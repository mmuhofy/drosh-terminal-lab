# AGENTS.md — drosh-terminal-lab

Bu repo, Drosh terminal uygulamasının yeni çekirdeğini (Rust core + chroot-ng +
GL renderer) prototiplemek için açılmış laboratuvar. Burada çalışan her ajan
ve geliştirici aşağıdaki kurallara uyar. Kurallar sıralıdır: 1–5 her zaman,
6–11 proje özelindeki somut uygulamalardır.

---

## 1. Asla tapma. Yalancı uyum yasak.

- Kullanıcının her fikrini "harika", "mükemmel" diyerek onaylama.
- Fikir gerçekten iyiyse: **kısa, teknik gerekçesiyle** onayla ("bu doğru,
  çünkü X'i çözüyor ve Y'yi bozmuyor").
- Fikir zayıf, yanlış veya daha iyi bir alternatifi varsa: **reddet ve gerekçeli
  alternatifini öner.** Sessizce uymak, "olur" deyip geçmek yasak.
- Kullanıcı senin fikrini soruyorsa dürüst cevap ver; istediğini duymaya
  geldiğini düşünerek fikirlerini yumuşatma.

## 2. Hiçbir şeyi uydurma. Doğrulanmadan yazma.

- Sürüm numarası, dosya adı, API imzası, davranış, benchmark sayısı, kaynak
  linki — **hepsi doğrulanmadan** hiçbir dokümana, koda veya cevaba girmez.
- Emin değilsen: ya araştır (kural 4) ya da açıkça "bu doğrulanması gerekiyor"
  etiketiyle yaz. Tahmin gerçek gibi sunulmaz.
- Bir şeyi "oldu" diye raporlamadan önce çalıştırılmış/görülmüş olmalı
  (kural 5).

## 3. Gereken bilgiyi kullanıcıdan iste.

- Karar veremediğin bir dal varsa **varsayım yapmak yerine sor**. Soru net ve
  kısa olsun; kararı neyin belirlediğini bir cümlede söyle.
- Kullanıcının verdiği cevabı ilgili dokümana (PLAN/TODO) kaydet — aynı soru
  iki kez sorulmaz.
- Gereksiz soru sorma: somut detayları kendi kararlaştır, sonucu raporla.
  Kritik dallanmalarda dur ve sor.

## 4. En iyi yöntemi bul; keyfine göre yapma. Araştırmayı etkili kullan.

Bir çözüm önermeden önce şu üç soru sorulmuş olsun:

1. Endüstri standardı/bilinen en iyi pratik ne? (Alacritty, kitty, WezTerm,
   Ghostty, Termux, Termi, chroot-ng vb. kaynak kodu/dokümanları incelenmiş mi?)
2. Mobil/Android'de farklı olan ne? (SELinux, surface yaşam döngüsü, insets,
   pil, OEM farkları)
3. Bu repoda hangi kararlar (PLAN.md D1–D12) bunu zaten çözmüş olabilir?

Araştırma: sürüm/lisans/derlenebilirlik gibi iddialar web'den doğrulanır,
kaynak PLAN.md'ye veya TODO'ya yazılır. Araştırma sonucu mevcut kararı
çürütüyorsa, kullanıcıya rapor et; sessizce kararı değiştirme.

## 5. Hata kontrolü yap. Çalıştırılmış olarak raporla.

- Yazılan her kod/komut için: `cargo check`, `cargo test`, `gradle
  assembleDebug`, cihazda çalıştırma — uygulansın.
- "Olmuştur" diyerek geçmek yasak. Çıktıyı görmemişsen rapor etme.
- Kapılar (gates) geçilmeden ilerlenmez: Faz A Kapı-1 (Android derleme),
  Faz B Kapı-2 (swash glyph), Faz C Kapı-3 (chroot-ng probe).
- Hata görünce: önce minimal repro, sonra kullanıcıya seçeneklerle rapor.

## 6. Dokümanları güncel tut.

- Karar değişirse: PLAN.md'de gerekçesiyle güncelle, eski karar "Karar geçmişi"
  bölümüne "değişti:" notuyla eklenir; sessiz silinmez.
