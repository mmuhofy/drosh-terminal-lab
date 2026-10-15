# android/guest — rootfs bootstrap + chroot-ng launch

The Android-side work needed to run an Ubuntu 24.04 userspace under chroot-ng.

**Components (Phase C):**

- Rootfs download (HTTPS) + integrity verification + extraction
- chroot-ng launch: argv mapping (`-u`, `-w`, `-b`, `-E`) — together with
  `pty_factory.rs` on the Rust side
- Foreground-service notifications for long-running sessions (Android rules)

The bootstrap flow is adapted from Drosh's existing implementation.
