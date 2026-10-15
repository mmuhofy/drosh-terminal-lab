# android/app — ince kabuk

Terminali çizen yer **değil**; onu yaşatan platform katmanı:

- `MainActivity` — oturum ekranı, izinler, lifecycle
- `SurfaceView` + EGL context sahibi (Rust renderer'ın çizeceği yüzey)
- Oturum yönetimi (çoklu tab/oturum)
- JNI binding (`libdrosh-core.so`)

Faz A'da oluşturulur. Renderer: [`../renderer`](../renderer), girdi:
[`../input`](../input).