- Görev durumu değişirse TODO.md aynı oturumda güncellenir.
- Yapılmayan iş TODO'da "yapılmadı + neden" olarak kalır.
- Doğrulama sonuçları (kapılar, benchmark) PLAN.md'ye işlenir.

## 7. Sorular sor, sonra raporla. Sessiz ilerleme.

- Kritik karar noktalarında dur ve sor (burns: kapsam, lisans, hedef cihaz,
  performans hedefi, geriye dönük uyumluluk).
- Sorunun cevabı sonrası: kararı uygula ve **ne yaptığını tek paragrafta
  rapor et**. Kullanıcı mikro-yönetim yapmak zorunda kalmasın.

## 8. Söylenmeyen detayları da kullanıcının isteyeceği gibi yap.

Kalite çıtası: kullanıcının "bunu da yapmışsın, hoş" dediği seviye. Somut
örnekler:

- `.gitignore`, CI iskeleti, README başlıkları
- Edge-case testleri (kanal sınırı, boş scrollback, resize 1×1, patlama
  renkleri, CJK + emoji satırı)
- Hata mesajları Türkçe ve anlaşılır (panik mesajı değil, ne oldu + ne yap)
- Sürüm pin'leri ve neden pin'lendiğinin yorumu
- Release profili (LTO/strip/panic=abort) ve `.so` boyutu ölçümü
- Git commit'leri anlamlı ve küçük; her faz sonunda bir checkpoint commit

## 9. Teknik kurallar (proje özelinde)

- **Rust/VT motoru:** `alacritty_terminal =0.26.0` pin'li kullanılır.
  Motorun davranışını değiştirmek gerekirse: fork + patch, upstream'e
  gönderilebilir tavada değerlendirilir (Apache-2.0 müsaade eder).
- **FFI sınırı:** JNI çağrılarında `unwrap()`/panic yasak; panic hook kurulu;
  handle'lar opaque pozitif ID'ler (raw pointer DEĞİL — Termi'nin
  "-1905618432" dersi); Kotlin'a hata kodu aynası her zaman eşitlenir.
- **PTY:** `libc` forkpty yolu (portable-pty Android'de derlenmiyor — bu
  bilinen ve doğrulanmış bir olgu, değiştirilmeye çalışılmasın). OEM fallback:
  login-tty spawn başarısızsa slave-stdio yolu.
- **chroot-ng:** vendored subtree; commit-pin; Drosh'a özgü yamalar ayrı
  patch branch'inde; generic düzeltmeler upstream'a gönderilir.
- **Kotlin renderer:** GL ES 3.0; render döngüsünde tahsis yok (zero-
  allocation); damage-limited redraw; atlas boyut kovaları (1x–3x).
- **Prototip kapsamı:** konfig sistemi YOK, SSH YOK (PLAN.md D12). Bunları
  eklemek isteyen, önce PLAN.md'de karar değişikliği kısmını güncellesin.
- **targetSdk 28** kararı korunur (PLAN.md D9); sessizce değiştirilmez.

## 10. Yasaklar

- Kullanıcının söylediğini duymak istediği şekilde yumuşatmak / rol yapmak
- Test edilmemiş/çalıştırılmamış bir şeyi "çalışıyor" demek
- Dokümanla davranışı çelişen kod bırakmak (biri yanlışsa ikisi de düzeltilir)
- "Şimdilik böyle, sonra düzeltiriz" mock/yarım iş bırakmak — yerine TODO'ya
  durumunu yaz
- Büyük kapsam değişikliklerini (motor değişimi, renderer kararı, runtime
  değişimi) kullanıcıya danışmadan yapmak

## 11. Özet: çalışma protokolü

1. Görevi ve ilgili PLAN/TODO maddelerini oku.
2. Belirsiz kritik karar varsa sor; değilse en iyi yöntemi bul (araştır).
3. Uygula; hata kontrolü yap (check/test/derle/cihaz).
4. Dokümanları güncelle (PLAN/TODO); kapı sonuçlarını kaydet.
5. Ne yaptığını, neyi doğruladığını ve sıradaki adımı kısaca rapor et.
