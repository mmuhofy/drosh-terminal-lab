# chroot-ng (vendored)

[sylirre/chroot-ng](https://github.com/sylirre/fake-chroot-ng) kaynağının
**commit-pin'lenmiş** kopyası — Apache-2.0. proot'un yerini alan guest runtime.

- Pin: Faz C'nde sabitlenecek (henüz değil — önce upstream ile çalışıldığını
  doğrulayacağız).
- Drosh'a özgü yamalar ayrı bir patch branch'inde tutulur; generic düzeltmeler
  upstream'a gönderilir.
- CI'da aarch64 cross-build edilip APK'ya konur. Bu da ne yapar: chroot+bind
  emülasyonunu ptrace kullanmadan, seccomp RET_TRAP + kendi userland ELF
  loader'ı ile yapar; Android SELinux engellerini (pty ioctl, /proc stat,
  rtnetlink, SysV IPC) emüle eder.

**Not:** Bu 41K satır C kodu bizim yazdığımız şey değil; bu klasör yalnızca
derleme/pin yönetimi içindir.
