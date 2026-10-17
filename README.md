# Düşme Algılama

Telefonun **arka kamerasıyla** ortamı izleyen, **yapay zeka** ile kişinin düştüğünü algılayan ve gerekirse belirlenen numarayı **otomatik arayan** Android uygulaması.

## Nasıl çalışır?

```
Düşme algılandı
   │  (4 sn kalkmazsa)
   ▼
"İyi misiniz?"  ── sesli soru, mikrofon dinlenir
   │
   ├─ "İyiyim" / "Tamam" / "Evet"          → alarm iptal, izlemeye devam
   ├─ "Yardım" / "İmdat" / "İyi değilim"   → hemen arama
   ├─ kişi ayağa kalkar                     → alarm iptal
   └─ 7 sn içinde yanıt yok / kalkmadı      → acil numara aranır (hoparlör açılmaya çalışılır)
```

* **Yapay zeka:** Google ML Kit *Pose Detection* her karede insan iskeletinin 33 noktasını bulur. Model uygulamanın içindedir, **internet gerekmez**, görüntü hiçbir yere gönderilmez.
* **Düşme kararı:** Omuz ortası ile kalça ortası arasındaki gövde çizgisinin dikeyle yaptığı açı izlenir. Kişi **2 saniye içinde dik duruştan yatay konuma** geçer ve kalçası aşağı inerse düşme sayılır. Yavaşça uzanmak genelde alarm vermez.
* **Kalkma:** Kişi 0,8 sn boyunca yeniden dik görünürse alarm iptal olur.
* Sesli yanıt için telefonun konuşma tanıma servisi (Türkçe) kullanılır.

## Kurulum

1. Repo'da **Actions → APK Derle** → son başarılı çalışmanın altındaki **Dusme-Algilama-apk** dosyasını indir.
2. Zip'i aç, `app-debug.apk`'yı telefona kur (bilinmeyen kaynaklara izin vermek gerekebilir).
3. İlk açılışta **Kamera, Mikrofon ve Telefon** izinlerini ver.
4. **AYARLAR**'dan acil numarayı gir, **KAYDET**. Süreler de buradan değiştirilebilir (varsayılan 4 sn + 7 sn).
5. Telefonu odayı gören bir yere sabitle (dik ya da yatay olabilir). **TEST: DÜŞME** ile akışı deneyebilirsin.

## Önemli notlar

* Uygulama **ekran açıkken ve ön plandayken** çalışır (ekran kapanmaz). Telefon şarjda olmalı.
* Kişinin **tüm vücudu kadraja** girmeli; 2–4 m uzaklık en iyisidir. Çok karanlık ortamda algılama zayıflar.
* Bu bir **eğitim/araştırma prototipidir**, tıbbi cihaz değildir. Yanlış alarm ve kaçırılan düşme olabilir. Testlerde 112 yerine **kendi numaranızı** kullanın.

## Proje yapısı

| Dosya | Görevi |
|---|---|
| `MainActivity.java` | Kamera (CameraX), yapay zeka, ekran |
| `FallDetector.java` | İskeletten duruş sınıflandırma ve düşme kararı |
| `EmergencyFlow.java` | Sayaçlar, "İyi misiniz?" sorusu, konuşma tanıma, arama |
| `PoseOverlayView.java` | İskeleti kamera görüntüsünün üstüne çizer |
| `AppPrefs.java` | Numara ve süre ayarları |
