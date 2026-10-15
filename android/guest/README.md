# android/guest — rootfs bootstrap + chroot-ng launch

Ubuntu 24.04 userspace'ini chroot-ng altında çalıştırmak için gereken Android
tarafı işler.

**Bileşenler (Faz C):**
- Rootfs indirme (HTTPS) + bütünlük doğrulama + extract
- chroot-ng launch: argv eşlemesi (`-u`, `-w`, `-b`, `-E`) — Rust tarafındaki
  `pty_factory.rs` ile birlikte
- Foreground session'lar için bildirim (Android'in oyun kuralları)

Drosh'taki bootstrap akışı burada adapte edilecek.
