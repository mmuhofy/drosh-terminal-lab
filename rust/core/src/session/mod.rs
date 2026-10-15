//! A terminal session: the engine, its event loop thread, the PTY, and the
//! buffers the JNI layer hands to Kotlin.
//!
//! Lock order (never violated, here and in `ffi`): `term` → `damage_scratch` →
//! `damage`. Taking them in any other order would deadlock against a concurrent
//! snapshot.

pub mod manager;
pub mod pty_factory;

use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::{self, JoinHandle};

use alacritty_terminal::event::WindowSize;
use alacritty_terminal::event_loop::{EventLoop, EventLoopSender, Msg};
use alacritty_terminal::grid::{Dimensions, Scroll};
use alacritty_terminal::sync::FairMutex;
use alacritty_terminal::term::{Config, LineDamageBounds, Term};

use crate::bridge::events::{EventProxy, EventQueue, SessionEvent, DEFAULT_QUEUE_CAPACITY};
use crate::bridge::snapshot;
use crate::session::pty_factory::{LocalPty, PtyConfig};

/// Grid dimensions of a session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GridSize {
    columns: u16,
    rows: u16,
}

impl GridSize {
    /// The engine does not work below these bounds.
    pub const MIN_COLUMNS: u16 = 2;
    pub const MIN_ROWS: u16 = 1;

    pub const fn new(columns: u16, rows: u16) -> Self {
        Self { columns, rows }
    }

    pub const fn cols(&self) -> usize {
        self.columns as usize
    }

    pub const fn rows(&self) -> usize {
        self.rows as usize
    }

    /// Whether the engine can be asked to use this size.
    pub fn is_valid(&self) -> bool {
        self.columns >= Self::MIN_COLUMNS && self.rows >= Self::MIN_ROWS
    }
}

impl Dimensions for GridSize {
    fn total_lines(&self) -> usize {
        self.rows()
    }

    fn screen_lines(&self) -> usize {
        self.rows()
    }

    fn columns(&self) -> usize {
        self.cols()
    }
}

/// Everything needed to start a session. The prototype has no config system
/// (D12): the caller passes exactly what it wants.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionConfig {
    pub size: GridSize,
    pub cell_width: u16,
    pub cell_height: u16,
    /// Program to run on the slave side (`/system/bin/sh`, `chroot-ng`, …).
    pub program: String,
    pub args: Vec<String>,
    /// Extra environment variables; `TERM`, `COLORTERM` and `LANG` have
    /// defaults, `PATH` only on Android.
    pub env: Vec<(String, String)>,
    pub cwd: Option<String>,
}

/// What can go wrong around a session.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionError {
    InvalidArgument(String),
    InvalidHandle,
    LimitReached,
    SpawnFailed(String),
    Io(String),
    Internal(String),
}

impl std::fmt::Display for SessionError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidArgument(reason) => write!(formatter, "invalid argument: {reason}"),
            Self::InvalidHandle => write!(formatter, "unknown session"),
            Self::LimitReached => write!(formatter, "session limit reached"),
            Self::SpawnFailed(reason) => write!(formatter, "spawn failed: {reason}"),
            Self::Io(reason) => write!(formatter, "io error: {reason}"),
            Self::Internal(reason) => write!(formatter, "internal error: {reason}"),
        }
    }
}

impl std::error::Error for SessionError {}

