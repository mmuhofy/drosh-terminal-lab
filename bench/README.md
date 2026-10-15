# bench — ölçüm panosu

İki ölçüm ailesi:

1. **Render/parse (Faz B):** frame p50/p99, jank sayımı, parse throughput
   (ekran içi overlay olarak gösterilir; "hissetmek" değil, "ölçmek").
2. **Guest runtime (Faz C):** aynı yüklerin Termux (proot referans) ile aynı
   cihazda karşılaştırması — `apt install`, `go build`, dosya ağırlıklı işler.

Sonuçlar [`../PLAN.md`](../PLAN.md)'ye işlenir. Ölçüm olmadan performans
iddiası yapılmaz (AGENTS.md kural 2).
