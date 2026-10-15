//! Engine events → the queue Kotlin drains.
//!
//! Two kinds of events arrive from [`alacritty_terminal::event::EventListener`]:
//!
//! - Replies that must go back to the PTY (`PtyWrite`, text-area size, colour
//!   queries). These are handled here, on the spot: an application waiting for
//!   an answer must not wait for a Kotlin frame.
//! - Everything else (title, bell, exit, …) is queued and drained by the app.

use std::collections::VecDeque;
use std::os::unix::process::ExitStatusExt;
use std::process::ExitStatus;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

use alacritty_terminal::event::{Event, EventListener, WindowSize};
use alacritty_terminal::event_loop::{EventLoopSender, Msg};

/// Default queue capacity. Events are rare (title/bell/exit); the cap exists so
/// that a program spamming titles cannot grow the queue without bound.
pub const DEFAULT_QUEUE_CAPACITY: usize = 256;

/// Low-frequency session events, in the order the engine produced them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionEvent {
    /// `OSC 0`/`OSC 2`: the window title changed.
    Title(String),
    /// The title was reset to the application default.
    ResetTitle,
    /// `BEL`.
    Bell,
    /// The terminal asked to shut down (`Term::exit`).
    Exit,
    /// The child process exited; `128 + signal` when it was signalled.
    ChildExit(i32),
    /// `OSC 4`/`OSC 10`/`OSC 11` query. Kotlin owns the palette, so it owns
    /// the answer (TODO.md, Phase B).
    ColorQuery(i32),
    /// Cursor blink state changed.
    CursorBlinkingChange,
}

/// Events plus the "something changed" flag the render loop polls.
#[derive(Debug)]
pub struct EventQueue {
    events: Mutex<VecDeque<SessionEvent>>,
    wakeup: AtomicBool,
    capacity: usize,
}

impl EventQueue {
    pub fn new(capacity: usize) -> Self {
        Self { events: Mutex::new(VecDeque::new()), wakeup: AtomicBool::new(false), capacity }
    }

    /// A frame's worth of change happened (`Event::Wakeup`).
    pub fn set_wakeup(&self) {
        self.wakeup.store(true, Ordering::Release);
    }

    /// Take and clear the wakeup flag.
    pub fn take_wakeup(&self) -> bool {
        self.wakeup.swap(false, Ordering::AcqRel)
    }

    /// Append an event, keeping the queue bounded.
    pub fn push(&self, event: SessionEvent) {
        let mut events = self.lock();

        if events.len() >= self.capacity {
            // Exit and ChildExit are never dropped: losing them would leave
            // Kotlin with a dead session it does not know about.
            let victim = events.iter().position(|queued| {
                !matches!(queued, SessionEvent::Exit | SessionEvent::ChildExit(_))
            });

            match victim {
                Some(index) => {
                    events.remove(index);
                }
                None => {
                    log::warn!("drosh-core: event queue full of exit events");
                    events.pop_front();
                }
            }
        }

        events.push_back(event);
    }

    /// Remove and return everything queued so far.
    pub fn drain(&self) -> Vec<SessionEvent> {
        self.lock().drain(..).collect()
    }

    fn lock(&self) -> MutexGuard<'_, VecDeque<SessionEvent>> {
        self.events.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// The engine's event listener.
///
/// Cloned into the [`alacritty_terminal::Term`] and into the event loop: the
/// Term needs it to report events while parsing, the loop to report
/// `Wakeup`/`ChildExit`.
#[derive(Clone)]
pub struct EventProxy {
    queue: Arc<EventQueue>,
    pty: Arc<Mutex<Option<EventLoopSender>>>,
    size: Arc<Mutex<WindowSize>>,
}

impl EventProxy {
    /// Build a proxy.
    ///
    /// `pty` is filled in after the event loop exists: the loop needs the
    /// `Term`, the `Term` needs the proxy, so the sender can only be wired up
    /// once the loop has been created.
    pub fn new(
        queue: Arc<EventQueue>,
        pty: Arc<Mutex<Option<EventLoopSender>>>,
        size: Arc<Mutex<WindowSize>>,
    ) -> Self {
        Self { queue, pty, size }
    }

    /// Write an escape-sequence reply to the PTY.
    fn write_pty(&self, text: String) {
        if text.is_empty() {
            return;
        }

        let guard = self.pty.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        match guard.as_ref() {
            Some(sender) => {
                if let Err(err) = sender.send(Msg::Input(text.into_bytes().into())) {
                    log::warn!("drosh-core: PTY reply failed: {err}");
                }
            }
            None => log::debug!("drosh-core: dropping PTY reply, event loop not ready yet"),
        }
    }

    fn window_size(&self) -> WindowSize {
        *self.size.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl EventListener for EventProxy {
    fn send_event(&self, event: Event) {
        match event {
            Event::Wakeup => self.queue.set_wakeup(),
            Event::Title(title) => self.queue.push(SessionEvent::Title(title)),
            Event::ResetTitle => self.queue.push(SessionEvent::ResetTitle),
            Event::Bell => self.queue.push(SessionEvent::Bell),
            Event::Exit => self.queue.push(SessionEvent::Exit),
            Event::ChildExit(status) => self.queue.push(SessionEvent::ChildExit(exit_code(status))),
            Event::PtyWrite(text) => self.write_pty(text),
            Event::TextAreaSizeRequest(format) => {
                let size = self.window_size();
                self.write_pty(format(size));
            }
            Event::ColorRequest(index, _format) => {
                self.queue.push(SessionEvent::ColorQuery(index as i32));
            }
            Event::CursorBlinkingChange => self.queue.push(SessionEvent::CursorBlinkingChange),
            Event::MouseCursorDirty => {}
            Event::ClipboardStore(_, _) | Event::ClipboardLoad(_, _) => {
                // OSC 52 is out of Phase A scope (TODO.md): the prototype has no
                // clipboard integration yet.
                log::debug!("drosh-core: clipboard event ignored in the prototype");
            }
        }
    }
}

/// Map a child's exit status to a plain code (`128 + signal` when signalled).
pub fn exit_code(status: ExitStatus) -> i32 {
    match status.code() {
        Some(code) => code,
        None => status.signal().map_or(1, |signal| 128 + signal),
    }
}
