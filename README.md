# JaWa: Lightweight Java WhatsApp Library

[![build](https://github.com/jochris/JaWa/actions/workflows/build.yml/badge.svg)](https://github.com/jochris/JaWa/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![java](https://img.shields.io/badge/java-compatible-orange)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.jochris/JaWa)](https://central.sonatype.com/artifact/io.github.jochris/JaWa)

> Lightweight **Java** library for WhatsApp Web Multi-Device protocol.
> Connects backend services, bots, and applications to WhatsApp Web features (Signal E2EE encryption, binary node codecs, QR & Phone pairing, CTA buttons, carousels, lists, and group management).

---

## 📦 Installation

Import `JaWa` as a dependency into your Java project:

### Maven

```xml
<dependency>
    <groupId>io.github.jochris</groupId>
    <artifactId>JaWa</artifactId>
    <version>0.0.4</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.jochris:JaWa:0.0.4'
```

---

## 🏗️ Architecture Layout

`JaWa` is structured into clean, domain-driven packages:

```text
id.jawa
├── client/                     Primary Client Facade & Modular Sub-Services
├── protocol/                   Wire Protocol, Codecs & Cryptography
│   ├── connection/             WebSocket, Noise XX Handshake & Transport
│   ├── codec/                  Binary XML Node Encoder / Decoder
│   └── crypto/                 Cryptography & Byte Utilities
├── domain/                     Core Domain Models & Store Interfaces
│   ├── model/                  JID, Auth Credentials, Receipt & Protocol Models
│   └── store/                  File & In-Memory Persistence Stores
└── feature/                    Feature Subsystems
    ├── pairing/                QR & Phone-Code Pairing Handshake
    ├── messaging/              E2EE Messaging, Group Actions & USync Query
    ├── media/                  Media Encrypt/Decrypt & Upload/Download
    ├── appstate/               Multi-Device AppState Sync & LtHash
    └── signal/                 E2EE Session Bootstrap & PreKey Management
```

---

## 🧩 Modular Sub-Services

`JaWaClient` organizes functionality into clean, domain-scoped sub-services for clearer and more discoverable API access:

```java
client.messages().sendText(chatJid, "Hello from JaWa!");
client.messages().sendReaction(chatJid, msgId, null, false, "👍");
String nextId = client.messages().generateMessageId();

client.groups().createGroup("Developers Chat", List.of("628123456789"));
client.groups().getGroupInviteLink(groupJid);
client.groups().joinGroupWithLink("https://chat.whatsapp.com/Code123");
client.groups().setGroupLocked(groupJid, true);
client.groups().setGroupAnnounce(groupJid, true);
client.groups().setDisappearingTimer(groupJid, 604800);

client.media().sendImage(chatJid, imageBytes, "image/jpeg", "Check this out!");

client.newsletters().sendText(newsletterJid, "New update published!");

client.profile().updateStatus("Building with JaWa ☕");

client.chats().subscribePresence(targetJid);
client.chats().sendChatPresence(chatJid, ChatPresence.COMPOSING);
client.chats().setDisappearingTimer(chatJid, 86400);
```

> [!NOTE]
> All legacy top-level methods on `JaWaClient` (e.g. `client.sendText(...)`, `client.createGroup(...)`) remain fully supported for 100% backward compatibility.

---

## 🎛️ Client Configuration

`JaWaClient` provides fluent, chainable configuration options to customize bot and connection behavior:

```java
JaWaClient client = new JaWaClient(store)
    .autoReconnect(true)
    .autoReadReceipt(true)
    .autoComposingPresence(true)
    .autoPreKeyUpload(true);
```

### Configuration Options Reference

| Method | Default | Description |
| :--- | :--- | :--- |
| `.autoReconnect(boolean)` | `true` | Automatically reconnects with exponential backoff on transient socket disconnects. |
| `.autoReadReceipt(boolean)` | `false` | Sends an automatic `receipt type=read` (blue ticks) to the sender upon receiving inbound messages. |
| `.autoComposingPresence(boolean)` | `false` | Sends a `composing` presence status (typing...) prior to dispatching outgoing messages. |
| `.autoPreKeyUpload(boolean)` | `true` | Automatically uploads 30 fresh Signal One-Time PreKeys when server inventory falls below 5. |

---

<a id="daftar-panduan"></a>
## 📖 Panduan Cepat & Indeks Fitur

Gunakan navigasi interaktif di bawah ini untuk mempelajari alur kerja dasar dan langsung melompat ke contoh kode serta penjelasan teknis setiap fitur:

### 🔄 Alur Kerja Dasar (Lifecycle Guide)

1. **Auth & Signal Storage**: Siapkan penyimpanan kredensial menggunakan `FileAuthStore` (file `.session`) atau `SqliteAuthStore` (database SQLite).
2. **Inisialisasi Client**: Buat objek `JaWaClient` dengan meneruskan auth store dan path signal store, lalu atur konfigurasi yang diinginkan.
3. **Pemasangan Sesi (Pairing)**: Hubungkan bot ke WhatsApp pada ponsel utama via kode pairing 8 karakter (`requestPairingCode`) atau pemindaian QR Terminal.
4. **Listener Event**: Daftarkan listener menggunakan `client.listener(...)` untuk menangani pesan masuk (`onMessage`), pembaruan QR (`onQr`), koneksi (`onConnected`), dan event lainnya.
5. **Akses Sub-Service**: Kirim pesan teks, tombol interaktif, stiker, media, serta kelola grup dan privasi menggunakan sub-services terstruktur (`client.messages()`, `client.groups()`, `client.media()`, `client.privacy()`, `client.chats()`, `client.newsletters()`, `client.profile()`).

---

### 📑 Daftar Indeks Fitur (Klik untuk Menuju Kode)

#### 🔐 1. Autentikasi & Penyimpanan Sesi
| No | Fitur & Topik | Deskripsi Singkat | Navigasi Langsung |
| :--- | :--- | :--- | :--- |
| 1 | **Phone Pairing Code** | Pasangkan bot menggunakan kode 8 digit ke nomor ponsel | [Buka Panduan & Kode](#phone-pairing) |
| 2 | **Terminal QR Code Pairing** | Pindai kode QR ASCII langsung pada terminal console | [Buka Panduan & Kode](#qr-pairing) |
| 3 | **SQLite Database Session Storage** | Simpan sesi, keys, dan kredensial di database SQLite | [Buka Panduan & Kode](#sqlite-store) |
| 4 | **Logout & Session Unlinking** | Putus tautan sesi companion secara aman dan bersihkan data | [Buka Panduan & Kode](#logout-session) |

#### 💬 2. Pesan & Fitur Interaktif
| No | Fitur & Topik | Deskripsi Singkat | Navigasi Langsung |
| :--- | :--- | :--- | :--- |
| 5 | **LID to Phone Number Resolver** | Resolusi sender JID format LID ke nomor telepon asli penerima | [Buka Panduan & Kode](#lid-pn) |
| 6 | **Interactive CTA Buttons & Lists** | Kirim tombol Call To Action (URL, Copy, Quick Reply) & Dropdown | [Buka Panduan & Kode](#buttons-list) |
| 7 | **Carousel Cards Slider** | Slider kartu horizontal interaktif dengan gambar dan tombol aksi | [Buka Panduan & Kode](#carousel) |
| 8 | **Message Starring & Chat Cleanup** | Bintang pesan penting, hapus perorangan, atau bersihkan riwayat chat | [Buka Panduan & Kode](#star-cleanup) |
| 9 | **Offline Sync Message Filtering** | Abaikan pesan kedaluwarsa saat bot baru online kembali | [Buka Panduan & Kode](#offline-sync) |
| 10 | **Companion Privacy Receipts** | Tandai dibaca secara lokal (read-self) tanpa memicu centang biru | [Buka Panduan & Kode](#privacy-receipts) |

#### 📸 3. Media, Dokumen & Stiker
| No | Fitur & Topik | Deskripsi Singkat | Navigasi Langsung |
| :--- | :--- | :--- | :--- |
| 11 | **Automatic Media Downloader** | Unduh dan dekripsi foto, video, audio PTT, dokumen, dan view-once | [Buka Panduan & Kode](#media-downloader) |
| 12 | **WebP Stickers (Statis & Animasi)** | Kirim stiker WebP statis maupun animasi stiker bergerak | [Buka Panduan & Kode](#stickers) |
| 13 | **Media Deletion from WhatsApp CDN** | Hapus file media yang telah terunggah dari server MMS CDN | [Buka Panduan & Kode](#media-delete) |
| 14 | **Media Retry Requests (404/410)** | Minta ponsel utama mengunggah ulang media expired dan dekripsi hasilnya | [Buka Panduan & Kode](#media-retry) |

#### 👥 4. Grup & Komunitas
| No | Fitur & Topik | Deskripsi Singkat | Navigasi Langsung |
| :--- | :--- | :--- | :--- |
| 15 | **Advanced Group Administration** | Buat grup, kelola peserta/admin, kunci grup, dan link invite | [Buka Panduan & Kode](#group-admin) |
| 16 | **Group Invite Message Acceptance** | Terima dan masuki grup dari pesan undangan privat | [Buka Panduan & Kode](#group-invite) |
| 17 | **Community Management** | Kelola Komunitas WhatsApp, inspeksi subgroup, link/unlink grup | [Buka Panduan & Kode](#community) |

#### 📢 5. Saluran / Channel (Newsletter)
| No | Fitur & Topik | Deskripsi Singkat | Navigasi Langsung |
| :--- | :--- | :--- | :--- |
| 18 | **Newsletter & Channel Management** | Follow, mute, reaksi emoji, live updates, ToS notice, dan riwayat pesan | [Buka Panduan & Kode](#newsletter-channel) |

#### 🔒 6. Pengaturan Privasi & Keamanan Akun
| No | Fitur & Topik | Deskripsi Singkat | Navigasi Langsung |
| :--- | :--- | :--- | :--- |
| 19 | **Privacy Settings & Ephemeral** | Atur privasi last seen, online, foto, centang biru, disappearing timer | [Buka Panduan & Kode](#privacy-settings) |
| 20 | **Blocklist Management** | Kueri daftar blokir, blokir dan buka blokir nomor kontak | [Buka Panduan & Kode](#blocklist) |
| 21 | **Status Privacy Settings** | Atur siapa saja kontak yang diizinkan melihat status cerita akun | [Buka Panduan & Kode](#status-privacy) |
| 22 | **Profile, Privacy & Blocklist API** | Operasi cepat bio status, foto avatar, dan pemblokiran kontak | [Buka Panduan & Kode](#profile-privacy-api) |

#### 👤 7. Profil, Kontak & WhatsApp Bisnis
| No | Fitur & Topik | Deskripsi Singkat | Navigasi Langsung |
| :--- | :--- | :--- | :--- |
| 23 | **Profile Text Status (About / Bio)** | Ubah status info bio About profil akun via WhatsApp MEX GraphQL | [Buka Panduan & Kode](#about-bio) |
| 24 | **High-Resolution Profile Pictures** | Dapatkan URL foto profil resolusi tinggi (HD) dan thumbnail | [Buka Panduan & Kode](#profile-picture) |
| 25 | **Contact QR & Business Short Links** | Buat tautan QR kontak dan resolusi tautan pesan pendek bisnis | [Buka Panduan & Kode](#qr-shortlinks) |
| 26 | **Business Profiles & USync Info** | Kueri jam operasional, alamat, email, kategori, dan query USync | [Buka Panduan & Kode](#biz-usync) |
| 27 | **Order Details (Business Commerce)** | Parsing detail checkout transaksi pesanan barang dan total harga | [Buka Panduan & Kode](#order-details) |
| 28 | **Updating Push Name** | Perbarui nama tampilan perangkat bot yang terlihat di WhatsApp | [Buka Panduan & Kode](#push-name) |
| 29 | **Meta AI Bot Discovery** | Temukan daftar bot dan persona AI Meta yang tersedia di server | [Buka Panduan & Kode](#bot-discovery) |

#### ⚙️ 8. Manajemen Chat & Panggilan
| No | Fitur & Topik | Deskripsi Singkat | Navigasi Langsung |
| :--- | :--- | :--- | :--- |
| 30 | **Chat Management (Pin, Mute, Archive)** | Sematkan chat, arsipkan, bisukan obrolan, dan timer disappearing | [Buka Panduan & Kode](#chat-management) |
| 31 | **Call Management (Tolak Panggilan)** | Tolak panggilan suara atau video yang masuk secara otomatis | [Buka Panduan & Kode](#call-management) |

---

## 🚀 Contoh Kode & Panduan Implementasi

<a id="sec-1"></a><a id="lid-pn"></a>
### 1. Extract & Resolve Phone Number (PN)

Extract clean phone number digits from incoming sender JID, with automatic LID-to-PN resolution via `JaWaClient.LID_TO_PN_MAP`:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.store.FileAuthStore;
import id.jawa.feature.messaging.MessageReceiver;

import java.nio.file.Path;

public class PhoneNumberResolverExample {

    public static void main(String[] args) throws Exception {
        FileAuthStore store = new FileAuthStore(Path.of("sessions/bot.session"));
        JaWaClient client = new JaWaClient(store, Path.of("sessions/bot.signal")).autoReconnect(true);

        client.listener(new JaWaClient.Listener() {
            @Override
            public void onConnected() {
                System.out.println("Bot connected!");
            }

            @Override
            public void onMessage(MessageReceiver.Decoded d) {
                String senderJid = d.senderJid();
                String phoneNumber = extractPhoneNumber(senderJid);

                System.out.println("Pesan dari nomor HP: +" + phoneNumber);
                System.out.println("Raw Sender JID     : " + senderJid);
            }
        });

        client.connect();
        client.join();
    }

    public static String extractPhoneNumber(String senderJid) {
        if (senderJid == null) return "";

        String userPart = senderJid.split("@")[0].split(":")[0];

        String mappedPn = JaWaClient.LID_TO_PN_MAP.get(userPart + "@lid");
        if (mappedPn != null) {
            return mappedPn.split("@")[0].split(":")[0].replaceAll("[^0-9]", "");
        }

        return userPart.replaceAll("[^0-9]", "");
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-2"></a><a id="phone-pairing"></a>
### 2. Phone Pairing Code Connection

Connect and pair using an 8-character pairing code sent directly to your phone number:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.store.FileAuthStore;

import java.nio.file.Path;
import java.util.List;

public class PhonePairingExample {

    public static void main(String[] args) throws Exception {
        FileAuthStore store = new FileAuthStore(Path.of("sessions/phone.session"));
        JaWaClient client = new JaWaClient(store, Path.of("sessions/phone.signal"))
                .autoReconnect(true)
                .autoReadReceipt(true)
                .autoComposingPresence(false)
                .autoPreKeyUpload(true);

        client.listener(new JaWaClient.Listener() {
            @Override
            public void onQr(List<String> qrs) {
                String targetPhone = "6285607589072";
                client.requestPairingCode(targetPhone, null).whenComplete((code, err) -> {
                    if (err != null) {
                        System.err.println("Failed to get pairing code: " + err.getMessage());
                        return;
                    }
                    System.out.println("==================================================");
                    System.out.println("📱 Target Phone Number: +" + targetPhone);
                    System.out.println("🔑 Pairing Code      : " + code.substring(0, 4) + "-" + code.substring(4));
                    System.out.println("==================================================");
                });
            }

            @Override
            public void onConnected() {
                System.out.println("🎉 Connected and paired successfully via Phone Code!");
            }
        });

        client.connect();
        client.join();
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-3"></a><a id="qr-pairing"></a>
### 3. Terminal QR Code Pairing

Connect and pair by scanning an ASCII QR matrix in the terminal:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.store.FileAuthStore;
import id.jawa.feature.pairing.QrTerminal;

import java.nio.file.Path;
import java.util.List;

public class QrPairingExample {

    public static void main(String[] args) throws Exception {
        FileAuthStore store = new FileAuthStore(Path.of("sessions/qr.session"));
        JaWaClient client = new JaWaClient(store, Path.of("sessions/qr.signal")).autoReconnect(true);

        client.listener(new JaWaClient.Listener() {
            @Override
            public void onQr(List<String> qrs) {
                if (!qrs.isEmpty()) {
                    System.out.println("\n>>> Scan this QR code with WhatsApp Linked Devices:\n");
                    System.out.print(QrTerminal.render(qrs.get(0)));
                }
            }

            @Override
            public void onConnected() {
                System.out.println("🎉 Connected successfully via QR Scan!");
            }
        });

        client.connect();
        client.join();
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-4"></a><a id="buttons-list"></a>
### 4. Interactive CTA Buttons & Selection Lists

Send rich interactive call-to-action buttons (URL link, copy promo code, quick reply) and single-selection dropdown lists:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.feature.messaging.MessageEncoder;
import id.jawa.feature.messaging.MessageEncoder.CtaButton;
import id.jawa.feature.messaging.MessageEncoder.ListRow;
import id.jawa.feature.messaging.MessageEncoder.ListSection;

import java.util.List;

public class ButtonsAndListExample {

    public static void sendCtaButtons(JaWaClient client, String chatJid) {
        List<CtaButton> buttons = List.of(
            CtaButton.url("🌐 Visit GitHub", "https://github.com/jochris/JaWa"),
            CtaButton.copy("📋 Copy Code", "JAWA-PROMO-2026"),
            CtaButton.quickReply("🏓 Ping Bot", "ping_cmd")
        );

        var msg = MessageEncoder.interactiveCtaButtons(
            "*Interactive CTA Buttons Example*",
            "Choose an action below:",
            buttons
        );
        client.sendMessage(chatJid, msg);
    }

    public static void sendDropdownList(JaWaClient client, String chatJid) {
        List<CtaButton> buttons = List.of(
            CtaButton.singleSelect("Open Menu 📋", List.of(
                new ListSection("Main Actions", List.of(
                    new ListRow("ping_cmd", "Ping Server", "Check server uptime & health"),
                    new ListRow("menu_cmd", "Show Menu", "Display main navigation")
                )),
                new ListSection("Demos", List.of(
                    new ListRow("buttons_cmd", "CTA Buttons", "Test interactive buttons"),
                    new ListRow("carousel_cmd", "Carousel Slider", "Test card carousel")
                ))
            ))
        );

        var msg = MessageEncoder.interactiveCtaButtons(
            "*Dropdown Selection List Example*",
            "Select an option from the menu:",
            buttons
        );
        client.sendMessage(chatJid, msg);
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-5"></a><a id="carousel"></a>
### 5. Carousel Cards Slider

Send horizontal scrolling carousel cards featuring images, descriptions, and action buttons:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.feature.messaging.MessageEncoder.CtaButton;

import java.util.List;

public class CarouselExample {

    public static void sendCarouselSlider(JaWaClient client, String chatJid, byte[] imageBytes) {
        List<JaWaClient.CarouselCardInput> cards = List.of(
            new JaWaClient.CarouselCardInput(
                "Card 1: Feature Overview",
                "Learn more about JaWa features",
                imageBytes,
                "image/jpeg",
                "Slide 1",
                List.of(CtaButton.quickReply("Select Card 1", "card_1_cmd"))
            ),
            new JaWaClient.CarouselCardInput(
                "Card 2: Documentation",
                "Read the official guide & docs",
                imageBytes,
                "image/jpeg",
                "Slide 2",
                List.of(CtaButton.quickReply("Select Card 2", "card_2_cmd"))
            )
        );

        client.sendCarousel(chatJid, "*Swipe cards horizontally:*", "Carousel Demo", cards, null);
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-6"></a><a id="sqlite-store"></a>
### 6. SQLite Database Session Storage & Maintenance

Save and restore WhatsApp credentials & session state using an SQLite database (`SqliteAuthStore`):

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.store.SqliteAuthStore;

import java.nio.file.Path;
import java.util.Map;

public class SqliteSessionExample {

    public static void main(String[] args) throws Exception {
        SqliteAuthStore store = new SqliteAuthStore(Path.of("sessions/app.db"));

        boolean isHealthy = store.checkIntegrity();
        store.vacuum();
        Map<String, Integer> stats = store.getStats();

        JaWaClient client = new JaWaClient(store, Path.of("sessions/app.signal")).autoReconnect(true);

        client.listener(new JaWaClient.Listener() {
            @Override
            public void onConnected() {
                System.out.println("🎉 Connected using SQLite Database session store!");
            }
        });

        client.connect();
        client.join();
    }
}
```

#### SQLite Maintenance API Reference

| Method | Description |
| :--- | :--- |
| `store.checkIntegrity()` | Runs `PRAGMA integrity_check` to verify database health. Returns `true` if intact. |
| `store.vacuum()` | Executes `VACUUM` to compact disk size and reclaim unused space after pruning prekeys. |
| `store.getStats()` | Returns active row counts across `credentials`, `sessions`, `prekeys`, `sender_keys`, and `appstate_keys`. |
| `store.clearAll()` | Clears all stored records and keys for clean session unpairing and reset. |

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-7"></a><a id="media-downloader"></a>
### 7. Automatic Media Downloader Helper

Automatically download and decrypt attachments (Image, Video, Audio PTT, Document, Sticker) from an inbound message, with automatic fallback resolution to `directPath` via WhatsApp MMG CDN if direct URLs are blank:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.feature.messaging.MessageReceiver;

public class MediaDownloadExample {

    public static void onIncomingMessage(JaWaClient client, MessageReceiver.Decoded decoded) {
        if (decoded.message() == null) return;

        byte[] mediaBytes = client.downloadMedia(decoded);
        if (mediaBytes != null) {
            System.out.println("Downloaded decrypted file: " + mediaBytes.length + " bytes");
        }
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-8"></a><a id="profile-privacy-api"></a>
### 8. Profile, Privacy & Blocklist API

Manage account bio status, fetch profile picture URLs, and manage contact blocklists:

```java
package com.example;

import id.jawa.client.JaWaClient;

import java.util.List;

public class ProfilePrivacyExample {

    public static void manageProfile(JaWaClient client, String targetJid) throws Exception {
        client.updateProfileStatus("Built with JaWa Library 🚀");

        String avatarUrl = client.fetchProfilePictureUrl(targetJid).get();
        System.out.println("Avatar URL: " + avatarUrl);

        client.updateBlockStatus(targetJid, true);
        List<String> blockedJids = client.fetchBlockList().get();
        System.out.println("Blocked Contacts: " + blockedJids);
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-9"></a><a id="chat-management"></a>
### 9. Chat Management (Pin, Archive, Mute & Disappearing Messages)

Manage chat pin, archive, mute states, and ephemeral disappearing message timers:

```java
package com.example;

import id.jawa.client.JaWaClient;

public class ChatManagementExample {

    public static void manageChat(JaWaClient client, String chatJid, String groupJid) {
        client.chats().pinChat(chatJid, true);
        client.chats().archiveChat(chatJid, true);
        client.chats().muteChat(chatJid, 8 * 3600);
        client.chats().setDisappearingTimer(chatJid, 86400);
        client.groups().setDisappearingTimer(groupJid, 604800);
    }
}
```

#### Disappearing Message Presets Reference

| Duration | Seconds | Description |
| :--- | :--- | :--- |
| Off | `0` | Disables disappearing messages in chat or group. |
| 24 Hours | `86400` | Messages disappear after 24 hours. |
| 7 Days | `604800` | Messages disappear after 7 days. |
| 90 Days | `7776000` | Messages disappear after 90 days. |

#### Automatic Ephemeral Message Wrapping

JaWa automatically wraps outbound messages in `ephemeralMessage` envelopes when a chat or group has an active disappearing timer (`autoEphemeral = true` by default). Inbound disappearing messages are also automatically unpacked so message text and media are directly accessible.

```java
client.autoEphemeral(true);

client.chats().setDisappearingTimer(chatJid, 86400);

int currentTimer = client.chats().getDisappearingTimer(chatJid);

client.messages().sendText(chatJid, "This message automatically disappears after 24 hours!");
```

To configure or disable automatic wrapping:

```java
client.autoEphemeral(false);
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-10"></a><a id="privacy-settings"></a>
### 10. Privacy Settings & Default Ephemeral Timer

Inspect and configure account privacy visibility across categories (last seen, profile photo, status, online status, read receipts) and set the default disappearing message timer for all new chats:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.PrivacySettings;

public class PrivacyExample {

    public static void configurePrivacy(JaWaClient client) throws Exception {
        PrivacySettings settings = client.privacy().getPrivacySettings().get();
        System.out.println("Last Seen: " + settings.lastSeen());
        System.out.println("Profile Pic: " + settings.profilePhoto());
        System.out.println("Status: " + settings.status());
        System.out.println("Read Receipts: " + settings.readReceipts());
        System.out.println("Default Disappearing Timer: " + settings.defaultDisappearingDuration() + "s");

        client.privacy().setPrivacySetting("last", "all").get();
        client.privacy().setPrivacySetting("status", "contacts").get();
        client.privacy().setPrivacySetting("readreceipts", "all").get();

        client.privacy().setDefaultDisappearingTimer(86400).get();
    }
}
```

#### Privacy Categories & Allowed Values Reference

| Category | Description | Allowed Values |
| :--- | :--- | :--- |
| `last` | Last Seen visibility | `all`, `contacts`, `contact_blacklist`, `none` |
| `online` | Online status visibility | `all`, `match_last_seen` |
| `profile` | Profile photo visibility | `all`, `contacts`, `contact_blacklist`, `none` |
| `status` | About status text visibility | `all`, `contacts`, `contact_blacklist`, `none` |
| `readreceipts` | Read receipts (blue ticks) | `all`, `none` |
| `calladd` | Who can call this account | `all`, `known` |

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-11"></a><a id="qr-shortlinks"></a>
### 11. Personal Contact QR & Business Short Links

Generate personal contact QR links (`https://wa.me/qr/<code>`), revoke them, or resolve contact and business message short links (`https://wa.me/message/<code>`):

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.BusinessMessageLinkTarget;
import id.jawa.domain.model.ContactQrTarget;

public class QrAndShortLinkExample {

    public static void handleLinks(JaWaClient client) throws Exception {
        String myQrUrl = client.profile().getContactQrLink().get();
        System.out.println("My Contact QR URL: " + myQrUrl);

        String refreshedQrUrl = client.profile().getContactQrLink(true).get();
        System.out.println("Refreshed QR URL: " + refreshedQrUrl);

        ContactQrTarget contact = client.profile().resolveContactQrLink("https://wa.me/qr/ABCDEF12345").get();
        System.out.println("Resolved Contact JID: " + contact.jid());
        System.out.println("Push Name: " + contact.pushName());

        BusinessMessageLinkTarget biz = client.profile().resolveBusinessMessageLink("https://wa.me/message/XYZ987").get();
        System.out.println("Business JID: " + biz.jid());
        System.out.println("Business Name: " + biz.verifiedName());
        System.out.println("Prefilled Message: " + biz.message());
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-12"></a><a id="biz-usync"></a>
### 12. Business Profiles & USync User Info

Fetch full WhatsApp business profiles (address, email, description, website list, categories, operating hours) and query multi-field user info via USync in a single network round trip:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.BusinessProfile;
import id.jawa.domain.model.UserInfo;

import java.util.List;
import java.util.Map;

public class BusinessAndUserInfoExample {

    public static void inspectUsers(JaWaClient client, String targetJid) throws Exception {
        BusinessProfile profile = client.profile().getBusinessProfile(targetJid).get();
        if (profile != null) {
            System.out.println("Address: " + profile.address());
            System.out.println("Email: " + profile.email());
            System.out.println("Description: " + profile.description());
            System.out.println("Websites: " + profile.websites());
            for (BusinessProfile.Category cat : profile.categories()) {
                System.out.println("Category: " + cat.name());
            }
        }

        UserInfo info = client.profile().getUserInfo(targetJid).get();
        if (info != null) {
            System.out.println("About bio: " + info.status());
            System.out.println("Profile picture ID: " + info.pictureId());
            System.out.println("Associated LID: " + info.lid());
            System.out.println("Active Devices: " + info.devices().size());
        }

        Map<String, UserInfo> batch = client.profile().getUserInfo(List.of(targetJid, "628987654321@s.whatsapp.net")).get();
        System.out.println("Batch size: " + batch.size());
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-13"></a><a id="group-admin"></a>
### 13. Advanced Group Administration

Control group join approval requirements, member add mode (admin only versus all members), and manage pending membership approval requests:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.GroupJoinRequest;

import java.util.List;

public class AdvancedGroupAdminExample {

    public static void manageGroupSettings(JaWaClient client, String groupJid) throws Exception {
        client.groups().setGroupJoinApprovalMode(groupJid, true).get();

        client.groups().setGroupMemberAddMode(groupJid, true).get();

        List<GroupJoinRequest> pending = client.groups().getGroupRequestParticipants(groupJid).get();
        for (GroupJoinRequest req : pending) {
            System.out.println("User waiting for approval: " + req.jid() + " at " + req.requestedAt());
        }

        if (!pending.isEmpty()) {
            List<String> toApprove = List.of(pending.get(0).jid());
            client.groups().updateGroupRequestParticipants(groupJid, toApprove, true).get();
        }
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-14"></a><a id="newsletter-channel"></a>
### 14. Newsletter & Channel Management

Interact with WhatsApp Channels / Newsletters by subscribing to live updates, following, muting, fetching metadata, sending reactions, marking messages as viewed, and reading typed newsletter history:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.NewsletterMessage;
import id.jawa.domain.model.NewsletterMetadata;

import java.util.List;

public class NewsletterExample {

    public static void channelActions(JaWaClient client, String channelJid) throws Exception {
        client.newsletters().acceptNewsletterTOSNotice().get();
        System.out.println("Channel TOS notice accepted");

        client.newsletters().follow(channelJid).get();
        System.out.println("Followed channel: " + channelJid);

        client.newsletters().toggleMute(channelJid, true).get();
        System.out.println("Muted channel notifications");

        NewsletterMetadata meta = client.newsletters().getInfo(channelJid).get();
        if (meta != null) {
            System.out.println("Channel Name: " + meta.name());
            System.out.println("Subscribers : " + meta.subscriberCount());
            System.out.println("My Role     : " + meta.role());
            System.out.println("Invite Code : " + meta.inviteCode());
        }

        NewsletterMetadata inviteMeta = client.newsletters().getInfoWithInvite("https://whatsapp.com/channel/ABC123XYZ").get();
        if (inviteMeta != null) {
            System.out.println("Preview Channel: " + inviteMeta.name());
        }

        long durationSec = client.newsletters().subscribeLiveUpdates(channelJid).get();
        System.out.println("Subscribed to live channel updates for: " + durationSec + "s");

        client.newsletters().sendReaction(channelJid, "12345", "🔥");

        client.newsletters().markViewed(channelJid, List.of(12345L, 12346L)).get();

        List<NewsletterMessage> messages = client.newsletters().getMessages(channelJid, 20).get();
        for (NewsletterMessage msg : messages) {
            System.out.println("Message ID: " + msg.messageId() + " Views: " + msg.viewsCount());
        }

        client.newsletters().unfollow(channelJid).get();
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-15"></a><a id="call-management"></a>
### 15. Call Management

Reject incoming WhatsApp voice and video calls programmatically:

```java
package com.example;

import id.jawa.client.JaWaClient;

public class CallManagementExample {

    public static void handleIncomingCall(JaWaClient client, String callerJid, String callId) {
        client.rejectCall(callerJid, callId).whenComplete((v, err) -> {
            if (err == null) {
                System.out.println("Call " + callId + " rejected successfully");
            }
        });
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-16"></a><a id="offline-sync"></a>
### 16. Offline Sync Message Filtering

When connecting after being offline, WhatsApp sends backlogged offline messages. JaWa includes automatic offline message filtering to avoid responding to outdated commands while saving sender LID mappings:

```java
JaWaClient client = new JaWaClient(store)
    .autoIgnoreOfflineMessages(true)
    .offlineMessageGracePeriodSec(2);
```

| Method | Default | Description |
| :--- | :--- | :--- |
| `.autoIgnoreOfflineMessages(boolean)` | `true` | When true, ignores historical offline messages delivered during initial connection. |
| `.offlineMessageGracePeriodSec(int)` | `2` | Number of seconds grace period around login timestamp for message evaluation. |

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-17"></a><a id="push-name"></a>
### 17. Updating Push Name (Companion Display Name)

Change the companion device display name sent in presence broadcasts:

```java
package com.example;

import id.jawa.client.JaWaClient;

public class PushNameExample {

    public static void changeName(JaWaClient client) throws Exception {
        client.profile().updatePushName("JaWa Bot Official").get();
        System.out.println("Display name updated successfully");
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-18"></a><a id="logout-session"></a>
### 18. Logout & Session Unlinking

Safely unlink the companion device from WhatsApp Web, purge credentials from storage, and close socket connection:

```java
package com.example;

import id.jawa.client.JaWaClient;

public class LogoutExample {

    public static void exitSession(JaWaClient client) throws Exception {
        client.logout().get();
        System.out.println("Session unlinked and logged out cleanly");
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-19"></a><a id="order-details"></a>
### 19. Order Details (WhatsApp Business & Commerce Orders)

Fetch and parse structured order details from incoming `OrderMessage` containing order ID and base64 security token:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.OrderDetails;

public class OrderExample {

    public static void inspectOrder(JaWaClient client, String orderId, String tokenBase64) throws Exception {
        OrderDetails order = client.profile().getOrderDetails(orderId, tokenBase64).get();
        if (order != null) {
            System.out.println("Order ID    : " + order.id());
            System.out.println("Created At  : " + order.createdAt());
            System.out.println("Catalog ID  : " + order.catalogId());
            if (order.price() != null) {
                System.out.println("Total Amount: " + order.price().total() + " " + order.price().currency());
            }
            for (OrderDetails.OrderProduct item : order.products()) {
                System.out.println("Item: " + item.name() + " x" + item.quantity() + " (" + item.price() + ")");
            }
        }
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-20"></a><a id="star-cleanup"></a>
### 20. Message Starring and Chat Cleanup

Star messages, delete chats, or clear message histories:

```java
package com.example;

import id.jawa.client.JaWaClient;

public class StarAndChatExample {

    public static void run(JaWaClient client, String chatJid, String messageId) throws Exception {
        client.chats().starMessage(chatJid, messageId, true).get();
        client.chats().starMessage(chatJid, messageId, false).get();

        client.chats().clearChat(chatJid).get();
        client.chats().deleteChat(chatJid).get();
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-21"></a><a id="blocklist"></a>
### 21. Blocklist Management

Retrieve full blocklist with companion LID and phone number mappings, or block and unblock contacts:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.Blocklist;
import id.jawa.domain.model.BlocklistItem;

public class BlocklistExample {

    public static void manageBlocklist(JaWaClient client, String contactJid) throws Exception {
        Blocklist blocklist = client.privacy().getBlocklist().get();
        System.out.println("DHash: " + blocklist.dhash());
        for (BlocklistItem item : blocklist.items()) {
            System.out.println("Blocked: LID=" + item.lid() + " PN=" + item.pn() + " Active=" + item.active());
        }

        client.privacy().block(contactJid).get();
        client.privacy().unblock(contactJid).get();
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-22"></a><a id="status-privacy"></a>
### 22. Status Privacy Settings

Check status broadcast audience settings (contacts, blacklist, or whitelist modes):

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.StatusPrivacy;

import java.util.List;

public class StatusPrivacyExample {

    public static void checkStatusPrivacy(JaWaClient client) throws Exception {
        List<StatusPrivacy> settings = client.privacy().getStatusPrivacy().get();
        for (StatusPrivacy sp : settings) {
            System.out.println("Type: " + sp.type() + " Default: " + sp.isDefault() + " Users: " + sp.list().size());
        }
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-23"></a><a id="about-bio"></a>
### 23. Profile Text Status (About / Bio)

Update the user profile "About" bio text via WhatsApp Web MEX mutation:

```java
package com.example;

import id.jawa.client.JaWaClient;

public class ProfileStatusExample {

    public static void updateBio(JaWaClient client) throws Exception {
        client.profile().setStatusMessage("Available on WhatsApp via JaWa!").get();
        System.out.println("About bio updated successfully");
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-24"></a><a id="community"></a>
### 24. Community Management

Manage WhatsApp Communities, inspect child subgroups, query linked participants across all community groups, or link and unlink child groups:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.CommunitySubGroup;

import java.util.List;

public class CommunityExample {

    public static void inspectCommunity(JaWaClient client, String communityJid, String childGroupJid) throws Exception {
        List<CommunitySubGroup> subGroups = client.groups().getSubGroups(communityJid).get();
        for (CommunitySubGroup group : subGroups) {
            System.out.println("Subgroup: " + group.subject() + " (" + group.jid() + ") Default: " + group.defaultSubGroup());
        }

        List<String> participants = client.groups().getLinkedGroupsParticipants(communityJid).get();
        System.out.println("Total community participants: " + participants.size());

        client.groups().linkGroup(communityJid, childGroupJid).get();
        client.groups().unlinkGroup(communityJid, childGroupJid).get();
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-25"></a><a id="group-invite"></a>
### 25. Group Invite Message Acceptance

Accept private group invitations sent as invite messages (containing group JID, inviter JID, code, and expiration timestamp):

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.protocol.codec.BinaryNode;

public class GroupInviteMessageExample {

    public static void acceptInvite(JaWaClient client, String groupJid, String inviterJid, String code, long expiration) throws Exception {
        BinaryNode info = client.groups().getGroupInfoFromInvite(groupJid, inviterJid, code, expiration).get();
        System.out.println("Group info from invite: " + info);

        client.groups().joinGroupWithInvite(groupJid, inviterJid, code, expiration).get();
        System.out.println("Joined group successfully via invite message");
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-26"></a><a id="profile-picture"></a>
### 26. High-Resolution Profile Pictures

Fetch structured profile picture metadata including direct path, image ID, and URLs for full-resolution HD photos or preview thumbnails:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.ProfilePictureInfo;

public class ProfilePictureExample {

    public static void fetchPictures(JaWaClient client, String targetJid) throws Exception {
        ProfilePictureInfo hdPic = client.profile().getProfilePictureInfo(targetJid).get();
        if (hdPic != null) {
            System.out.println("HD Picture URL : " + hdPic.url());
            System.out.println("Picture ID     : " + hdPic.id());
            System.out.println("Direct Path    : " + hdPic.directPath());
        }

        ProfilePictureInfo thumbnail = client.profile().getProfilePictureInfo(targetJid, true).get();
        if (thumbnail != null) {
            System.out.println("Thumbnail URL  : " + thumbnail.url());
        }
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-27"></a><a id="bot-discovery"></a>
### 27. Meta AI Bot Discovery

Discover WhatsApp AI personas and bot accounts registered on the server:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.BotListInfo;

import java.util.List;

public class BotDiscoveryExample {

    public static void listBots(JaWaClient client) throws Exception {
        List<BotListInfo> bots = client.profile().getBotList().get();
        for (BotListInfo bot : bots) {
            System.out.println("Bot Persona ID: " + bot.personaId() + " JID: " + bot.botJid());
        }
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-28"></a><a id="stickers"></a>
### 28. WebP Stickers (Static & Animated)

Send static or animated WebP stickers to private chats or groups:

```java
package com.example;

import id.jawa.client.JaWaClient;
import java.nio.file.Files;
import java.nio.file.Path;

public class StickerExample {

    public static void sendStickers(JaWaClient client, String chatJid) throws Exception {
        byte[] staticWebp = Files.readAllBytes(Path.of("sticker.webp"));
        String staticMsgId = client.media().sendSticker(chatJid, staticWebp).get();
        System.out.println("Static sticker sent: " + staticMsgId);

        byte[] animatedWebp = Files.readAllBytes(Path.of("animated.webp"));
        String animMsgId = client.media().sendSticker(chatJid, animatedWebp, true).get();
        System.out.println("Animated sticker sent: " + animMsgId);
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-29"></a><a id="media-delete"></a>
### 29. Media Deletion from WhatsApp CDN

Explicitly delete uploaded media files from WhatsApp CDN servers using direct path and encrypted file hash:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.feature.media.MediaCrypto;

public class MediaDeleteExample {

    public static void deleteUploadedMedia(JaWaClient client, String directPath, byte[] encFileHash) throws Exception {
        client.media().deleteMedia(directPath, encFileHash, MediaCrypto.MediaType.IMAGE).get();
        System.out.println("Media deleted from WhatsApp CDN successfully");
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-30"></a><a id="media-retry"></a>
### 30. Media Retry Requests (404/410 Handling)

Request phone re-upload when media files expire or return 404/410, and decrypt incoming retry responses:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.proto.Wa;

public class MediaRetryExample {

    public static void requestMediaRetry(JaWaClient client, MessageReceiver.Decoded target, byte[] mediaKey) throws Exception {
        client.media().sendMediaRetryReceipt(target, mediaKey).get();
        System.out.println("Media retry receipt sent to primary phone");
    }

    public static void handleRetryResponse(byte[] ciphertext, byte[] iv, String messageId, byte[] mediaKey) {
        Wa.MediaRetryNotification notif = JaWaClient.decryptMediaRetryNotification(ciphertext, iv, messageId, mediaKey);
        System.out.println("Retry Result: " + notif.getResult());
        System.out.println("New Direct Path: " + notif.getDirectPath());
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

<a id="sec-31"></a><a id="privacy-receipts"></a>
### 31. Companion Privacy Receipts (Read-Self & Played-Self)

Mark messages or view-once media as read locally on companion devices without sending blue ticks to the counterparty:

```java
package com.example;

import id.jawa.client.JaWaClient;

public class PrivacyReceiptsExample {

    public static void markReadPrivately(JaWaClient client, String chatJid, String msgId) {
        client.messages().sendReadSelfReceipt(chatJid, msgId);
        client.messages().sendPlayedSelfReceipt(chatJid, msgId);
        System.out.println("Privacy receipt sent for companion sync");
    }
}
```

[↑ Kembali ke Indeks Fitur](#daftar-panduan)

---

## 📄 License

This project is licensed under the terms of the **GNU General Public License v3.0** (GPL-3.0-or-later). See [LICENSE](LICENSE) for details.
