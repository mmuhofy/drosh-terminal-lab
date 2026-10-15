# PLAN — Drosh Terminal Lab

> Son güncelleme: 2026-10-15 · Durum: kararlar netleşti, implementasyona hazır
> Bu doküman canlıdır: karar değişirse burada gerekçesiyle güncellenir,
> eski karar "değişti:" notuyla arşivlenir, sessizce silinmez.

---

## 1. Amaç ve kapsam

Drosh'un yeni terminal çekirdeğini (Rust core + chroot-ng + GL renderer) Drosh'un
içinde değil, **ayrı bir prototip uygulamada** kanıtlamak. Kanıtlanınca çekirdek
Drosh'a modül olarak taşınır; entegrasyon ayrı bir plan ve fazdır (bkz. §10).

Prototip **yalnızca terminaldir**: ekrana terminal çizen, girdi alan, bir
Ubuntu userspace'i chroot-ng altında çalıştıran minimal bir uygulama. Amaç,
çekirdeğin performans ve doğruluğunu Drosh'un karmaşıklığından arınmış bir
ortamda ölçmek ve riskleri erken azaltmaktır.

## 2. Bağlam: neden değişiyoruz?

Drosh bugün vendored Termux motoru (Java) + proot ile Ubuntu 24.04 çalıştırıyor.
Tespit edilen üç temel darboğaz (Drosh'un kendi PERFORMANCE-PLAN.md analiziyle
uyumlu):

1. **Guest runtime**: proot'un per-syscall ptrace maliyeti — apt, derleme,
   paket kurulumu gibi syscall-ağır işlerde ciddi yavaşlama.
2. **Parse + frame politikası**: motor main thread'te parse ediyor, her chunk'ta
   frame, idle'da bile çizim; glyph atlas yok.
3. **Motorun dili**: Java motorun kendisi çoğu iş için yeterli ama desktop-class
   mimari (IO thread + ring buffer grid + damage + GPU atlas) Java tarafta
   taşınabilir olsa da ekosistemin kanıtlanmış çözümü Rust'ta hazır.

Mobil kısıtlar (değişmeyen): targetSdk 28 + F-Droid/sideload (Android 10+
targetSdk ≥ 29'da app data'da execve yasağı — Ubuntu-under-PRoot modeli buna
mahkûm; Play kapalı, install floor zamanla yükselecek, bilinen kabul edilmiş risk).

## 3. Kararlar

