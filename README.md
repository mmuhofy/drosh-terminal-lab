# Drosh Terminal Lab

Drosh terminal uygulamasının yeni terminal çekirdeğini **Drosh'tan bağımsız**,
ayrı ve minimal bir prototip uygulamada kanıtlamak için açılmış laboratuvar repo'su.

Amaç: Rust core (alacritty_terminal 0.26.0) + chroot-ng guest runtime +
Kotlin GL ES renderer yığınının Android'de gerçekten çalıştığını ve hızlı
olduğunu sayılarla göstermek. Kanıtlanınca bu çekirdek Drosh'a modül olarak
taşınacak.

## Durum

🚧 **Planlama tamam, implementasyon başlamadı.** Dokümanlar:

| Doküman | İçerik |
|---|---|
| [`PLAN.md`](PLAN.md) | Kararlar (D1–D12), mimari, dosya envanteri, fazlar, riskler |
| [`TODO.md`](TODO.md) | Faz bazlı görev listesi, kapılar (gates), kabul kriterleri |
| [`AGENTS.md`](AGENTS.md) | Bu repoda çalışan ajanların ve geliştiricilerin kuralları |

## Klasör yapısı

```
drosh-terminal-lab/
├── rust/
│   ├── chroot-ng/        vendored subtree (Apache-2.0, commit-pin)
│   └── core/             cdylib "libdrosh-core.so" — Rust çekirdek
├── android/
│   ├── app/              ince kabuk: Activity, SurfaceView/EGL, oturum yönetimi
│   ├── renderer/         GL ES 3.0 terminal renderer (Kotlin)
│   ├── input/            escape encoder + jestler (Kotlin)
│   └── guest/            Ubuntu rootfs bootstrap + chroot-ng launch
└── bench/                frame/parse + guest benchmark panosu
```

## Prototip ne DEĞİLDir

- Drosh'un kendisi değildir — block engine, agent, SSH host yönetimi, tema
  mağazası, PIN, çok dillilik **kapsam dışıdır**.
- Konfig sistemi yoktur: sabit makul varsayılanlarla çalışır (gerçek Drosh'ta
  TOML config sistemi var ve orada kalır).
- SSH kapsam dışıdır (prototipte ne SSHJ bağlantısı ne de FdPair trait'i).

## Lisans

Drosh (GPL-3.0) ile aynı lisans altında taşınır. Bağımlılıklar:
alacritty_terminal ve chroot-ng Apache-2.0.
