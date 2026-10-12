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

## 🚀 Code Examples

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

---

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

---

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

## 🧩 Modular Sub-Services

`JaWaClient` organizes functionality into clean, domain-scoped sub-services for clearer and more discoverable API access:

```java
client.messages().sendText(chatJid, "Hello from JaWa!");
client.messages().sendReaction(chatJid, msgId, null, false, "👍");
String nextId = client.messages().generateMessageId();

client.groups().createGroup("Developers Chat", List.of("628123456789"));
client.groups().setDisappearingTimer(groupJid, 604800);

client.media().sendImage(chatJid, imageBytes, "image/jpeg", "Check this out!");

client.newsletters().sendText(newsletterJid, "New update published!");

client.profile().updateStatus("Building with JaWa ☕");

client.chats().sendChatPresence(chatJid, ChatPresence.COMPOSING);
client.chats().setDisappearingTimer(chatJid, 86400);
```

> [!NOTE]
> All legacy top-level methods on `JaWaClient` (e.g. `client.sendText(...)`, `client.createGroup(...)`) remain fully supported for 100% backward compatibility.

---

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

---

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

---

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

---

### 7. Automatic Media Downloader Helper

Automatically download and decrypt attachments (Image, Video, Audio PTT, Document, Sticker) from an inbound message, with automatic fallback resolution to `directPath` via WhatsApp MMG CDN if direct URLs are blank:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.feature.messaging.MessageReceiver;

public class MediaDownloadExample {

    public static void onIncomingMessage(JaWaClient client, MessageReceiver.Decoded decoded) {
        if (decoded.message() == null) return;

        byte[] mediaBytes = client.downloadMedia(decoded.message());
        if (mediaBytes != null) {
            System.out.println("Downloaded decrypted file: " + mediaBytes.length + " bytes");
        }
    }
}
```

---

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

---

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

---

## 📄 License

This project is licensed under the terms of the **GNU General Public License v3.0** (GPL-3.0-or-later). See [LICENSE](LICENSE) for details.