| # | Konu | Karar | Gerekçe / kanıt |
|---|---|---|---|
| D1 | Guest runtime | **chroot-ng** (proot yerine), vendored subtree + commit-pin + patch branch | Ptrace-free (seccomp RET_TRAP + kendi userland ELF loader'ı); Android SELinux engellerini (pty ioctl whitelist, /proc stat, rtnetlink, SysV IPC) emüle ediyor; yazarın cihaz testlerinde apk/gcc/go+cgo/build zincirleri çalışıyor; Apache-2.0 |
| D2 | VT motoru | **alacritty_terminal `=0.26.0`** bağımlılık olarak | crates.io'da "library for writing terminal emulators" olarak yayınlanmış, 23 dosya/11.7K satır; ring-buffer grid + reflow + wide char + alt screen + damage + selection; Apache-2.0, GPL-3.0 uyumlu. 0.x olduğu için sürüm **sabit** pin'lenir, yükseltmeler bilinçli iş |
| D3 | GL renderer | **Kotlin, GL ES 3.0** — Alacritty renderer portu DEĞİL | Renderer mobilde en az kaldıraç gösteren yer (surface yaşam döngüsü, insets, foldable, Compose z-order Kotlin tarafında); snapshot zero-copy olduğundan Rust renderer'ın kazancı yok sayılır; iterasyon + debuggability (AGI/Perfetto). Plan-B tetikleyicisi §9'da |
| D4 | Font yığını | **Rust'ta rustybuzz (shaping) + swash (rasterize)**; atlas Kotlin'de bitmap, 1x/1.5x/2x/3x boyut kovaları | Android'da Kotlin tarafında shaping API'si yok (HarfBuzz erişimi yok); Alacritty glyph_cache/atlas deseni referans |
| D5 | Rust↔Kotlin köprüsü | **Snapshot** (render-komut listesi değil): DirectByteBuffer (zero-copy) + satır hasar listesi + olay kuyruğu | Basit ve hızlı; renderer Kotlin'deyken Rust'a gereksiz bağımlılık yapmaz |
| D6 | Input | Kotlin'de tam escape encoder + jest sözlüğü | xterm convention'ları (Alacritty `input/keyboard.rs` desen); DroshKeyboard altyapısıyla uyumlu |
| D7 | Protokoller | Dahil: OSC 8 (hyperlink), OSC 133 (semantic marker), OSC 0/2 (title), OSC 4 (palette), OSC 10/11 (fg/bg), bell, bracketed paste (2004), focus (1004). **Ertelenen: sixel, kitty graphics** | Alacritty motorunda sixel yok (Termux motorunda vardı — küçük gerileme, kabul); blok/agent UI pratik yeri tutar; talep gelirse ayrı iş |
| D8 | PTY implementasyonu | **libc** yolu (portable-pty DEĞİL) | portable-pty'nin `termios` bağımlılığı Android target'ında derlenmiyor; Termi'nin cihaz keşfiyle uyumlu |
| D9 | Dağıtım (Drosh için) | **targetSdk 28 + F-Droid/sideload** | Android 10+ targetSdk ≥ 29'da app data'da execve yasağı (W^X, SELinux); Ubuntu-under-PRoot bu yüzden 28'e mahkûm; Termux'un 7 yıllık kanıtlanmış yolu. Play kapalı; install floor'un yükselmesi bilinen uzun vadeli risk |
| D10 | Termi (MannanSaood/termi) | Sadece referans; temel DEĞİL | Emülasyonu toy (alt screen/wide char yok, ~10 CSI komutu), UI grid'den beslenmiyor; alınacaklar: JNI disiplini (opaque handle, panic hook, hata aynası) + PTY/OEM fallback dersleri; upstream'a generic katkı değerlendirilir |
| D11 | Warp / kitty / WezTerm / Ghostty | Kod değil, okuma | Warp AGPL (hukuki risk); kitty C kütüphane değil; wezterm-term yayınlanmamış ve API stabilitesi açıkça yok; libghostty Zig, API olgun değil |
| D12 | Prototip kapsamı | **Konfig sistemi yok, SSH yok** | Prototip sadece terminaldir; sabit makul varsayılanlarla çalışır (gerçek Drosh'ta TOML config sistemi var ve orada kalır). SSH (SSHJ/FdPair) tamamen Drosh entegrasyon fazına kalır |

### Karar geçmişi

- 2026-10-15: D12 güncellendi — prototipten konfig ve SSH çıkarıldı (kullanıcı
  kararı: prototype sadece terminal; Drosh'ta TOML sistemi mevcut).

## 4. Hedef mimari

```
┌──────────────────────────────────────────────────────────┐
│ Kotlin (Android)                                          │
│  app/      Activity, SurfaceView+EGL, oturum yaşam döngüsü│
│  input/    escape encoder + jestler (dokunma)             │
│  renderer/ GL ES 3.0: atlas, instanced quad, damage döngüsü│
│  ui/       Compose chrome: tab bar (terminal'i çizmez!)   │
│  guest/    rootfs bootstrap + chroot-ng launch            │
└───────────────┬──────────────────────────────────────────┘
                │ JNI (zero-copy snapshot + olay kuyruğu)
┌───────────────┴──────────────────────────────────────────┐
│ Rust core (cdylib "libdrosh-core.so")                     │
│  ffi/        session, snapshot, font, events              │
│  session/    Term + EventLoop + PTY (chroot-ng argv)      │
│  bridge/     Term → flat cell buffer + damage; EventListener│
│  font/       rustybuzz + swash → glyph bitmapler          │
└───────────────┬──────────────────────────────────────────┘
                │ PTY
┌───────────────┴──────────────────────────────────────────┐
│ chroot-ng (vendored, Apache-2.0) → Ubuntu 24.04 userspace │
└──────────────────────────────────────────────────────────┘
```

Veri akışı: chroot-ng → PTY → alacritty_terminal (parse + grid + damage) →
snapshot buffer → Kotlin GL renderer. Olaylar (OSC/title/bell/cwd/exit) ayrı
bir kuyrukta Kotlin'e drenajlanır.

## 5. Rust tarafı — dosya envanteri (~19 dosya, ~4.700 satır)

| Dosya | Satır | Sorumluluk |
|---|---|---|
| `core/src/lib.rs` | ~150 | cdylib giriş, JNI_OnLoad, panic hook |
| `core/src/ffi/mod.rs` | ~300 | hata kodu enum'u + Kotlin aynası + string/dizi yardımcıları |
| `core/src/ffi/session.rs` | ~250 | create/spawn/write/resize/kill/close |
| `core/src/ffi/snapshot.rs` | ~200 | DirectByteBuffer + satır hasar listesi |
| `core/src/ffi/font.rs` | ~200 | shape+rasterize istekleri |
| `core/src/ffi/events.rs` | ~150 | olay kuyruğu drenajı |
| `core/src/session/mod.rs` | ~300 | Session: Term + EventLoop + PTY + kanallar |
| `core/src/session/pty_factory.rs` | ~300 | LocalPty (chroot-ng argv) — libc forkpty + setsid |
| `core/src/session/manager.rs` | ~300 | handle tablosu, oturum yaşam döngüsü |
| `core/src/bridge/snapshot.rs` | ~350 | Term → flat cell buffer + damage hesaplama |
| `core/src/bridge/cell.rs` | ~120 | cell encoding (codepoint u32 + packed style u32) |
| `core/src/bridge/events.rs` | ~300 | EventListener implementasyonu → olay kuyruğu |
| `core/src/font/mod.rs` | ~200 | FontStack: shape + rasterize + cache |
| `core/src/font/shape.rs` | ~200 | rustybuzz: cluster → glyph |
| `core/src/font/raster.rs` | ~250 | swash: alpha/renkli bitmap |
| `core/src/font/atlas_api.rs` | ~150 | Kotlin atlas'e glyph kayıtları (UV, metrics) |
| `core/src/font/fallback.rs` | ~150 | font fallback zinciri + emoji yolu |
| `core/src/util/*` | ~200 | log, sync, error yardımcıları |
| `tests/` (3 dosya) | ~700 | golden VT + snapshot + PTY uçtan uca |

Not: chroot-ng Rust kodu değil, C (41K satır) — vendored subtree olarak durur,
dosya sayımıza girmez.

## 6. Kotlin tarafı

| Modül | Dosya (tahmini) | Sorumluluk |
|---|---|---|
| `android/app/` | 4-5 | Activity, SurfaceView/EGL sahibi, oturum yönetimi, JNI binding |
| `android/renderer/` | 5-6 | atlas (boyut kovaları), shader, instanced quad, rect (bg/cursor), render loop (Choreographer + damage) |
| `android/input/` | 3-4 | escape encoder, ek-anahtar satırı, jestler (seçim/scroll/zoom), clipboard |
| `android/guest/` | 2-3 | rootfs indirme/doğrulama/extract, chroot-ng launch |

## 7. Fazlar

### Faz A — Motoru Android'e indirmek (hedef ~1 hafta)

**Amaç:** alacritty_terminal'ın Android'de derlenip basit bir PTY oturumunu
parse ettiğini kanıtlamak.

- Repo iskeleti: cargo workspace + android/ + bench/; git init
- cargo-ndk profili: arm64-v8a, release (LTO, strip, panic=abort)
- **Kapı-1:** `cargo check --target aarch64-linux-android` — alacritty_terminal
  0.26.0, rustix-openpty, polling, vte 0.15, jni, rustybuzz, swash derleniyor mu?
- `ffi/` + `session/` + `bridge/` yazımı (Plan-B: rustix-openpty çalışmazsa
  tty katmanı libc'ye alınır)
- PTY: `/system/bin/sh` spawn (libc forkpty + setsid; Termi'nin OEM fallback
  deseni: login-tty spawn başarısızsa slave-stdio yolu)
- Kotlin ince kabuk: Activity + SurfaceView + JNI binding + snapshot okuma
- Host testleri: cargo test (golden VT, snapshot, PTY uçtan uca)

**Kabul:** fiziksel cihazda `echo hello` / `ls` çıktısı snapshot üzerinden
ekrana çiziliyor; cargo test yeşil; Kapı-1 geçti.

### Faz B — Renderer + input (hedef ~2 hafta)

**Amaç:** terminal kalitesinde çizim ve tam mobil girdi.

- `font/` yazımı: rustybuzz + swash, alpha bitmap + metrics
- **Kapı-2:** swash Android'de derleniyor ve glyph bitmap üretiyor mu?
  (renkli emoji yolu: swash CBDT/COLR destekliyorsa atlas'e; değilse emoji için
  Android native text overlay — prototipte çözülür)
- Kotlin renderer: atlas (1x–3x kovalar), shader, instanced quad, bg/cursor rect
- Render loop: Choreographer + damage-limited redraw + frame skip politikası
- Input: escape encoder (Ctrl/Alt/Shift, oklar, Home/End/PgUp/PgDn, F1–F12, Esc,
  Tab/Shift-Tab, DEL/BS), bracketed paste, ek-anahtar satırı
- Jestler: tap, double-tap (kelime), long-press (seçim modu), drag (genişletme),
  pinch zoom (satır anchoring), momentum scroll
- `bench/`: frame p50/p99 + jank sayımı panosu

**Kabul:** frame p99 < 8 ms; tam ekran çıktıda jank yok; pinch-zoom keskin;
seçim + kopyalama + paste çalışıyor. Hedef tutmazsa §9 Plan-B (renderer portu)
değerlendirilir.

### Faz C — chroot-ng + Ubuntu (hedef ~1-2 hafta)

**Amaç:** chroot-ng'yi proot yerine koymak ve guest fidelity + performansı
kanıtlamak.

- `rust/chroot-ng/` vendoring (commit-pin) + CI cross-build + patch branch
- **Kapı-3:** cihazda `chroot-ng --probe` — seccomp/execmem/noexec viability;
  execmem reddedilmişse file-backed yükleme modu çalışmalı
- `android/guest/`: rootfs indirme + doğrulama + extract (Drosh adaptasyonu)
- `pty_factory.rs`: chroot-ng argv eşlemesi (`-u`, `-w`, `-b`, `-E`)
- Guest fidelity listesi: `apt update && apt install -y build-essential`, tmux,
  Ctrl+C/Z, resize (SIGWINCH), `su`, `ps`, shebang script'ler, setuid araçlar
- Benchmark: aynı yükleri Termux (proot referans) ile aynı cihazda karşılaştır

**Kabul:** fidelity listesi tamam; benchmark kazancı sayısal olarak dokümante.

### Faz D — Paketleme + polish (hedef ~1 hafta)

- Release build: `.so` boyutu kontrolü (hedef ≤ 8 MB), arm64-v8a
- README/AGENTS.md güncel; benchmark sonuçları PLAN.md'ye işlenir
- Prototip sonuç raporu: Drosh entegrasyonuna "evet/hayır" kararı

## 8. Doğrulanması gerekenler (açık maddeler)

Bunlar **henüz doğrulanmamış** iddialardır, uydurma kararı almadan önce
sırasıyla test edilir:

1. alacritty_terminal 0.26.0'un `aarch64-linux-android` derlenmesi
   (rustix-openpty + polling + vte 0.15) — Faz A Kapı-1
2. Alacritty `tty/unix.rs` içindeki varsayılan shell/getpwuid yolu: explicit
   shell verildiğinde sorun olmamalı — Faz A'da doğrulanacak
3. swash'ın Android'de derlenip renkli emoji (CBDT/COLR) bitmap'i vermesi —
   Faz B Kapı-2; tutmazsa emoji overlay yolu
4. chroot-ng probe sonucu ve file-backed mod — Faz C Kapı-3
5. GL ES 3.0 instancing'in hedef cihazlarda mevcut olması (fallback: GLES2 yolu)

## 9. Riskler ve Plan-B tetikleyicileri

| Risk | Olasılık | Plan B / tetikleyici |
|---|---|---|
| alacritty_terminal Android'de derlenmiyor | Düşük | tty katmanını libc'ye al (Termi yolu); motorun kendisi saf Rust, sorun çıkması beklenmiyor |
| Kotlin renderer kalite/hedef tutmuyor (p99/jank/zoom) | Orta | **Faz B sonunda ölçülen veriyle** Alacritty renderer portu (Rust) değerlendirilir — ölçüm olmadan port'a geçilmez |
| chroot-ng OEM kernel'de tutarsız | Orta | proot fallback flag'ı; chroot-ng upstream'e device-matrix bug raporu + repro |
| swash kalite/performans yetersiz | Orta | freetype-rs + NDK build (Alacritty'nin desktop yolu) |
| Guest fidelity boşlukları (ek programlar) | Orta | pty_factory + upstream patch; fidelity listesi TODO'da büyür |
| targetSdk 28 stratejisi uzun vadede tıkanırsa (install floor) | Düşük-orta | Drosh seviyesinde Bionic userland + termux-exec geçiş yolu (ayrı strateji oturumu) |

## 10. Drosh entegrasyonu (sonraki faz — bu planın dışı)

Prototip kanıtlandıktan sonra, ayrı bir planla:

1. Çekirdeğin `:terminal-core` modülü olarak Drosh'a taşınması
2. Block engine'ın OSC 133 olaylarına bağlanması (Kotlin'de zaten var olan
   semantic katman devreye girer)
3. Agent shell tool'unun yeni session fabrikasına bağlanması
4. Drosh'un mevcut Termux motorunun geri planına çekilmesi / çıkarılması
5. DroshKeyboard entegrasyonu (prototipteki input encoder'ın olgun hâli)
6. targetSdk/dağıtım stratejisinin korunarak devamı

## 11. Çalışma kuralları

Bu repodaki tüm geliştirme ve ajan davranışı [`AGENTS.md`](AGENTS.md) ile
yönetilir. Özet: hiçbir şey uydurulmaz, iddialar doğrulanmadan yazılmaz,
kapılar geçilmeden ilerlenmez, dokümanlar güncel tutulur, gereksiz övgü
yapılmaz, gereken bilgi kullanıcıdan istenir.
