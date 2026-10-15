//! `native*` functions for the event queue.
//!
//! `Wakeup` (the frame trigger) is a flag, not a queue entry: it arrives with
//! every PTY read. The rest are rare and cross as newline-separated records.

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong};
use jni::JNIEnv;

use crate::bridge::events::SessionEvent;
use crate::ffi::session_or;

/// Was there a `Wakeup` since the last call?
///
/// An unknown handle answers `false`: a stale handle must not make the render
/// loop spin.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_lab_core_NativeCore_nativePollWakeup(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jboolean {
    match session_or(id) {
        Ok(session) => u8::from(session.poll_wakeup()),
        Err(_) => 0,
    }
}

/// Drain the queued events as newline-separated records.
///
/// Frozen record format (mirrored in `NativeCore.kt`): `title:…` (newlines
/// replaced by spaces), `reset_title`, `bell`, `exit`, `child_exit:CODE`,
/// `color_query:INDEX`, `cursor_blink_change`. An unknown handle yields a
/// single `error:CODE` record, so a bug is visible instead of silent.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_lab_core_NativeCore_nativeDrainEvents<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> JString<'local> {
    let text = match session_or(id) {
        Ok(session) => {
            let records: Vec<String> = session.drain_events().iter().map(encode_event).collect();
            records.join("\n")
        }
        Err(code) => format!("error:{}", code.code()),
    };

    env.new_string(text).unwrap_or_default()
}

fn encode_event(event: &SessionEvent) -> String {
    match event {
        SessionEvent::Title(title) => format!("title:{}", sanitize(title)),
        SessionEvent::ResetTitle => "reset_title".to_owned(),
        SessionEvent::Bell => "bell".to_owned(),
        SessionEvent::Exit => "exit".to_owned(),
        SessionEvent::ChildExit(code) => format!("child_exit:{code}"),
        SessionEvent::ColorQuery(index) => format!("color_query:{index}"),
        SessionEvent::CursorBlinkingChange => "cursor_blink_change".to_owned(),
    }
}

/// Records are newline-separated, so a title must not contain one.
fn sanitize(text: &str) -> String {
    text.replace('\n', " ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffi::ErrorCode;

    #[test]
    fn records_are_line_safe_and_stable() {
        assert_eq!(encode_event(&SessionEvent::Bell), "bell");
        assert_eq!(encode_event(&SessionEvent::Exit), "exit");
        assert_eq!(encode_event(&SessionEvent::ResetTitle), "reset_title");
        assert_eq!(encode_event(&SessionEvent::CursorBlinkingChange), "cursor_blink_change");
        assert_eq!(encode_event(&SessionEvent::ChildExit(7)), "child_exit:7");
        assert_eq!(encode_event(&SessionEvent::ColorQuery(11)), "color_query:11");
        assert_eq!(encode_event(&SessionEvent::Title("a\nb".to_owned())), "title:a b");
    }

    #[test]
    fn error_record_carries_the_code() {
        assert_eq!(format!("error:{}", ErrorCode::InvalidHandle.code()), "error:-1");
    }
}
