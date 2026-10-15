# android/input — escape encoder + jestler

Dokunma ve klavye girdisini terminalin anladığı byte'lara çeviren katman.

**Bileşenler (Faz B):**
- `encoder.kt` — xterm convention'ları: Ctrl/Alt/Shift, oklar, Home/End,
  PgUp/PgDn, F1–F12, Esc, Tab/Shift-Tab, DEL/BS (Alacritty `input/keyboard.rs`
  desen)
- `keys.kt` — ek-anahtar satırı (Ctrl+C/D/Z, Tab, ok tuşları)
- `paste.kt` — bracketed paste (mode 2004)
- `gestures.kt` — tap, double-tap (kelime seç), long-press (seçim modu),
  drag (genişletme), pinch zoom (satır anchoring), momentum scroll

Not: soft-keyboard mapping bu repoda ilk kez yazılıyor — DroshKeyboard
entegrasyonu Drosh entegrasyon fazında olacak.
