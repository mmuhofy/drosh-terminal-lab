# Rust çekirdek (`libdrosh-core.so`)

Drosh terminal çekirdeğinin Rust kısmı — cdylib olarak üretilir, Android tarafı
JNI ile konuşur.

**İçerik (Faz A'da yazılır):** `ffi/` (JNI sınırı), `session/` (Term + EventLoop +
PTY), `bridge/` (snapshot + olaylar), `font/` (rustybuzz + swash).

**Bağımlılıklar:** `alacritty_terminal =0.26.0` (VT motoru), `jni`, `libc`,
`rustybuzz`, `swash`. Ayrıntı: [`../../PLAN.md`](../../PLAN.md) §5.