/// A live terminal session.
pub struct Session {
    id: u64,
    term: Arc<FairMutex<Term<EventProxy>>>,
    events: Arc<EventQueue>,
    sender: EventLoopSender,
    size: Arc<Mutex<WindowSize>>,
    damage: Mutex<Vec<i32>>,
    damage_scratch: Mutex<Vec<LineDamageBounds>>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl Session {
    /// Start a PTY, the engine and the event loop for session `id`.
    pub fn spawn(id: u64, config: &SessionConfig) -> Result<Self, SessionError> {
        validate(config)?;

        let window_size = WindowSize {
            num_lines: config.size.rows as u16,
            num_cols: config.size.columns as u16,
            cell_width: config.cell_width,
            cell_height: config.cell_height,
        };

        let events = Arc::new(EventQueue::new(DEFAULT_QUEUE_CAPACITY));
        let sender_slot = Arc::new(Mutex::new(None));
        let size_slot = Arc::new(Mutex::new(window_size));

        let proxy = EventProxy::new(events.clone(), sender_slot.clone(), size_slot.clone());
        let terminal = Term::new(Config::default(), &config.size, proxy.clone());
        let term = Arc::new(FairMutex::new(terminal));

        let pty = LocalPty::spawn(&PtyConfig {
            program: config.program.clone(),
            args: config.args.clone(),
            env: config.env.clone(),
            cwd: config.cwd.clone(),
            columns: config.size.columns,
            rows: config.size.rows,
            cell_width: config.cell_width,
            cell_height: config.cell_height,
        })
        .map_err(|err| SessionError::SpawnFailed(err.to_string()))?;

        // `drain_on_exit`: the shell's last output must still reach the grid
        // after the child is gone. `ref_test` is alacritty's replay format —
        // not used by the prototype.
        let event_loop = EventLoop::new(term.clone(), proxy, pty, true, false)
            .map_err(|err| SessionError::Io(err.to_string()))?;

        let sender = event_loop.channel();
        *lock(&sender_slot) = Some(sender.clone());

        let worker = thread::Builder::new()
            .name(format!("drosh-session-{id}"))
            .spawn(move || {
                // The engine runs the loop on a thread of its own; joining it
                // here means the PTY (and with it the child process) is torn
                // down when the loop ends, even though nobody joins us.
                if let Err(err) = event_loop.spawn().join() {
                    log::error!("drosh-core: event loop for session {id} died: {err:?}");
                }
            })
            .map_err(|err| SessionError::Internal(err.to_string()))?;

        Ok(Self {
            id,
            term,
            events,
            sender,
            size: size_slot,
            damage: Mutex::new(vec![0i32; snapshot::damage_out_len(config.size.rows())]),
            damage_scratch: Mutex::new(Vec::new()),
            worker: Mutex::new(Some(worker)),
        })
    }

    pub fn id(&self) -> u64 {
        self.id
    }

    /// Send input to the PTY.
    pub fn write(&self, data: &[u8]) -> Result<(), SessionError> {
        if data.is_empty() {
            return Ok(());
        }

        self.sender.send(Msg::Input(data.to_vec().into())).map_err(io_error)
    }

    /// Resize grid, PTY and renderer state.
    pub fn resize(
        &self,
        size: GridSize,
        cell_width: u16,
        cell_height: u16,
    ) -> Result<(), SessionError> {
        if !size.is_valid() {
            return Err(SessionError::InvalidArgument(format!("invalid grid size {size:?}")));
        }

        let window_size =
            WindowSize { num_lines: size.rows, num_cols: size.columns, cell_width, cell_height };

        *lock(&self.size) = window_size;
        {
            // `Term::resize` marks everything damaged, so the next snapshot is
            // a full redraw.
            let mut term = self.term.lock();
            term.resize(size);
        }
        lock(&self.damage).resize(snapshot::damage_out_len(size.rows()), 0);

        self.sender.send(Msg::Resize(window_size)).map_err(io_error)
    }

    /// Scroll the viewport (positive = back into the scrollback history).
    pub fn scroll(&self, delta: i32) -> Result<(), SessionError> {
        if delta == 0 {
            return Ok(());
        }

        // `scroll_display` marks the whole viewport damaged when the offset
        // actually changed.
        self.term.lock().scroll_display(Scroll::Delta(delta));
        Ok(())
    }

    /// Fill `buffer` with the damaged cells and return `damage[0]`:
    /// [`snapshot::DAMAGE_FULL`] or the number of damaged lines.
    pub fn take_snapshot(&self, buffer: &mut [u8]) -> Result<i32, snapshot::SnapshotError> {
        let mut term = self.term.lock();
        let mut scratch = lock(&self.damage_scratch);
        let mut damage = lock(&self.damage);

        snapshot::take_snapshot(&mut term, buffer, &mut damage, &mut scratch)?;
        Ok(damage[0])
    }

    /// Run `f` with the damage list of the last [`Session::take_snapshot`].
    pub fn with_damage<R>(&self, f: impl FnOnce(&[i32]) -> R) -> R {
        f(&lock(&self.damage))
    }

    /// Snapshot the renderer state (size, cursor, modes).
    pub fn state(&self) -> [i32; snapshot::STATE_LEN] {
        let mut out = [0i32; snapshot::STATE_LEN];
        snapshot::write_state(&self.term.lock(), &mut out);
        out
    }

    /// Take and clear the "something changed" flag.
    pub fn poll_wakeup(&self) -> bool {
        self.events.take_wakeup()
    }

    /// Remove and return every queued event.
    pub fn drain_events(&self) -> Vec<SessionEvent> {
        self.events.drain()
    }

    /// Run `f` with the engine's terminal, holding its lock.
    pub fn with_term<R>(&self, f: impl FnOnce(&mut Term<EventProxy>) -> R) -> R {
        let mut term = self.term.lock();
        f(&mut term)
    }

    /// Stop the event loop; the PTY and the child are torn down when the loop
    /// thread returns.
    pub fn shutdown(&self) {
        let _ = self.sender.send(Msg::Shutdown);
        drop(lock(&self.worker).take());
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        // Sessions never outlive their handle: losing the last reference must
        // not leave a child process behind.
        self.shutdown();
    }
}

/// Recover from a poisoned lock: one panicking caller must not take the session
/// with it.
fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn io_error(err: impl std::fmt::Display) -> SessionError {
    SessionError::Io(err.to_string())
}

fn validate(config: &SessionConfig) -> Result<(), SessionError> {
    if !config.size.is_valid() {
        return Err(SessionError::InvalidArgument(format!("invalid grid size {:?}", config.size)));
    }
    if config.program.is_empty() {
        return Err(SessionError::InvalidArgument("program is empty".to_owned()));
    }
    if config.program.contains('\0') {
        return Err(SessionError::InvalidArgument("program contains a NUL byte".to_owned()));
    }

    if config.args.iter().any(|arg| arg.contains('\0')) {
        return Err(SessionError::InvalidArgument("argument contains a NUL byte".to_owned()));
    }

    for (key, value) in &config.env {
        if key.is_empty() || key.contains('=') || key.contains('\0') || value.contains('\0') {
            return Err(SessionError::InvalidArgument(format!(
                "invalid environment entry {key:?}"
            )));
        }
    }

    Ok(())
}
