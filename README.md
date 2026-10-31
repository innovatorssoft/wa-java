# Ja-Wa: Ultra-Fast Java WhatsApp Web Multi-Device Library

[![build](https://github.com/innovatorssoft/wa-java/actions/workflows/build.yml/badge.svg)](https://github.com/innovatorssoft/wa-java/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/)
[![GitHub repo](https://img.shields.io/badge/github-innovatorssoft%2Fwa--java-181717.svg?logo=github)](https://github.com/innovatorssoft/wa-java)

**Ja-Wa** is a lightweight, high-performance **Java 21+** library for the WhatsApp Web Multi-Device (MD) protocol. It provides comprehensive feature parity with popular JavaScript libraries like [Baileys](https://github.com/WhiskeySockets/Baileys), featuring end-to-end Signal Protocol encryption, Noise XX handshakes, full media support, interactive messages, newsletters, groups, communities, and a high-throughput event-driven architecture.

---

## Table of Contents

- [Features](#-features)
- [Installation](#-installation)
- [Architecture & Modular Services](#-architecture--modular-services)
- [Quick Start](#-quick-start)
  - [1. QR Code Pairing](#1-qr-code-pairing)
  - [2. Mobile Pairing Code (8-Digit)](#2-mobile-pairing-code-8-digit)
  - [3. Listening & Replying to Messages](#3-listening--replying-to-messages)
- [Event System (`WaEventBus`)](#-event-system-waeventbus)
- [In-Memory Store (`InMemoryStore`)](#-in-memory-store-inmemorystore)
- [Sending Messages](#-sending-messages)
  - [Standard Messages & Quotes](#standard-messages--quotes)
  - [Interactive Messages (Buttons, Lists, Carousels)](#interactive-messages-buttons-lists-carousels)
  - [Polls, Quizzes & Payments](#polls-quizzes--payments)
  - [Media: Images, Audio, Video, Documents & Stickers](#media-images-audio-video-documents--stickers)
- [Group & Community Management](#-group--community-management)
- [Channels / Newsletters](#-channels--newsletters)
- [Privacy, Profile & Settings](#-privacy-profile--settings)
- [Running the Showcase Bot](#-running-the-showcase-bot)
- [Logging & Configuration](#-logging--configuration)
- [Contributing & License](#-contributing--license)

---

## ⚡ Features

| Category | Supported Capabilities |
| :--- | :--- |
| **Authentication** | Noise XX protocol handshake, persistent multi-device sessions, QR Code terminal generation, 8-digit phone pairing code. |
| **End-to-End Encryption** | Pure Java Signal Protocol implementation (Curve25519, AES-CBC/CTR/GCM, HKDF, HMAC-SHA256, Sender Keys, Pre-Keys). |
| **Interactive Messaging** | CTA Action buttons (URL, Call, Copy Code), Dropdown List Pickers, Multi-Card Carousel Sliders, Interactive Polls & Quizzes V5. |
| **Rich Media** | JPEG/PNG Images, Opus Voice Notes (PTT), MP4 Video, PDF/Raw Documents, WebP Stickers, Multi-Image Albums, View-Once Media, and Media Decryption/Download. |
| **Group Administration** | Create group, invite links, join request approvals, participant add/remove/promote/demote, announce & lock toggles, disappearing message timer. |
| **Communities** | Create communities, query linked sub-groups, link/unlink groups to communities. |
| **Newsletters / Channels** | Follow/unfollow, mute/unmute, search metadata, react to updates, broadcast channel messages with newsletter media. |
| **Event Bus** | Full 33 user-facing Baileys-compatible events (`messages.upsert`, `connection.update`, `presence.update`, `groups.update`, `call`, `newsletter`, etc.). |
| **State & Privacy** | `InMemoryStore` for chats, contacts, messages, labels; Privacy settings (last seen, online, profile pic, read receipts), contact blocklist, Story status updates. |

---

## 📦 Installation

Add `Ja-Wa` to your project build configuration:

### Maven

```xml
<dependency>
    <groupId>io.github.innovatorssoft</groupId>
    <artifactId>Ja_Wa</artifactId>
    <version>0.0.4</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.innovatorssoft:Ja_Wa:0.0.4'
```

> **Requirements:** Java 21 or higher (LTS recommended).

---

## 🏛️ Architecture & Modular Services

`Ja-Wa` separates concerns into domain-scoped sub-services accessible through `client`:

```text
id.ja_wa
├── client/                     Primary Client Facade & Modular Sub-Services
│   ├── JaWaClient.java         Core connection, lifecycle & unified facade
│   ├── ChatService.java        Chat-level mutations, archiving, pinning & read receipts
│   ├── GroupService.java       Group & Community administration APIs
│   ├── MediaService.java       Media uploads, downloads & streaming
│   ├── MessagingService.java   E2EE messaging, buttons, templates & albums
│   ├── NewsletterService.java  Newsletter channels CRUD, following & posting
│   ├── PrivacyService.java     Account privacy toggles, blocklists & read receipts
│   └── ProfileService.java     Profile pictures, bio status & business catalog
├── protocol/                   Wire Protocol, Codecs & Cryptography
│   ├── connection/             WebSocket, Noise XX Handshake & FrameSocket
│   ├── codec/                  WhatsApp Binary XML Node Encoder / Decoder
│   └── crypto/                 Signal primitives (Curve25519, HKDF, AES, HMAC)
├── domain/                     Core Models & Storage
│   ├── model/                  JID, Auth Credentials, Receipts & Constants
│   └── store/                  File & SQLite session stores
├── event/                      Event Bus & Baileys Event Types
└── feature/                    Subsystems (pairing, appstate sync, store)
```

### Accessing Sub-Services

```java
// Messaging operations (text, media, buttons, templates)
client.messages().sendText(chatJid, "Hello from Ja-Wa!");

// Group & Community management
client.groups().getGroupMetadata(groupJid);

// Media processing
client.media().sendImage(chatJid, imageBytes, "image/jpeg", "Photo caption");

// Newsletters / Broadcast Channels
client.newsletters().sendText(channelJid, "New update!");

// Account privacy & security
client.privacy().updateLastSeenPrivacy("contacts");

// Profile & bio
client.profile().updateStatus("Building with Ja-Wa 🚀");
```

---

## 🚀 Quick Start

### 1. QR Code Pairing

```java
import id.ja_wa.client.JaWaClient;
import id.ja_wa.domain.store.FileAuthStore;
import id.ja_wa.feature.pairing.QrTerminal;
import java.nio.file.Path;
import java.util.List;

public class App {
    public static void main(String[] args) throws Exception {
        FileAuthStore store = new FileAuthStore(Path.of("sessions/bot.session"));
        Path signalDir = Path.of("sessions/bot.signal");

        JaWaClient client = new JaWaClient(store, signalDir)
                .autoReconnect(true);

        client.listener(new JaWaClient.Listener() {
            @Override
            public void onQr(List<String> qrs) {
                // Renders clean QR code directly into the terminal
                QrTerminal.print(qrs.get(qrs.size() - 1));
            }

            @Override
            public void onPaired(String jid, String pushName, String platform) {
                System.out.println("✅ Paired successfully: " + jid + " (" + pushName + ")");
            }
        });

        client.connect();
    }
}
```

### 2. Mobile Pairing Code (8-Digit)

Pair without needing a camera by requesting an 8-digit alphanumeric code:

```java
client.listener(new JaWaClient.Listener() {
    @Override
    public void onQr(List<String> qrs) {
        // Request an 8-digit code for your phone number
        client.requestPairingCode("1234567890", null).whenComplete((code, err) -> {
            if (err == null) {
                System.out.println("🔑 Pairing Code: " + code.substring(0, 4) + "-" + code.substring(4));
            }
        });
    }
});
```

### 3. Listening & Replying to Messages

```java
client.listener(new JaWaClient.Listener() {
    @Override
    public void onInboundMessage(id.ja_wa.feature.messaging.MessageReceiver.Decoded msg) {
        String from = msg.senderJid();
        String text = msg.text();

        System.out.println("📩 Inbound message from " + from + ": " + text);

        if (".ping".equalsIgnoreCase(text)) {
            client.messages().sendText(from, "🏓 Pong! Ja-Wa is active.");
        }
    }
});
```

---

## 📡 Event System (`WaEventBus`)

Ja-Wa includes a reactive event bus supporting 33 Baileys-equivalent event dispatches. Listeners can be typed or generic:

```java
import id.ja_wa.event.*;

// Register a typed listener
client.addEventListener(new WaEventListener() {
    @Override
    public void onMessageUpsert(MessageUpsert upsert) {
        for (var msg : upsert.messages()) {
            System.out.println("New message received: " + msg.msgId() + " from " + msg.senderJid());
        }
    }

    @Override
    public void onConnectionUpdate(ConnectionUpdate update) {
        System.out.println("Connection state: " + update.connection());
    }

    @Override
    public void onGroupParticipantsUpdate(GroupParticipantsUpdate update) {
        System.out.println("Group " + update.groupId() + ": action " + update.action() + " for " + update.participants());
    }

    @Override
    public void onPresenceUpdate(PresenceUpdate update) {
        System.out.println("User " + update.userJid() + " is " + update.presence());
    }
});
```

### Non-Blocking Awaiting

Wait for connection state transitions or media updates asynchronously:

```java
// Wait up to 10 seconds for OPEN connection
CompletableFuture<ConnectionUpdate> connFuture = client.waitForConnectionUpdate(
    u -> u.connection() == ConnectionUpdate.ConnectionState.OPEN, 
    10000
);
```

---

## 💾 In-Memory Store (`InMemoryStore`)

Cache chats, contacts, messages, labels, and group metadata locally with built-in JSON persistence:

```java
import id.ja_wa.feature.store.InMemoryStore;
import java.nio.file.Path;

InMemoryStore store = client.store();

// Bind store to client event bus for automatic state synchronisation
store.bind(client.eventBus());

// Save and load state
client.saveStore(Path.of("sessions/store.json"));
client.loadStore(Path.of("sessions/store.json"));

// Query store state
store.chats().all().forEach(chat -> System.out.println("Chat: " + chat.id()));
```

---

## ✉️ Sending Messages

### Standard Messages & Quotes

```java
// Plain text
client.messages().sendText(chatJid, "Hello from Ja-Wa!");

// Quoted reply
client.reply(chatJid, "Replying to your message", targetDecodedMessage);

// Mentions
client.messages().sendMention(chatJid, "Hello @1234567890", List.of("1234567890@s.whatsapp.net"));

// Spoiler text
client.messages().sendSpoiler(chatJid, "Warning", "This is hidden spoiler text");

// Code formatting
client.messages().sendRichCode(chatJid, "java", List.of(
    "public class Hello {",
    "    public static void main(String[] args) {",
    "        System.out.println(\"Hello WhatsApp!\");",
    "    }",
    "}"
));
```

### Interactive Messages (Buttons, Lists, Carousels)

#### 1. Call-To-Action (CTA) Buttons
```java
import id.ja_wa.feature.messaging.MessageEncoder.CtaButton;

client.messages().sendCtaButtons(
    chatJid,
    "Explore Ja-Wa framework features below:",
    "Ja-Wa Bot • Documentation",
    List.of(
        CtaButton.url("🌐 GitHub Repository", "https://github.com/innovatorssoft/wa-java"),
        CtaButton.call("📞 Support Call", "+1234567890"),
        CtaButton.copy("📋 Copy Voucher", "JAWA-PROMO-2026")
    )
);
```

#### 2. Dropdown List Picker
```java
import id.ja_wa.feature.messaging.MessageEncoder.ListSection;
import id.ja_wa.feature.messaging.MessageEncoder.ListRow;

client.messages().sendList(
    chatJid,
    "📋 Service Menu",
    "Select an option from the menu:",
    "Ja-Wa Assistant",
    "Open Menu",
    List.of(
        new ListSection("Main Services", List.of(
            new ListRow("opt_balance", "💳 Check Balance", "View account balance"),
            new ListRow("opt_support", "💬 Contact Agent", "Connect to live support")
        ))
    )
);
```

#### 3. Multi-Card Carousel Slider
```java
import id.ja_wa.client.JaWaClient.CarouselCardInput;

List<CarouselCardInput> cards = List.of(
    new CarouselCardInput("Card 1", "Fast and Lightweight", imageBytes1, "image/jpeg", List.of(
        CtaButton.url("Learn More", "https://github.com/innovatorssoft/wa-java")
    )),
    new CarouselCardInput("Card 2", "Pure Java 21", imageBytes2, "image/jpeg", List.of(
        CtaButton.copy("Copy Library Name", "Ja-Wa")
    ))
);

client.sendCarousel(chatJid, "🚀 Features Showcase", "Swipe through the cards:", cards);
```

### Polls, Quizzes & Payments

```java
// Interactive Voting Poll
client.messages().sendPoll(
    chatJid, 
    "Which feature do you like most?", 
    List.of("Performance", "Simplicity", "Baileys Parity"), 
    1 // Selectable count
);

// Interactive Quiz (V5)
client.messages().sendQuiz(
    chatJid,
    "What is the latest Java LTS?",
    List.of("Java 8", "Java 17", "Java 21"),
    2 // Correct option index (Java 21)
);

// Payment Request
client.messages().sendPaymentRequest(chatJid, "USD", 150000, "Monthly Subscription");
```

### Media: Images, Audio, Video, Documents & Stickers

```java
// Image
client.media().sendImage(chatJid, imageBytes, "image/jpeg", "Optional caption");

// Audio / Voice Note (PTT)
client.media().sendAudio(chatJid, audioBytes, "audio/ogg; codecs=opus", true);

// Video
client.media().sendVideo(chatJid, videoBytes, "video/mp4", "Watch this preview");

// PDF Document
client.media().sendDocument(chatJid, pdfBytes, "application/pdf", "Invoice.pdf");

// WebP Sticker
client.media().sendSticker(chatJid, stickerBytes, false);

// Download Media from Inbound Message
byte[] downloadedBytes = client.downloadMedia(decodedMsg.message());
```

---

## 👥 Group & Community Management

```java
// Create a new group
CompletableFuture<String> groupFuture = client.groups().createGroup(
    "Developers Hub", 
    List.of("1234567890@s.whatsapp.net", "9876543210@s.whatsapp.net")
);

// Invite link
client.groups().getGroupInviteLink(groupJid).thenAccept(link -> {
    System.out.println("Invite URL: " + link);
});

// Revoke invite link
client.groups().revokeGroupInviteLink(groupJid);

// Admin controls: Add, Remove, Promote, Demote
client.groups().promoteParticipants(groupJid, List.of("1234567890@s.whatsapp.net"));
client.groups().demoteParticipants(groupJid, List.of("1234567890@s.whatsapp.net"));
client.groups().removeParticipants(groupJid, List.of("1234567890@s.whatsapp.net"));

// Group configuration toggles
client.groups().setGroupAnnounce(groupJid, true); // Only admins can send messages
client.groups().setGroupLocked(groupJid, true);   // Only admins can edit group info
client.groups().setDisappearingTimer(groupJid, 86400); // 24 hours disappearing timer

// Communities
client.groups().createCommunity("Engineering Org", "Community description");
client.groups().getSubGroups(communityJid);
```

---

## 📢 Channels / Newsletters

```java
// Create a broadcast newsletter channel
client.newsletters().createNewsletter("Tech Updates", "Daily tech news")
    .thenAccept(meta -> System.out.println("Created Channel JID: " + meta.id()));

// Query metadata & subscribed list
client.newsletters().getSubscribedNewsletters();
client.newsletters().getNewsletterInfo(channelJid);

// Follow / Unfollow / Mute
client.newsletters().followNewsletter(channelJid);
client.newsletters().unfollowNewsletter(channelJid);
client.newsletters().muteNewsletter(channelJid);

// Broadcast update to channel
client.newsletters().sendText(channelJid, "📢 Major Release 1.0 is now live!");
```

---

## 🔒 Privacy, Profile & Settings

```java
// Privacy toggles
client.privacy().updateLastSeenPrivacy("contacts");       // 'all', 'contacts', 'contact_blacklist', 'none'
client.privacy().updateOnlinePrivacy("all");              // 'all', 'match_last_seen'
client.privacy().updateProfilePicturePrivacy("contacts");
client.privacy().updateReadReceiptsPrivacy("none");       // disable blue ticks

// Blocking
client.privacy().blockContact("1234567890@s.whatsapp.net");
client.privacy().unblockContact("1234567890@s.whatsapp.net");
client.privacy().getBlocklist();

// Profile bio & picture
client.profile().updateStatus("Building with Ja-Wa 🚀");
client.profile().updateProfilePicture(imageBytes);
client.profile().removeProfilePicture();

// Presence (typing / recording)
client.messages().sendChatPresence(chatJid, ChatPresence.COMPOSING);
client.messages().sendChatPresence(chatJid, ChatPresence.RECORDING);
client.messages().sendChatPresence(chatJid, ChatPresence.PAUSED);

// WhatsApp Story Status
client.postStatusStory("Status update posted via Ja-Wa Bot!");

// WhatsApp Call Link
client.createCallLink(false); // false for voice call, true for video
```

---

## 🤖 Running the Showcase Bot

A full-featured showcase bot demonstrating all available features is available in [`examples/id/ja_wa/SimpleBot.java`](examples/id/ja_wa/SimpleBot.java).

### Compile the Project

Compile all 172 source files cleanly with JDK 21 using the included batch file:

```bat
compile.bat
```

Or in PowerShell:

```powershell
.\scratch\compile_all.ps1
```

### Start the Bot

```bat
run_bot.bat
```

Or in PowerShell:

```powershell
.un_bot.ps1
```

Scan the terminal QR code or request an 8-digit link code to pair. Once connected, send `.menu` to view all available commands.

---

## ⚙️ Logging & Configuration

By default, internal wire protocol frame logs and debug stanzas are suppressed to keep console output clean. Only bot banners, inbound messages, and outbound replies appear in the terminal.

To enable verbose protocol debugging for troubleshooting, run with:

```powershell
java -Djawa.log=DEBUG -cp target\classes id.ja_wa.SimpleBot
```

You can customize runtime properties:
- `-Djawa.log=DEBUG|INFO|WARN|ERROR` - Set log level
- `-Djawa.session=sessions/my.session` - Path to credentials file
- `-Djawa.owner=1234567890` - Bot owner phone number

---

## 📄 Contributing & License

Contributions, bug reports, and pull requests are warmly welcome!

- **Repository:** [https://github.com/innovatorssoft/wa-java](https://github.com/innovatorssoft/wa-java)
- **Organization:** [Innovators soft](https://github.com/innovatorssoft)
- **Email:** [innovatorssoft7@gmaile.com](mailto:innovatorssoft7@gmaile.com)

Distributed under the **GNU General Public License v3.0 or later** (GPL-3.0-or-later). See [`LICENSE`](LICENSE) for details.
