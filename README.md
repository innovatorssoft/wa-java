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

<a id="feature-directory"></a><a id="daftar-panduan"></a>
## 📖 Quickstart Guide & Feature Directory

Use the interactive directory below to learn the basic workflow and quickly jump to working code examples and technical explanations for any feature:

### 🔄 Application Lifecycle Guide

1. **Auth & Signal Storage**: Initialize credential storage using `FileAuthStore` (`.session` file) or `SqliteAuthStore` (SQLite database).
2. **Client Initialization**: Create a `JaWaClient` instance with storage paths and configure connection options (auto-reconnect, auto-receipts, presence).
3. **Session Pairing**: Link your companion bot to WhatsApp on your primary phone using an 8-character pairing code (`requestPairingCode`) or ASCII terminal QR code scan.
4. **Event Listener**: Register a listener via `client.listener(...)` to handle incoming messages (`onMessage`), QR updates (`onQr`), connection state (`onConnected`), and other events.
5. **Sub-Services Access**: Send text messages, interactive buttons, media, stickers, manage groups, and configure privacy using structured sub-services (`client.messages()`, `client.groups()`, `client.media()`, `client.privacy()`, `client.chats()`, `client.newsletters()`, `client.profile()`).

---

### 📑 Feature Directory & Direct Links

