# Safra Kwik fork'u

Bu depo, Peter Doornbosch'un [Kwik](https://github.com/ptrd/kwik) kütüphanesinin Safra için bakım fork'udur. Temel `v0.11`, kaynak commit'i `4d5d40f540d70a1b50ea59651f9d36740e77f165`, özel sürüm `0.11.0-safra.1`.

Değişiklikler:

- İsteğe bağlı 64–256 KiB canlı alım penceresi; gerçek tüketim ve RTT ile hesaplama, sınırlı büyüme/küçülme ve boşta kalma kontrolü.
- Kaybolan bağlantı akış kredisi bildirimini güncel sınırla yeniden gönderme.
- Gönderici kuyruğundaki veri varken gönderimi durdurabilen yarışın düzeltilmesi.
- Ağdan bağımsız hale getirilen iki eski test ve saat sınırındaki kararsız testin düzeltilmesi; yeni regresyon testleri.

BBR, ayrı paket pacing katmanı, uygulama jitter tamponu ve dinamik PMTU keşfi bu fork'ta eklenmedi. TURN ChannelData değişiklikleri bu kütüphanede değil, [Safra](https://github.com/DeveloperKubilay/Safra/tree/codex/adaptive-receive-window) tarafındadır.

## Derleme

JDK 21 ile:

```sh
bash ./gradlew :kwik:test :kwik:jar :kwik:sourcesJar :kwik:generatePomFileForMavenPublication
python tools/prepare_release.py
```

`dist/` içinde kütüphane, kaynak JAR'ı, tam kaynak arşivi, Maven POM'u, lisanslar, kaynak commit bilgisi ve `SHA256SUMS` oluşur. Alım penceresi kendiliğinden tüm uygulamalarda açılmaz; Safra `enableReceiveWindowAutoTuning` çağrısıyla etkinleştirir. Kaynakların yayınlanan commit ile aynı olması gerekir.

## Doğrulama ve yayın

GitHub Actions, Linux ve Windows üzerinde çekirdek testlerini çalıştırır. Sürüm etiketi `v0.11.0-safra.1` biçimindedir; etiket ve kütüphane sürümü eşleşmezse yayın durur. İki işletim sistemi başarılı olmadan release işi çalışmaz. Sürüm dosyaları yeniden derlenmeden başarılı Linux işinin çıktısından yayımlanır. Actions sürümleri sabit commit'lere bağlanır.

İlk sürüm ön sürüm olarak yayımlanır. Yerel çekirdek doğrulaması 1070 başarılı ve önceden devre dışı bırakılmış 1 test içerir. Minecraft ve yapay ağ matrisi sonuçları Safra deposunda belgelenmiştir; her ağda aynı performans garanti edilmez.

Upstream lisansları ve telif bildirimleri korunur: [LGPL](LICENSE-LESSER.txt), [GPL metni](LICENSE.txt).
