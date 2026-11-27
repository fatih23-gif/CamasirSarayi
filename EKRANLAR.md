# Ekran ve gereksinim eşlemesi

Tüm satırlar `activity_main.xml` ve `MainActivity` üzerinden açılır. Ana sayfalarda ayrıca `view_home.xml`, kart listelerinde `item_row.xml` kullanılır. Hesap ekranları ve iki profil `AccountScreens.kt`, öğrenci ekranları ile iki ana sayfa `StudentScreens.kt`, yönetim işlemleri `AdminScreens.kt` içinde. Son sütun doğrulanması gereken senaryoları gösterir.

| Ekran | Rol | Görev | Veri | Gezinme hedefi | Kontrol senaryosu |
|---|---|---|---|---|---|
| 01 Karşılama | Öğrenci / Yönetici | Oturum aç ve kayıt seçenekleri | Auth | welcome | Oturumla tekrar açma |
| 02 Giriş | Öğrenci | E-posta ve şifre | Auth | login | Hatalı şifre ve sıfırlama |
| 03 Kayıt | Öğrenci | Hesap oluşturma ve yurt seçimine geçiş | Auth | register → dorms | Eşleşmeyen şifre |
| 04 Yurt seçimi | Öğrenci | Aktif yurtları arama | dorms özeti | dorms | Sonuçsuz arama |
| 05 Yurt doğrulama | Öğrenci | Kodu doğrula ve onay bekle | members | enroll → pending | Yanlış kod, ret ve onay |
| 06 Ana sayfa | Öğrenci | Durumlar ve kısa liste | machines, queue, history | home | Boş liste, süresi dolan makine |
| 07 Makine listesi | Öğrenci | Arama, salon ve durum filtresi | machines, rooms | machines | Sonuçsuz filtre |
| 08 Makine detayı | Öğrenci | Durum, konum ve tahmini süre | machines | detail | Çalışıyor, arızalı, bekliyor |
| 09 Boş makine | Öğrenci | Uygunsa kullanım hakkı iste | machines, queue | detail | Son makineye iki istek |
| 10 Sıraya katıl | Öğrenci | Ortak salon kuyruğuna katıl | queue | queue | İkinci katılım reddi |
| 11 Dijital sıra | Öğrenci | Konum ve ayrılma | queue | queue | FIFO, sıradan ayrılma |
| 12 Sıra hakkı | Öğrenci | 5 dakikayı takip et | machines, inbox | queue | Süre sınırında başlatma ve vazgeçme |
| 13 Yıkama başlat | Öğrenci | Saat ve dakika seçimi | machines | duration | 100 dk kabul, 241 dk ret |
| 14 Aktif yıkama | Öğrenci | Geri sayım ve uzatma | machines | wash → duration | Başka kullanıcıdan güncelleme reddi |
| 15 Yıkama bitti | Öğrenci | Boşaltma onayı | machines, history | wash | Onaydan önce boş görünmez |
| 16 Geçmiş | Öğrenci | Kendi tamamlanan kayıtları | history | history | Boş geçmiş, başka kullanıcının geçmişi |
| 17 Arıza bildir | Öğrenci | Makine, tür ve açıklama | faults | report | Kısa açıklama reddi |
| 18 Bildirimler | Öğrenci | Gelen kutusu ve okundu durumu | inbox | notifications | Başka kullanıcının bildirimi |
| 19 Profil | Öğrenci | Üyelik, görünüm ve hesap | Auth, members | profile | Tema kalıcılığı, çıkış |
| 20 Çevrimdışı | Öğrenci | Son alınan makine verisini göster | Son ekran verisi | machines + uyarı | Bağlantı kesilince yazma yok |
| 21 Yönetici girişi | Yönetici | Yetkili hesabı doğrula | Auth, members | adminLogin | Öğrenci hesabı reddedilir |
| 22 Yönetici ana sayfa | Yönetici | Özet ve yönetim kısayolları | machines, queue, faults, members, history | home | Özet verisi değişince kartlar güncellenir |
| 23 Makine yönetimi | Yönetici | Listele ve filtrele | machines, rooms | machines | Farklı yurt verisine erişim reddi |
| 24 Makine ekle | Yönetici | Ad, salon, numara ve durum | machines, audit | machineForm | Aynı salonda çift numara reddi |
| 25 Makine düzenle | Yönetici | Durum, konum ve ad | machines, audit | machineForm | Aktif yıkamayı doğrudan boş yapma reddi |
| 26 Sıra yönetimi | Yönetici | Sırayı ve aktif hakları görüntüle | queue, machines | queue | Yönetici sıra ve hakları değiştiremez |
| 27 Arıza bildirimleri | Yönetici | Filtreleme ve çözüm | faults, audit | faults | Durum değişikliğinde öğrenci bildirimi |
| 28 Üye yönetimi | Yönetici | Onay, ret veya askıya alma | members, audit | members | Öğrenci rol yükseltemez |
| 29 Yurt ayarları | Yönetici | Yurt adı, salon ve yetkili listesi | dorms, rooms, members | settings | Yönetici dışındaki talep reddedilir |
| 30 Raporlar | Yönetici | Dönemsel yıkama ve arıza özeti | history, faults | reports | Boş veya dolu dönemde sayaç |
| 31 Yönetici profili | Yönetici | Hesap ve yurt ayarları | Auth, members | profile | Çıkış ve profil seçenekleri |