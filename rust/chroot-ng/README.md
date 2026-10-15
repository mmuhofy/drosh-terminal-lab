# chroot-ng (vendored)

A **commit-pinned** copy of [sylirre/chroot-ng](https://github.com/sylirre/fake-chroot-ng)
(Apache-2.0). The guest runtime that replaces proot.

- Pin: to be fixed in Phase C (not yet — first we validate it works upstream).
- Drosh-specific patches live in a separate patch branch; generic fixes are
  sent upstream.
- CI cross-builds it for aarch64 and packages it into the APK.
- What it does: chroot + bind emulation **without ptrace**, via seccomp
  RET_TRAP + its own userland ELF loader; emulates Android SELinux barriers
  (pty ioctls, /proc stat, rtnetlink, SysV IPC).

**Note:** these 41K lines of C are not our code; this directory only holds
the pin/build management.
