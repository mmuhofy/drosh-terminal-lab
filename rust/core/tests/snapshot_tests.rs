//! Grid → buffer correctness, driven through the engine directly (no PTY).

use alacritty_terminal::event::VoidListener;
use alacritty_terminal::term::{Config, Term};
use alacritty_terminal::vte::ansi::Processor;

use drosh_core::bridge::cell::CellRecord;
use drosh_core::bridge::cell::CELL_BYTES;
use drosh_core::bridge::snapshot::DAMAGE_FULL;
use drosh_core::bridge::snapshot::STATE_LEN;
use drosh_core::bridge::snapshot::SnapshotError;
use drosh_core::bridge::snapshot::cursor_shape;
use drosh_core::bridge::snapshot::damage_out_len;
use drosh_core::bridge::snapshot::state_index;
use drosh_core::bridge::snapshot::take_snapshot;
use drosh_core::bridge::snapshot::write_state;
use drosh_core::session::GridSize;

const COLUMNS: usize = 20;
const ROWS: usize = 4;

fn new_term() -> Term<VoidListener> {
    let size = GridSize::new(COLUMNS as u16, ROWS as u16);
    Term::new(Config::default(), &size, VoidListener)
}

fn feed(term: &mut Term<VoidListener>, bytes: &[u8]) {
    Processor::new().advance(term, bytes);
}

fn snapshot(term: &mut Term<VoidListener>, buffer: &mut [u8], damage: &mut [i32]) -> i32 {
    let mut scratch = Vec::new();
    take_snapshot(term, buffer, damage, &mut scratch).expect("snapshot");
    damage[0]
}

fn new_buffers(columns: usize, rows: usize) -> (Vec<u8>, Vec<i32>) {
    (vec![0u8; columns * rows * CELL_BYTES], vec![0i32; damage_out_len(rows)])
}

fn record(buffer: &[u8], columns: usize, row: usize, column: usize) -> CellRecord {
    let at = (row * columns + column) * CELL_BYTES;
    CellRecord::decode(&buffer[at..at + CELL_BYTES]).expect("record")
}

#[test]
fn first_snapshot_is_full_and_encodes_the_grid() {
    let mut term = new_term();
    feed(&mut term, b"hi");

    let (mut buffer, mut damage) = new_buffers(COLUMNS, ROWS);
    assert_eq!(snapshot(&mut term, &mut buffer, &mut damage), DAMAGE_FULL);

    assert_eq!(record(&buffer, COLUMNS, 0, 0).codepoint, u32::from(b'h'));
    assert_eq!(record(&buffer, COLUMNS, 0, 1).codepoint, u32::from(b'i'));
    assert_eq!(record(&buffer, COLUMNS, 0, 2).codepoint, u32::from(b' '));
    // The last cell of the viewport is part of the snapshot, too.
    assert_eq!(record(&buffer, COLUMNS, ROWS - 1, COLUMNS - 1).codepoint, u32::from(b' '));
}

#[test]
fn partial_snapshot_copies_and_reports_the_damaged_span() {
    let mut term = new_term();
    feed(&mut term, b"hi");

    let (mut buffer, mut damage) = new_buffers(COLUMNS, ROWS);
    assert_eq!(snapshot(&mut term, &mut buffer, &mut damage), DAMAGE_FULL);

    feed(&mut term, b"XY");
    assert_eq!(snapshot(&mut term, &mut buffer, &mut damage), 1, "damage is on the first line");

    assert_eq!(damage[1], 0, "damaged row");
    assert!(damage[2] <= 2, "left edge covers the old cursor position: {}", damage[2]);
    assert!(damage[3] >= 4, "right edge covers the new cursor position: {}", damage[3]);

    assert_eq!(record(&buffer, COLUMNS, 0, 2).codepoint, u32::from(b'X'));
    assert_eq!(record(&buffer, COLUMNS, 0, 3).codepoint, u32::from(b'Y'));
}

#[test]
fn idle_terminal_only_damages_the_cursor_cell() {
    let mut term = new_term();
    feed(&mut term, b"hi");

    let (mut buffer, mut damage) = new_buffers(COLUMNS, ROWS);
    snapshot(&mut term, &mut buffer, &mut damage);

    assert_eq!(snapshot(&mut term, &mut buffer, &mut damage), 1);
    assert_eq!(damage[1], 0);
    assert_eq!(damage[2], damage[3], "a cursor is a single cell");
}

#[test]
fn resize_forces_a_full_redraw_and_reflows() {
    let mut term = new_term();
    feed(&mut term, b"hello");

    let (mut buffer, mut damage) = new_buffers(COLUMNS, ROWS);
    snapshot(&mut term, &mut buffer, &mut damage);

    term.resize(GridSize::new(30, 5));
    let (mut buffer, mut damage) = new_buffers(30, 5);

    assert_eq!(snapshot(&mut term, &mut buffer, &mut damage), DAMAGE_FULL);
    assert_eq!(record(&buffer, 30, 0, 0).codepoint, u32::from(b'h'));
    assert_eq!(record(&buffer, 30, 0, 4).codepoint, u32::from(b'o'));
}

#[test]
fn undersized_buffers_are_rejected() {
    let mut term = new_term();
    let mut scratch = Vec::new();

    let mut damage = vec![0i32; damage_out_len(ROWS)];
    let mut too_small = vec![0u8; COLUMNS * ROWS * CELL_BYTES - 1];
    assert!(matches!(
        take_snapshot(&mut term, &mut too_small, &mut damage, &mut scratch),
        Err(SnapshotError::BufferTooSmall { .. })
    ));

    let mut buffer = vec![0u8; COLUMNS * ROWS * CELL_BYTES];
    let mut too_few = vec![0i32; 1];
    assert!(matches!(
        take_snapshot(&mut term, &mut buffer, &mut too_few, &mut scratch),
        Err(SnapshotError::DamageOutTooSmall { .. })
    ));
}

#[test]
fn state_reports_size_cursor_and_offset() {
    let mut term = new_term();
    feed(&mut term, b"abc\r\ndef");

    let mut out = [0i32; STATE_LEN];
    write_state(&term, &mut out);

    assert_eq!(out[state_index::COLUMNS], COLUMNS as i32);
    assert_eq!(out[state_index::ROWS], ROWS as i32);
    assert_eq!(out[state_index::CURSOR_ROW], 1);
    assert_eq!(out[state_index::CURSOR_COL], 3);
    assert_eq!(out[state_index::CURSOR_SHAPE], cursor_shape::BLOCK);
    assert_eq!(out[state_index::DISPLAY_OFFSET], 0);
    assert_ne!(out[state_index::MODE_BITS], 0, "the default mode set is not empty");
}
