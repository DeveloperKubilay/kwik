# Kwik bakım sürümü

Temel: [ptrd/kwik v0.12.1](https://github.com/ptrd/kwik/tree/v0.12.1), kaynak `d4098009a281f70df43db4736e1976521028ac0a`. Özel sürüm `0.12.1-maintenance.1`; resmî bir Kwik 0.12.2 yayını değildir.

## Korunan katkılar

- İsteğe bağlı, sınırlandırılmış canlı alım penceresi: [API ve hesaplama](docs/receive-window-auto-tuning.md).
- Kaybolan MAX_DATA bildirimini güncel bağlantı kredisiyle yeniden gönderme.
- Çok hızlı ACK işlenmeden önce gönderilen paketi kurtarma takibine kaydetme.
- Ağdan bağımsız DNS testleri ve zamanlaması belirli NewReno testi; test dışlanmaz.
- Sürüm kaynağı, lisanslar, kaynak arşivi ve SHA-256 ile Windows/Linux yayın doğrulaması.

Gönderim kuyruğundaki yarış ve en az 1 ms kayıp eşiği v0.12.1 içinde zaten düzeltilmiştir. Bu iki üretim düzeltmesi yeniden uygulanmadı. TLS bağımlılığı agent15 4.0'dır. Pacing, BBR, PMTU keşfi ve uygulama jitter tamponu eklenmedi. QUIC ağ göçü özelliği varsayılan kapalı bırakılır.

## Derleme ve yayın

JDK 21 ile `./gradlew :kwik:test :kwik:jar :kwik:sourcesJar :kwik:generatePomFileForMavenPublication` ardından `python tools/prepare_release.py` çalıştırılır. `dist/` içine kaynak commit bilgisi, kütüphane ve kaynak JAR'ı, tam kaynak arşivi, POM, lisanslar ve SHA256SUMS hazırlanır.

Etiket `v0.12.1-maintenance.1` biçimindedir. Kaynak commit'i, paket sürümü ve etiket eşleşmelidir. Yayın Linux ve Windows kontrolü başarılı olduktan sonra ön sürüm olarak oluşturulur. Upstream lisansları ve telif bildirimleri korunur. Safra entegrasyonu ayrıca değişen ağ koşulları ve sertifika sabitleme testlerinden geçirilir; gerçek kullanıcı ağlarında aynı performans garanti edilmez.