#### 🔐 1. Authentication & Session Persistence
| No | Feature & Topic | Description | Direct Navigation |
| :--- | :--- | :--- | :--- |
| 1 | **Phone Pairing Code** | Pair companion device with an 8-character code sent to your phone | [View Guide & Code](#phone-pairing) |
| 2 | **Terminal QR Code Pairing** | Scan ASCII QR matrix directly from the terminal console | [View Guide & Code](#qr-pairing) |
| 3 | **SQLite Database Session Storage** | Store sessions, keys, and credentials in SQLite with automatic vacuum | [View Guide & Code](#sqlite-store) |
| 4 | **Logout & Session Unlinking** | Safely terminate companion session and clear stored tokens | [View Guide & Code](#logout-session) |

#### 💬 2. Messaging & Interactive Features
| No | Feature & Topic | Description | Direct Navigation |
| :--- | :--- | :--- | :--- |
| 5 | **LID to Phone Number Resolver** | Resolve LID-formatted sender JID to real phone number with cache | [View Guide & Code](#lid-pn) |
| 6 | **Interactive CTA Buttons & Lists** | Send interactive CTA buttons (URL, Copy Code, Quick Reply) & Dropdowns | [View Guide & Code](#buttons-list) |
| 7 | **Carousel Cards Slider** | Interactive horizontal scrolling card carousel with images and buttons | [View Guide & Code](#carousel) |
| 8 | **Message Starring & Chat Cleanup** | Star important messages, delete individual chats, or clear history | [View Guide & Code](#star-cleanup) |
| 9 | **Offline Sync Message Filtering** | Filter out historical offline messages received during reconnection | [View Guide & Code](#offline-sync) |
| 10 | **Companion Privacy Receipts** | Mark read locally without sending blue ticks (read-self and played-self) | [View Guide & Code](#privacy-receipts) |

#### 📸 3. Media, Documents & Stickers
| No | Feature & Topic | Description | Direct Navigation |
| :--- | :--- | :--- | :--- |
| 11 | **Automatic Media Downloader** | Download and decrypt images, video, audio PTT, documents, and view-once | [View Guide & Code](#media-downloader) |
| 12 | **WebP Stickers (Static & Animated)** | Send static WebP stickers and animated stickers | [View Guide & Code](#stickers) |
| 13 | **Media Deletion from WhatsApp CDN** | Delete uploaded attachments directly from WhatsApp MMS CDN servers | [View Guide & Code](#media-delete) |
| 14 | **Media Retry Requests (404/410)** | Request phone re-upload for expired media and decrypt responses | [View Guide & Code](#media-retry) |

#### 👥 4. Groups & Communities
| No | Feature & Topic | Description | Direct Navigation |
| :--- | :--- | :--- | :--- |
| 15 | **Advanced Group Administration** | Create groups, manage participants/admins, lock settings, invite links | [View Guide & Code](#group-admin) |
| 16 | **Group Invite Message Acceptance** | Accept and join WhatsApp groups from private invite messages | [View Guide & Code](#group-invite) |
| 17 | **Community Management** | Manage WhatsApp Communities, inspect child subgroups, link/unlink groups | [View Guide & Code](#community) |

#### 📢 5. Channels & Newsletters
| No | Feature & Topic | Description | Direct Navigation |
| :--- | :--- | :--- | :--- |
| 18 | **Newsletter & Channel Management** | Follow, mute, send reactions, live updates, ToS acceptance, message history | [View Guide & Code](#newsletter-channel) |

#### 🔒 6. Privacy & Security Settings
| No | Feature & Topic | Description | Direct Navigation |
| :--- | :--- | :--- | :--- |
| 19 | **Privacy Settings & Ephemeral** | Configure last seen, online status, avatar, blue ticks, disappearing timers | [View Guide & Code](#privacy-settings) |
| 20 | **Blocklist Management** | Query blocked contacts, block and unblock users | [View Guide & Code](#blocklist) |
| 21 | **Status Privacy Settings** | Configure contact audience for WhatsApp status stories | [View Guide & Code](#status-privacy) |
| 22 | **Profile, Privacy & Blocklist API** | Unified API for about status, profile picture, and blocklist actions | [View Guide & Code](#profile-privacy-api) |

#### 👤 7. Profile, Contacts & WhatsApp Business
| No | Feature & Topic | Description | Direct Navigation |
| :--- | :--- | :--- | :--- |
| 23 | **Profile Text Status (About / Bio)** | Update user About bio text status via WhatsApp MEX GraphQL mutation | [View Guide & Code](#about-bio) |
| 24 | **High-Resolution Profile Pictures** | Fetch high-resolution (HD) profile photos and thumbnail previews | [View Guide & Code](#profile-picture) |
| 25 | **Contact QR & Business Short Links** | Generate contact QR links and resolve wa.me/message short links | [View Guide & Code](#qr-shortlinks) |
| 26 | **Business Profiles & USync Info** | Query operating hours, address, email, categories, and multi-field USync | [View Guide & Code](#biz-usync) |
| 27 | **Order Details (Business Commerce)** | Parse checkout order details, items, currency, and total amount | [View Guide & Code](#order-details) |
| 28 | **Updating Push Name** | Update companion device display name broadcasted to contacts | [View Guide & Code](#push-name) |
| 29 | **Meta AI Bot Discovery** | Discover WhatsApp Meta AI personas and bot accounts registered on server | [View Guide & Code](#bot-discovery) |

#### ⚙️ 8. Chat & Call Management
| No | Feature & Topic | Description | Direct Navigation |
| :--- | :--- | :--- | :--- |
| 30 | **Chat Management (Pin, Mute, Archive)** | Pin chats, archive, mute notifications, and configure disappearing messages | [View Guide & Code](#chat-management) |
| 31 | **Call Management** | Detect incoming calls and reject voice or video calls programmatically | [View Guide & Code](#call-management) |

---

## 🚀 Code Examples & Detailed Guides

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

                System.out.println("Message from Phone Number: +" + phoneNumber);
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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

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

[↑ Back to Feature Directory](#feature-directory)

---

## 📄 License

This project is licensed under the terms of the **GNU General Public License v3.0** (GPL-3.0-or-later). See [LICENSE](LICENSE) for details.

---

## Baileys event compatibility (Increment 1)

The new `id.jawa.event` package ports the Baileys event model to Java, alongside the
existing single `JaWaClient.Listener` (which is unchanged). Register listeners with
`client.addEventListener(...)` / `client.removeEventListener(...)`, or use
`client.eventBus()` directly:

```java
client.addEventListener(new WaEventListener() {
    @Override
    public void onPresenceUpdate(PresenceUpdate update) {
        System.out.println(update.id() + " -> " + update.presences());
    }
});
```

- `BaileysEventType` — one constant per Baileys event (33 user-facing + the internal
  `event`), each carrying its exact Baileys string name; `fromName(String)` returns
  `null` for unknown names.
- `WaEventListener` — one default (no-op) method per event, plus the generic
  `onEvent(BaileysEventType, Object)` fallback. Implement only the methods you need.
- `WaEventBus` — multi-listener registry (`addListener` / `removeListener` /
  `listenerCount` / `dispatch`). Dispatch is synchronous on the caller's thread; a
  throwing listener is logged and never prevents delivery to the next listener.
  It uses `java.util.logging` and adds no dependency.
- Payload records (e.g. `ConnectionUpdate`, `CredsUpdate`, `PresenceUpdate`,
  `CallEvent`, `MessageUpsert`, `MessageReceiptUpdate`) carry the Baileys payload
  shapes, with per-field Javadoc stating the Baileys source and units. Where wa-java
  already has a model (`Receipt`, `MessageReceiver.Decoded`), the bus payload wraps
  or reuses it instead of duplicating it.
- `PresenceEventParser` and `CallEventParser` implement the Baileys
  `handlePresenceUpdate` / `handleCall` + `getCallStatusFromNode` stanza rules,
  including the offer cache that backfills `isVideo` / `isGroup` / `callerPn`
  into later events for the same call id.

**Dispatched by `JaWaClient` today (6 events):** `connection.update` (`qr`, `open`,
`close` + termination detail, `isNewLogin` partial), `creds.update` (paired `me`
only), `messages.upsert` (single-element list, type `"notify"`, at the same point as
`Listener.onMessage`), `message-receipt.update` (at the same point as
`Listener.onReceipt`), `presence.update` (new `presence` branch in `handleStanza`),
and `call` (new `call` branch in `handleStanza`). Presence and call stanzas are
not acked by the new branches, and they still fall through to the legacy
`onStanza` callback. App-state patches are not emitted as messaging-history events.

**Defined but not yet dispatched (27 events):** `messaging-history.set`,
`messaging-history.status`, `chats.upsert`, `chats.update`, `chats.delete`,
`chats.lock`, `lid-mapping.update`, `contacts.upsert`, `contacts.update`,
`messages.update`, `messages.delete`, `messages.media-update`, `messages.reaction`,
`groups.upsert`, `groups.update`, `group-participants.update`, `group.join-request`,
`group.member-tag.update`, `blocklist.update`, `labels.edit`, `labels.association`,
`newsletter.reaction`, `newsletter.view`, `newsletter-participants.update`,
`newsletter-settings.update`, `settings.update`, and `message-capping.update`
(the last carries only a raw JSON string — no field list is derivable from Baileys).
Each has a payload record and a listener method, documented as not yet dispatched,
so subscribing to them today yields no emissions. The internal `event` fan-out is
deliberately not re-emitted by the bus (that would double-deliver); use `onEvent`
for a single catch-all method.

For the full per-event payload semantics, Before/After status, and remaining gaps,
see `EVENT_COMPATIBILITY_MATRIX.md` in the baileys-port workspace
(`~/workspace/baileys-port/EVENT_COMPATIBILITY_MATRIX.md`).

## Baileys event compatibility (Increment 2)

Increment 2 adds the in-memory store, notification dispatch, message-content
builders, and the newsletter upload path — all on top of Increment 1's event
package, with no existing public API removed.

### In-memory store (`id.jawa.feature.store.InMemoryStore`)

The wa-java equivalent of Baileys' `makeInMemoryStore`, reachable as
`client.store()`. It keeps chats, contacts, messages, groups, presences,
labels and label associations, and LID→PN mappings in memory for the session
(it is not persisted across restarts). Chat ordering matches Baileys'
`KeyedDB` order: pinned first, then unarchived before archived, then most
recently active first. Each chat's message history is capped at 500 messages
by default (configurable in the constructor) — a wa-java adaptation, since
Baileys' store grows until the caller evicts; the oldest messages are dropped
first. `loadMessages(chatId, count, cursorMessageId)` mirrors Baileys'
before-only pagination (there is no forward pagination, matching Baileys'
empty `after` branch). Labels follow Baileys' 20-label cap.

When bound to a bus with `store.bind(bus)`, mutations dispatch the matching
Baileys events (chats upsert/update/delete, contacts upsert/update, messages
delete/reaction, groups upsert/update, labels edit/association, lid-mapping
on change, blocklist). The client's own store is deliberately *not* bound to
the client's bus — the client dispatches events itself at its dispatch
points, and binding both would double-emit. Use the store standalone with
your own bus, or query `client.store()` for state.

### Newly dispatched events (JaWaClient)

On top of Increment 1's 6 dispatched events, JaWaClient now dispatches:

- `chats.upsert` / `chats.update` — first / later messages in a chat (via the
  store), and group-create notifications.
- `messages.reaction` — inbound reaction messages, in addition to the existing
  `messages.upsert` delivery of the raw reaction message (legacy behaviour
  preserved).
- `messages.update` — inbound edit and revoke protocol messages, as key-only
  updates (target key + timestamp; edited content is not modelled in the
  Increment 1 payload).
- `messages.media-update` — `mediaretry` notifications (error code when the
  notification carries an `error` child; the encrypted media payload Baileys
  also carries is not modelled).
- `groups.upsert` / `groups.update` / `group-participants.update` /
  `group.join-request` — `w:gp2` notifications. `leave` is emitted as action
  `remove`, matching Baileys' emitted action set. Group-change fields carried
  are subject, description, announce, restrict, invite code, member-add mode
  and join-approval mode.
- `group.member-tag.update` — inbound `GROUP_MEMBER_LABEL_CHANGE` protocol
  messages.
- `contacts.update` — picture notifications (`imgUrl` `changed`/`removed`).
- `lid-mapping.update` — `sender_pn`/`participant_pn` mappings on inbound
  messages, and mappings newly learned by `resolveLidToPn`.
- `blocklist.update` — `account_sync` blocklist notifications (one event per
  item) and successful local block/unblock calls.
- `newsletter.reaction` / `newsletter.view` /
  `newsletter-participants.update` / `newsletter-settings.update` —
  newsletter notifications (name/description settings only; the verbatim mex
  settings object is not modelled, and the mex admin-promote path is not yet
  wired).

Still not dispatched: `messaging-history.set`, `messaging-history.status`,
`chats.delete` (store-bound only), `chats.lock`, `contacts.upsert`
(store-bound only), `messages.delete` (store-bound only), `labels.edit` /
`labels.association` (store-bound only), `settings.update`, and
`message-capping.update`. App-state sync actions are not yet mapped to
labels/settings/history events, and inbound poll votes are not emitted as
`messages.update` — Baileys' own receive branch for them is commented out.

### Message-content builders (`MessageEncoder`)

New builders, all producing genuine proto payloads (see `MessageEncoder`
Javadoc for Baileys source notes and differences):

- `albumMessage` + `withAlbumAssociation`, and `JaWaClient.sendAlbum` /
  `messages().sendAlbum` (album declaration first, members associated to its
  key, in order; image/video members only, at least two).
- `quizMessage` (V5, as in the Baileys fork; Baileys allows quizzes only in
  newsletters), `pollResultSnapshotMessage`, `pollUpdateMessage`
  (caller-supplied encrypted vote).
- `mentionAllMessage` (`nonJidMentions = 1`, caller-supplied participants),
  `textWithGroupMentions`, `contactsArrayMessage`.
- `lottieStickerMessage` / `wrapAsLottie` (FutureProof wrapper, as in the
  Baileys fork; the `StickerMessage.isLottie` flag overload is also
  available), `groupStatusWrap`, `interactiveAsTemplate` (with template id).
- `richCodeMessage` / `richTableMessage` (core `richResponseMessage` only —
  Baileys' fork wraps it in a `botForwardedMessage` with generated
  verification proofs, which wa-java does not fabricate), and
  `sendPaymentMessage` / `requestPaymentMessage`.
- `messages()` facades: `sendQuiz`, `sendAlbum`, `sendMentionAll`,
  `sendGroupMentions`, `sendContactsArray`, `sendRichCode`, `sendRichTable`,
  `sendPollResult`.

Not portable as builders: spoiler messages (wa.proto contains no spoiler
field or wrapper at all), status mentions (a send-time broadcast path with
stanza `<meta>` nodes, not a message builder), and newsletter admin /
create / community-create / catalog / product APIs (no such Baileys-equivalent
wire path exists in wa-java yet). Newsletter media uploads now use Baileys'
newsletter path map: `MediaUploader.upload(..., newsletter=true)` uploads to
`/newsletter/newsletter-<type>` with `server_thumb_gen=1` (see
`MediaUploader.uploadUrl`, which is unit-testable). There is no `/m1/` or
`/o1/` host logic in Baileys — the path map is the mechanism.

## Baileys event compatibility (Increment 3)

Increment 3 adds newsletter/community/catalog/contact/label server APIs, app-state sync-action mapping, store persistence, single-group metadata and profile-picture (tcToken) queries, status-mention builders, and spoiler messages via real proto regeneration.

- **Events now dispatched from real client data (27 of 33 user-facing):** Increment 2's 23, plus `labels.edit`, `labels.association`, `settings.update`, and `chats.lock`, all dispatched from decoded app-state sync actions in `JaWaClient.requestAppStateSync` via `id.jawa.feature.appstate.AppStateEventMapper`. Still not dispatched from any path: `messaging-history.set`, `messaging-history.status`, and `message-capping.update` (see below). The store-bound set is unchanged.
- **Newsletter:** `NewsletterMexQuery` builders (create/update/name/description/removePicture/subscribers/subscribed/adminCount/changeOwner/demote/delete/capping) and `NewsletterService` facades via `sendMexQuery`; `NewsletterMessageDecoder` for plaintext newsletter messages; `NewsletterLinkedProfiles` inbound parser. Newsletter promote does not exist as a Baileys query and was not invented.
- **Community:** `CommunityQuery` (create, createGroup, metadata, leave, subject/description/settings/member-add/join-approval/ephemeral, invite, participants, request-participants) and `GroupService` facades; `GroupService.queryGroupMetadata` for full single-group metadata.
- **Catalog/product/business:** `CatalogQuery` + `CatalogProduct` and `ProfileService` facades (catalog, collections, product create/update/delete, business-profile delta, cover-photo update/remove). Product-image and cover-photo uploads remain builders-only (no upload step in wa-java).
- **Contact/label server APIs:** `AppPatchBuilder` patch models and `ChatService.buildServer*` facades — request models only, **not sent**: Baileys sends them via encrypted `appPatch`, which wa-java does not yet have (see report).
- **App-state mapping:** `AppStateEventMapper` (feature/appstate) maps sync actions to labels/settings/lock events and history payloads; the history helpers are not wired because the client has no history-sync notification path. `AppStateSettingsLockMapper` (event package) is the settings/lock-only mapper kept from the same increment, renamed to end the duplicate `AppStateEventMapper` name collision.
- **Store persistence:** `StoreJson` (hand-written, no new dependencies) and `FileStorePersistence` (atomic save); persistence lifecycle (when to save/load) is intentionally unwired.
- **Spoiler messages:** `wa.proto` gained `ContextInfo.isSpoiler = 73` and `Message.spoilerMessage = 118`, verified against Baileys' compiled WAProto wire tags and regenerated with protoc 3.10.0; `MessageEncoder.spoilerWrap` mirrors Baileys' wrapper. `MessageKey.participantAlt` was deliberately **not** added to generated proto (Baileys' field is a JS-level extension, not a wire field); the plain `MessagesDelete.MessageKey` model gained a backwards-compatible 5th field, and `GroupMemberTagUpdate` already carried it.
- **Status mentions:** `StatusMentionBuilder` plan/builders only. The send fan-out (relay with `statusJidList` + `mentioned_users`, then per-target mention messages 1500 ms apart) is **not** implemented: `MessageSender` has no additional-nodes support, and a send that dropped the meta nodes would misrepresent the feature.
- **Not dispatched:** `messaging-history.set`/`status` (no history-sync notification path in the client; the pure mapper covers only the two explicit completions), `message-capping.update` (mex notification path, raw JSON only — no field list derivable from Baileys).

## Baileys event compatibility (Increment 4)

Increment 4 adds the appPatch transport, mex notification receive, history-sync dispatch, and status-mention sending.

- **Events dispatched from JaWaClient (30 of 33 user-facing):** Increment 3's 27, plus `message-capping.update` (mex, raw JSON verbatim), `messaging-history.set` (inline-payload history notifications only), and `messaging-history.status` (bootstrap/RECENT-100/paused, deduplicated). `connection.update` gains the `reachoutTimeLock` variant from mex timelock notifications. The remaining 3 (`chats.delete`, `contacts.upsert`, `messages.delete`) are dispatched only by a bound store, so every user-facing event has at least one real dispatch path — but the client-only figure is 30/33.
- **appPatch (transport only, not yet client-wired):** `AppPatchEncoder` mirrors Baileys `encodeSyncdPatch` exactly and round-trips through `AppStateProcessor.decode` with MAC validation; `AppPatchTransport` resyncs, encodes, sends the `w:sync:app:state` IQ, and persists the new version only on success. `ChatService.sendServer*` sends through a caller-supplied transport. Client wiring is blocked on verified prerequisites: `AuthCreds` has no `myAppStateKeyId`, no `app-state-sync-version` store exists, and `requestAppStateSync` does not expose collection versions. Two crypto formulae were inherited, not re-verified (see report §4.2/§4.5): the `AppStateKey.expand()` split order and wa-java's `LtHash` vs the rust bridge — round-trip proves self-consistency only, not wire-compatibility with a real server.
- **Mex receive:** `MexNotificationParser` + a `mex` branch in `handleStanza` — capping verbatim, timelock, linked-profiles lid mappings (also recorded in store + routing map), legacy newsletter promote/settings. Malformed/unknown ops are ignored, never thrown.
- **History-sync:** `HistorySyncNotificationParser` + `HistorySyncStatusTracker` + `handleHistorySyncNotification` (self-only guard, processability gate, inline zlib decode, `.set` mapping, status sink). Non-inline history download is **not** implemented: wa-java has no `md-msg-hist` media type or history HKDF info string, so payload-less notifications log and emit no `.set` — live history sync is not claimed.
- **Status mentions:** `JaWaClient.sendStatusMention` (+ `MessagingService` facades) — status relay with expanded `statusJidList` + `mentioned_users`, then per-target mention relays (1500 ms default delay, injectable). `MessageSender`/`GroupSender` additional-nodes overloads are additive; existing send methods behave identically with empty node lists.
- **Store lifecycle:** `StorePersistence.saveStore/loadStore` are explicit-only; nothing auto-saves. `InMemoryStore.cachedContactImgUrl` returns only the cached URL — fetching a missing URL remains the caller's job.

## Baileys event compatibility (Increment 5)

Increment 5 wires the JaWaClient.appPatch hook, adds history download, community linkedParent resolution, raw product/cover uploads, and explicit store lifecycle methods. No new event names or dispatch paths — event coverage stays at 30/33 from the client (3 more store-bound only).

- **appPatch hook:** `AuthCreds.myAppStateKeyId` (persisted in both stores; set from the last shared app-state key on capture), `FileAppStateVersionStore` (SQLite table or file-per-collection under `<signalDir>/appstate-versions/`; unparseable → no state, never half-decoded), `SyncStateEvolver` + `requestAppStateSyncWithVersions` (Baileys' version precedence; no fabricated versions), and `JaWaClient.appPatch` (ordered failure checks; version persisted only on success). `ChatService` gained 7 no-transport `sendServer*` overloads delegating to it. Fallback: a plain `FileAuthStore` client with no `signalDir` keeps the in-memory version store (versions lost on restart). Limitations: resync is single-request (does not loop on `hasMorePatches`); external-blob snapshots still not downloaded; patch-only syncs with no base state do not persist evolved state; decode runs with `validateMacs=false` (pre-existing). Wire-compatibility caveat from Increment 4 still applies (round-trip proves self-consistency only).
- **History download:** `MediaType.HISTORY("WhatsApp History Keys")` — verifiable from Baileys' HKDF mapping, not guessed — and `downloadHistory` (directPath → `mmg.whatsapp.net`, decrypt, inflate, decode). The history branch now tries inline, then download, then logs-and-skips. Standard media format is reused but not yet confirmed against a live history file (MAC failure → null, never partial sync).
- **Community:** `fetchLinkedGroups` (metadata → `linkedParent` → linked groups; fails rather than guessing on missing metadata) and create-with-metadata refetch (raw-node creators kept). `GroupListQuery.GroupInfo` still has no `linkedParent` — use `GroupMetadataQuery.GroupMetadata` for resolution.
- **Uploads:** product-image and biz-cover-photo use Baileys' raw-upload path (bytes as-is, token = SHA-256 of raw bytes, `/product/image` and `/pps/biz-cover-photo`), not encrypted MMS. `ProfileService` bytes overloads take a caller-supplied `MediaConn`; null/empty bytes = existing URL-only behaviour; uploaded URLs replace `imageUrls()` wholesale.
- **Store lifecycle:** `JaWaClient.saveStore/loadStore` are explicit only (missing file → `false`, no change; otherwise install snapshot via `InMemoryStore.replaceWith`, `true`). Nothing auto-saves.
- **Builders added, transport/emission pending:** `CallLinkQuery` (needs a non-`iq` `<call>` stanza send path) and community invite-V4 builders (expiry `messages.update` emission pending).

## Baileys event compatibility (Increment 6)

Increment 6 adds peer-data operations, placeholder resend, member-label, timelock fetch, public USync, newsletter picture, quick replies, call links, and invite-V4 emission. No new event names — event coverage stays at 30/33 from the client (3 more store-bound only).

- **Peer-data:** `sendPeerDataOperationMessage` (PDO payload, `category=peer`, `push_priority=high_force`, `<meta appdata='default'/>` via the additional-nodes DM path — Baileys' bare-`<enc>` peer framing is a remaining hook, documented); `requestPlaceholderResend` (claim/delay/race manager + PDO send + 8 s expiry; `resolve()` is unwired — no inbound placeholder/PDO-response path exists yet); `updateMemberLabel` (30-char truncation, timestamp, `member_tag` meta).
- **Fetch + USync:** `fetchAccountReachoutTimelock` (verified MEX query id `23983697327930364`; dispatches a partial `connection.update` with only `reachoutTimeLock`); public `executeUSyncQuery` (generic stanza shape + result; per-protocol parsing left to the caller).
- **Newsletter picture:** resize verified as 720×720 JPEG q80 (Jimp top-left square crop + bilinear scale, `javax.imageio` only; sharp centre-crop divergence documented, not implemented); facades via the existing MEX update path, plus a base64 passthrough for already-resized pictures.
- **Quick replies:** patches mirror Baileys exactly (SET even for remove, `deleted` flag, timestamp default) with `ChatService` facades via `JaWaClient.appPatch` (transport overloads also available).
- **Call links:** generic `sendStanzaAsync` (tag-agnostic pending-map correlation, so non-`iq` responses can arrive; unmatched `<call>` stanzas still flow to `CallEvent` parsing) + `createCallLink` (token from response `<link_create>`; error responses fail the future; no token → null; no timeout added, matching `sendIqAsync`).
- **Invite-V4:** accept sends the Increment 5 IQ and, only on a full key, dispatches `messages.update` with the invite copy (`inviteExpiration = 0`, `inviteCode = ''`). The synthetic `GROUP_PARTICIPANT_ADD` upsert Baileys also performs is not ported (wa-java has no synthetic-message upsert path).

## Baileys event compatibility (Increment 7)

Increment 7 adds privacy IQs, push-name and link-preview app-state patches, V4 revoke, cleanDirtyBits, disappearing duration, a connection-update waiter, peer framing, and placeholder-resolve dispatch. No new event names — event coverage stays at 30/33 from the client (3 more store-bound only).

- **Privacy:** `PrivacyQuery` exact shapes; `fetchPrivacySettings` (Baileys-faithful cache — updates do not invalidate it, use `force=true` after an update) + 9 update variants + disappearing-mode alias, mirrored on `PrivacyService`. Values are passed verbatim (`all`/`contacts`/`contact_blacklist`/`none`); no enum was invented. `updateDisableLinkPreviewsPrivacy` is an app-state patch, not a privacy IQ (corrected while verifying).
- **Push-name:** `buildPushNameSetting` / `buildDisableLinkPreviews` patches + facades via `JaWaClient.appPatch`. The presence-based `updatePushName` is kept untouched — the two paths are not interchangeable (presence = local creds + broadcast, no patch; app-state = server-side name synced to other devices) and neither calls the other.
- **V4 revoke, cleanDirtyBits, disappearing duration, waiter:** revoke facade mirroring accept (`!!result`); `cleanDirtyBits` via `sendIqAsync` (Baileys uses fire-and-forget `sendNode` — same disconnected-drop class, but testable); disappearing duration via `executeUSyncQuery` (JID-keyed map, unparseable users omitted where Baileys would yield `NaN`); bus-based waiter (carries the matching update, timeout → null, throwing predicate = non-match, listener always removed).
- **Peer framing / placeholder resolve:** `MessageSender.peerFrame` reframes exactly as Baileys does, but live PDO send is NOT rewired to it yet (bypassing `queryDevices` for a single own device-0 encryption is the exact remaining sender change; PDO still goes out as a self-DM stanza with a wire-identical payload). Placeholder responses are now resolved and dispatched as `messages.upsert` type `notify` (stored metadata preserved, content/timestamp from the reply; dispatch-only, not fed to the store or legacy `onMessage`); `MessageUpsert` has no `requestId`, so Baileys' `stanzaId` isn't propagated.

## Baileys event compatibility (Increment 8)

Increment 8 wires peer-mode PDO live, adds `MessageUpsert.requestId`, and adds the V4 synthetic upsert. No new event names — event coverage stays at 30/33 from the client (3 more store-bound only).

- **Peer PDO:** `MessageSender.buildPeerStanza` encrypts for the single own device-0 JID only; `sendPeerDataOperationMessage` bypasses `queryDevices`, installs a device-0 session, reframes with `peerFrame`, and sends with peer attributes + meta node. Fallback: when `creds.meJid` is bare (device 0 itself), the Increment 6 self-DM path is kept — Baileys' relay loop likewise skips the exact sender device. No protocol store yet → fail fast, never silently drop. Disconnected-drop behaviour unchanged.
- **requestId:** `MessageUpsert` gains a trailing nullable `requestId` (delegating 2-arg constructor; normal messages stay null). PDO dispatch sets it from `response.getStanzaId()` (blank → null).
- **V4 synthetic upsert:** `generateMessageIdV2()` implements Baileys' id algorithm exactly (`3EB0`…`STARFALL`…); `acceptCommunityInviteV4` dispatches a synthetic `messages.upsert` (type `notify`, inviter as sender, community JID, V2 id, null message — matching Baileys' absent content) even for a bare-JID key, and feeds it to the memory store. `Decoded` has no stub-type/stub-parameters fields, so `GROUP_PARTICIPANT_ADD` and the `JSON(me)` parameter are not carried — that is the remaining fidelity hook, not an invented field.

## Baileys event compatibility (Increment 9)

Increment 9 adds `Decoded` stub fields and `updateMediaMessage`. No new event names — event coverage stays at 30/33 from the client (3 more store-bound only).

- **Stub fields:** `Decoded` gains trailing nullable `messageStubType` (the existing generated `Wa.WebMessageInfo.StubType` — Baileys' `WAMessageStubType` is exactly that proto enum) + `messageStubParameters`, with delegating constructors (all existing sites default to null). The V4 synthetic upsert now carries `GROUP_PARTICIPANT_ADD` + `[meStubParameterJson]` (`{"id","name","lid"}`, blank fields omitted, `meJid` bared, no usable me → `"{}"`); the store feed retains them. Caveat: wa-java has no business name distinct from `pushName`, so a business account's `me.name` may differ from Baileys'.
- **updateMediaMessage:** `JaWaClient.updateMediaMessage` (message / media-key-bytes / default 30 s timeout) + `MessagingService` facades. Flow: waiter registered before the retry request is sent, then error code / missing ciphertext / non-`SUCCESS` decrypt → failed future, else the `MediaRetryNotification` is returned. `MediaRetryRequest.build` is the single construction site (`sendMediaRetryReceipt` now delegates to it verbatim); `MediaUpdateWaiter` subscribes to the existing bus (no dispatch change); `MediaUpdate` carries `ciphertext, iv` (trailing, legacy sites still null). Divergences: WA protos are immutable, so wa-java returns the notification for the caller to rebuild from and emits no `messages.update` (Baileys mutates content in place); the caller must supply the media key — wa-java never re-uploads bytes it doesn't hold; timeout → null (Baileys waits indefinitely); disconnected-transport silent failure unchanged (resolves null on timeout).
- **Not landed:** ciphertext-stub placeholder auto-trigger. Baileys gates PDO placeholder resend on the `'Message absent from node'` sentinel, which wa-java never produces (`decode` returns null-message or throws a libsignal `DecryptException`), so the receiver catch cannot distinguish it from failures Baileys answers with a retry receipt only. Landing an unconditional claim would change retry behaviour. The fix needs a structured `DecryptException` failure kind first — see the implementation report.

## Baileys event compatibility (Increment 10)

Increment 10 closes the placeholder auto-trigger blocker from Increment 9. No new event names — event coverage stays at 30/33 from the client (3 more store-bound only).

- **Structured decrypt-failure kind:** `MessageReceiver.DecryptFailureKind` (`NO_MESSAGE_FOUND`, `MISSING_KEYS`, `OTHER`; only `NO_MESSAGE_FOUND` is placeholder-eligible), with legacy `DecryptException` constructors defaulting to `OTHER`. `decode()` throws `NO_MESSAGE_FOUND` for no-`<enc>` + a direct `<unavailable>` child whose type is not `view_once` (Baileys' `decrypt()` rule: decryptable = `enc`/`plaintext` with byte content; `<unavailable type='view_once'>` suppresses the stub).
- **Placeholder auto-trigger:** `PeerDataOperations.isPlaceholderResendEligible` implements Baileys' gates in order (kind → peer-category → unavailable exclusions → 14-day cap; timestamp ≤ 0 treated as fresh). The receiver catch calls `handleDecryptFailurePlaceholder` after the untouched ack + retry-receipt lines: eligible failures upsert a `CIPHERTEXT` stub (`messageStubParameters=[error message]`) as `messages.upsert` type `notify`, claim via `PlaceholderResendManager`, and feed the store; non-eligible failures are a pure no-op. Open decisions (see the implementation report): the catch claims but does not itself send the PDO request (Baileys calls `requestPlaceholderResend`); plain no-`<enc>` without `<unavailable>` still returns the null-message `Decoded`; `MISSING_KEYS` is classified but not NACKed; wa-java keeps its catch retry receipt where Baileys sends ack only.

## Baileys event compatibility (Increment 10b)

Increment 10b completes the placeholder auto-trigger: the decrypt-failure catch now auto-sends the PDO resend, and a pre-existing null-creds crash is fixed. No new event names — event coverage stays at 30/33 from the client (3 more store-bound only).

- **PDO auto-send:** `handleDecryptFailurePlaceholder` still upserts/stores the CIPHERTEXT stub first (ungated on claim success), then invokes the existing `requestPlaceholderResend` (claim + 2 s delay + PDO send + 8 s expiry — no duplicated logic), fire-and-forget with failures logged and swallowed, mirroring Baileys' floating `.then/.catch`. The PDO key mirrors Baileys' `cleanKey` (chat JID, stanza `participant` when present, `fromMe = false` — an assumption, since the helper's stanza inputs carry no fromMe flag). A `placeholderResendDelay` seam exists for tests; production scheduling is unchanged (real 2 s delay). Disconnected transport: existing silent-drop behaviour, no retry added. Test honesty: the PDO assertion captures the peer-mode stanza (category, attrs, bare `<enc>` first, meta last) — the Signal-encrypted inner `PLACEHOLDER_MESSAGE_RESEND` bytes were not decoded.
- **Null-creds guard:** `sendRetryReceipt` returns early (logged, no retry count consumed) when `creds == null`, so a decrypt failure on an unpaired client no longer throws out of the catch block. Receipt behaviour with creds set is unchanged.
- **Remaining dispatch detail:** Baileys' `messages.update` with `[NO_MESSAGE_FOUND_ERROR_TEXT, requestId]` on successful PDO send is not yet mirrored — the stub keeps only the error text; the auto-send future's success value is the `requestId` and is currently discarded.

## Baileys event compatibility (Increment 10c)

Increment 10c mirrors Baileys' `messages.update` on successful PDO auto-send. No new event names — event coverage stays at 30/33 from the client (3 more store-bound only).

- **PDO success update:** on successful auto-send only, the catch dispatches `MESSAGES_UPDATE` with a single `MessageUpdate` for the same key as the stub upsert, carrying `[NO_MESSAGE_FOUND_ERROR_TEXT, requestId]` — gated on truthy, non-blank, non-`"RESOLVED"` requestId, exactly as Baileys (`messages-recv.js:1345–1354`). The future's success value *is* the requestId (verified: `PlaceholderResendManager.request` resolves to `null` / `"RESOLVED"` / the sent PDO message id). Failures never reach the handler (still logged and swallowed); the manual `requestPlaceholderResend` path emits no update. `MessageUpdate` gained a trailing nullable `messageStubParameters` component (existing call sites unchanged, emitting null).
