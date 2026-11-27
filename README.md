

\# Çamaşır Sarayı



\## Yurt Çamaşırhanesi Dijital Sıra ve Makine Takip Uygulaması



\## Proje Hakkında



Çamaşır Sarayı, öğrenci yurtlarında yaşanan çamaşır makinesi yoğunluğunu, sıra karışıklığını ve zaman kaybını azaltmak amacıyla geliştirilen bir Android mobil uygulamasıdır.



\## Projenin Amacı



Öğrencilerin çamaşır makinelerinin durumlarını mobil cihazlarından takip edebilmelerini, dijital sıraya katılabilmelerini ve çamaşırhane kullanım süreçlerini daha kolay yönetebilmelerini sağlamaktır.



\## Hedef Kullanıcılar



\- Yurt öğrencileri

\- Yurt yöneticileri

\- Çamaşırhane personelleri



\## Uygulama Özellikleri



\- Öğrenci kayıt ve giriş sistemi

\- E-posta doğrulama

\- KYK yurt seçimi

\- Yurt katılım kodu

\- Yönetici tarafından öğrenci onayı

\- Yönetici ve personel erişim sistemi

\- Çamaşır makinesi durum takibi

\- Dijital sıra ve rezervasyon sistemi

\- Yıkama süresi ve geri sayım takibi

\- Bildirim sistemi

\- Yönetici paneli

\- Makine ve çamaşırhane yönetimi

\- Arıza bildirim sistemi

\- Kullanım geçmişi



\## Kullanılan Teknolojiler



\- Kotlin

\- Android Studio

\- XML ve Android Views

\- Material Components

\- Firebase Authentication

\- Cloud Firestore

\- Cloudflare Workers – REST API

\- Node.js

\- Gradle

\- Git ve GitHub

\- Figma



\## Proje Geliştirme Süreci



\### 1. Hafta – Problem Tanımı ve Proje Planlaması



\- \[x] Problem ve ihtiyaç analizi

\- \[x] Hedef kullanıcıların belirlenmesi

\- \[x] Uygulama fikrinin oluşturulması

\- \[x] İlk ekran tasarımlarının hazırlanması



\### 2. Hafta – UI/UX Tasarımı ve Teknik Mimari



\- \[x] Figma ekran tasarımlarının hazırlanması

\- \[x] Rakip uygulamaların incelenmesi

\- \[x] Teknik mimarinin belirlenmesi

\- \[x] Veri modelinin oluşturulması

\- \[x] Android proje iskeletinin hazırlanması

\- \[x] Karşılama ekranı

\- \[x] Öğrenci giriş ve kayıt ekranları

\- \[x] Yönetici giriş, başvuru ve personel davet ekranları



\### 3. Hafta – Çekirdek Özellik ve İlk İmzalı Sürüm



\- \[x] Öğrenci kayıt ve giriş sistemi

\- \[x] E-posta doğrulama

\- \[x] Yurt seçimi ve katılım kodu

\- \[x] Yönetici tarafından öğrenci onayı

\- \[x] Öğrenci makine listesi

\- \[x] Mevcut uygulama kaynak kodlarının Git deposuna aktarılması

\- \[x] Tarih ve saat biçimlendirme kodunun güncellenmesi

\- \[x] Android debug derlemesinin doğrulanması

\- \[x] İş kurallarına yönelik otomatik testlerin çalıştırılması

\- \[ ] İlk imzalı AAB dosyasının oluşturulması

\- \[ ] Google Play Console iç test kanalına yükleme



Kişisel kısıt, öğretim elemanı tarafından belirlendikten sonra uygulanacaktır.



\## Otomatik Test Sonuçları



Test tarihi: 27 Kasım 2026



| Test Alanı | Sonuç |

|---|---|

| Makine rezervasyonu | Başarılı |

| FIFO sıra yönetimi | Başarılı |

| Yıkama süresi yönetimi | Başarılı |

| Salon bazlı makine ataması | Başarılı |

| Kullanıcı yetki kontrolü | Başarılı |

| Yurt katılım kodu | Başarılı |

| Aktif yıkama kontrolü | Başarılı |

| Yönetici işlem sınırları | Başarılı |

| Çamaşırhane oluşturma | Başarılı |

| Makine taşıma kontrolü | Başarılı |

| Tekrarlanan arıza bildirimi kontrolü | Başarılı |

| Belirli makine rezervasyonu | Başarılı |



\- Toplam test: 12

\- Başarılı test: 12

\- Başarısız test: 0

\- Test başarı oranı: %100



Bu sonuçlar mevcut otomatik iş kuralı testlerini kapsamaktadır.



\### Test Komutu



```bash

node --test functions/laundry.test.js

```



\### Android Derleme Komutu



```bash

./gradlew assembleDebug

```



Windows:



```powershell

.\\gradlew.bat assembleDebug

```



Son debug derleme sonucu: BUILD SUCCESSFUL



\## Proje Güvenliği



İmza anahtarları, yerel yapılandırma dosyaları ve hassas kimlik bilgileri Git deposuna eklenmemelidir.



Firebase yapılandırma dosyası ve kişisel veri içerebilecek seed dosyası depoya dahil edilmemiştir. Canlı servislerin kullanımı için gerekli yapılandırmalar ayrıca yapılmalıdır.



\## Projenin Mevcut Durumu



Uygulamanın temel öğrenci ve yönetici işlemleri geliştirilmiştir. Dijital sıra, makine takibi ve çamaşırhane yönetimi özellikleri bulunmaktadır.



İlk imzalı sürümün hazırlanması ve Google Play Console iç test işlemleri sonraki adımlardır.



\## Akademik Bilgi



Bu proje, Mobil Uygulama Geliştirme dersi kapsamında final projesi olarak geliştirilmektedir.



