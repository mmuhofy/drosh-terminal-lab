# TODO — Drosh Terminal Lab

> Plan: [`PLAN.md`](PLAN.md) · Sıra: Faz A → B → C → D
> Kurallar:
> - Her fazın başındaki **Kapı (Gate)** geçilmeden fazın görevlerine başlanmaz.
> - Bir görev yapılmadan bırakılırsa "yapılmadı + neden" notuyla burada kalır;
>   sessizce silinmez.
> - Bir kapı geçilemezse TODO'ya Plan-B maddesi eklenir ve kullanıcıya
>   rapor edilir (varsayımla ilerlenmez).

---

## Faz 0 — Repo iskeleti

- [x] `drosh-terminal-lab` klasörü ve docs (README, PLAN, TODO, AGENTS)
- [x] git init + ilk commit
- [ ] `rust/` cargo workspace (Cargo.toml: workspace profilleri — LTO, strip,
      panic=abort; cargo-ndk hedefleri)
- [ ] `android/` gradle iskeleti (boş app modülü, minSdk 26, ndk arm64-v8a)
- [ ] `.gitignore` (Rust + Android + Gradle + IDE)
- [ ] CI iskeleti (cargo check + gradle assembleDebug) — Faz A ile birlikte
      kurulur, ayrı görev değil
- [ ] klasör README'leri: `rust/core/`, `rust/chroot-ng/`, `android/app/`,
      `android/renderer/`, `android/input/`, `android/guest/`, `bench/`

## Faz A — Motoru Android'e indirmek (~1 hafta)

**Amaç:** alacritty_terminal'ın Android'de derlenip basit bir PTY oturumunu
parse ettiğini kanıtlamak. (Detay: PLAN.md §7-Faz A)

### Kapı-1 (başlamadan önce)
- [ ] `cargo check --target aarch64-linux-android` — alacritty_terminal =0.26.0,
      rustix-openpty, polling, vte 0.15, jni, rustybuzz, swash derleniyor mu?
- [ ] Sonuç TODO'ya kaydedilir (derlendi / hangi bağımlılıkta çöktü)

### Rust core
- [ ] `core/Cargo.toml`: cdylib, bağımlılıklar (pin'li sürümler)
- [ ] `core/src/lib.rs`: cdylib giriş, JNI_OnLoad, panic hook
- [ ] `core/src/ffi/mod.rs`: hata kodu enum'u + Kotlin aynası + yardımcılar
- [ ] `core/src/session/manager.rs`: Session yapısı (Term + EventLoop), handle tablosu
- [ ] `core/src/session/pty_factory.rs`: LocalPty — libc forkpty + setsid,
      OEM fallback (login-tty başarısızsa slave-stdio)
- [ ] `core/src/session/mod.rs`: spawn/write/resize/kill/close yaşam döngüsü
- [ ] `core/src/ffi/session.rs`: JNI fonksiyonları
- [ ] `core/src/bridge/cell.rs`: cell encoding (codepoint u32 + packed style u32)
- [ ] `core/src/bridge/snapshot.rs`: Term → flat cell buffer + satır hasar listesi
- [ ] `core/src/ffi/snapshot.rs`: DirectByteBuffer + damage indeksleri
- [ ] `core/src/bridge/events.rs`: EventListener (title/bell/cwd/exit en azından)
- [ ] `core/src/ffi/events.rs`: olay kuyruğu drenajı
- [ ] `core/src/util/*`: log, sync, error

### Kotlin ince kabuk
- [ ] `android/app/`: MainActivity + SurfaceView (EGL tutucu) + JNI binding
- [ ] Snapshot okuma + ham satır çizimi (basit text/textview bile olur; hedef
      sadece veri akışını kanıtlamak)

### Testler
- [ ] `tests/session_tests.rs`: host'ta `/bin/sh` + echo/ls uçtan uca
- [ ] `tests/snapshot_tests.rs`: grid → buffer doğruluğu, damage sınırları

### Kabul
- [ ] Fiziksel cihazda `echo hello` / `ls` çıktısı snapshot üzerinden ekrana
      çiziliyor
- [ ] `cargo test` yeşil
- [ ] Kapı-1 geçti

## Faz B — Renderer + input (~2 hafta)

### Kapı-2
- [ ] swash Android'de derleniyor ve glyph bitmap (alpha) üretiyor mu?
- [ ] Renkli emoji yolu kararı: swash CBDT/COLR destekliyorsa atlas'e;
      değilse Android native text overlay — karar TODO'ya yazılır

### Rust font yığını
- [ ] `font/shape.rs`: rustybuzz cluster → glyph
- [ ] `font/raster.rs`: swash rasterize (alpha + metrics)
- [ ] `font/atlas_api.rs`: Kotlin atlas'e kayıt formatı (UV, metrics)
- [ ] `font/fallback.rs`: fallback zinciri (ASCII → Latin → CJK → emoji)
- [ ] `font/mod.rs`: FontStack + cache
- [ ] `ffi/font.rs`: JNI shape/rasterize istekleri

### Kotlin renderer
- [ ] `renderer/atlas.kt`: bitmap atlas + 1x/1.5x/2x/3x boyut kovaları
- [ ] `renderer/shader.kt`: GLES3 instancing (vertex + quad shader)
- [ ] `renderer/quads.kt`: instanced quad batch (glyph + bg/cursor rect)
- [ ] `renderer/loop.kt`: Choreographer + damage-limited redraw + frame skip
- [ ] Cursor: blink lokasyonu + shape (block/bar) — sürekli tam redraw olmadan

### Kotlin input
- [ ] `input/encoder.kt`: escape encoder (Ctrl/Alt/Shift, oklar, Home/End,
      PgUp/PgDn, F1–F12, Esc, Tab/Shift-Tab, DEL/BS)
- [ ] `input/keys.kt`: ek-anahtar satırı (Ctrl+C/D/Z, Tab, ok tuşları)
- [ ] `input/paste.kt`: bracketed paste (2004)
- [ ] `input/gestures.kt`: tap, double-tap (kelime), long-press (seçim modu),
      drag (genişletme), pinch zoom (satır anchoring), momentum scroll

### Benchmark
- [ ] `bench/`: frame p50/p99 + jank sayımı panosu (ekran içi overlay)

### Kabul
- [ ] frame p99 < 8 ms
- [ ] tam ekran çıktıda jank yok
- [ ] pinch-zoom keskin, seçim + kopyalama + paste çalışıyor
- [ ] Hedef tutmazsa: ölçüm verisiyle Alacritty renderer portu Plan-B değerlendirmesi

## Faz C — chroot-ng + Ubuntu (~1-2 hafta)

### Kapı-3
- [ ] Cihazda `chroot-ng --probe`: seccomp/execmem/noexec viability raporu
- [ ] execmem reddedilmişse file-backed yükleme modu çalışıyor mu?

### Vendoring + launch
- [ ] `rust/chroot-ng/` vendoring: commit-pin + patch branch kurulumu
- [ ] CI cross-build (aarch64) + APK'ya konulması
- [ ] `pty_factory.rs`: chroot-ng argv eşlemesi (`-u`, `-w`, `-b`, `-E`)
- [ ] `android/guest/`: rootfs indirme + doğrulama + extract (Drosh adaptasyonu)

### Guest fidelity listesi
- [ ] `apt update && apt install -y build-essential`
- [ ] tmux: oturum aç/kapat, pane böl
- [ ] Ctrl+C / Ctrl+Z / Ctrl+D davranışı
- [ ] Resize (SIGWINCH) — rotasyon ve renderer resize
- [ ] `su` / setuid araçlar
- [ ] `ps` (guest process görünümü)
- [ ] shebang script'ler (postinst tarzı)
- [ ] iki büyük çıktı testi: apt kurulumu + go derlemesi

### Benchmark
- [ ] Aynı yükler Termux (proot referans) ile aynı cihazda karşılaştırma:
      apt install / go build / dosya ağırlıklı iş süreleri
- [ ] Sonuçlar PLAN.md'ye işlenir

### Kabul
- [ ] fidelity listesi tamam
- [ ] Benchmark kazancı sayısal dokümante

## Faz D — Paketleme + polish (~1 hafta)

- [ ] Release build: `.so` boyutu ≤ 8 MB (ölçülür), arm64-v8a
- [ ] README güncel (ekran görüntüleri + benchmark sonuçları)
- [ ] AGENTS.md gözden geçirme (Faz A–C'te öğrenilen derslerle güncelleme)
- [ ] Prototip sonuç raporu → Drosh entegrasyon kararı

## Sonraki (Drosh entegrasyonu — ayrı plan, bu repoda değil)

- [ ] `:terminal-core` modülü olarak Drosh'a taşıma
- [ ] Block engine OSC 133 bağlantısı
- [ ] Agent shell tool bağlantısı
- [ ] SSH (SSHJ → FdPair/uzaktan PTY) — Drosh entegrasyon fazında
- [ ] Mevcut Termux motorunun geri plana çekilmesi
- [ ] DroshKeyboard ile entegrasyon
