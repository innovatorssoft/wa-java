# wa-java Guide — Baileys-port features

This guide covers the features ported from Baileys (`baileys`) into
wa-java, across Increments 1–10c. It is written for a developer who already
has a wa-java checkout on the `baileys-port` branch and wants to use the
ported surface, not re-implement it.

> **How to read the examples.** Every class, method, enum, and record
> component named in a code block below was checked against the source in
> `src/main/java/` before this guide was written. Where a method's full
> parameter list is long or varies by overload, the guide shows the
> overload that was verified and says so; it does not invent overloads.
> All code blocks are Java. Futures are `java.util.concurrent`
> `CompletableFuture`s: call `.join()` in a short example, or
> `.thenAccept(...)` / `.exceptionally(...)` in real code — never ignore a
> failed future silently, because several send paths fail the future
> rather than throwing synchronously.
>
> **Scope note.** The port is complete as far as technically feasible
> without a live WhatsApp connection (see
> [Not yet / not ported / needs live verification](#11-not-yet--not-ported--needs-live-verification)).
> Behaviour that still needs a live connection is marked **live-gated**
> wherever it appears. Nothing in this guide describes a stub as working.

Contents:

1. [Quick start](#1-quick-start)
2. [Event system](#2-event-system)
3. [InMemoryStore](#3-inmemorystore)
4. [Sending messages](#4-sending-messages)
5. [Groups, communities, newsletters, contacts, labels, catalog and business](#5-groups-communities-newsletters-contacts-labels-catalog-and-business)
6. [Presence, receipts, reactions, edits, revokes, media retry](#6-presence-receipts-reactions-edits-revokes-media-retry)
7. [App-state, history sync, mex, peer/PDO and decrypt-failure placeholders](#7-app-state-history-sync-mex-peerpdo-and-decrypt-failure-placeholders)
8. [Privacy, blocking, profile and picture, connection lifecycle](#8-privacy-blocking-profile-and-picture-connection-lifecycle)
9. [Media: upload, download, newsletter media](#9-media-upload-download-newsletter-media)
10. [Calls, status, and miscellaneous queries](#10-calls-status-and-miscellaneous-queries)
11. [Not yet / not ported / needs live verification](#11-not-yet--not-ported--needs-live-verification)

Conventions used throughout:

- A **JID** is a full WhatsApp address such as `923001234567@s.whatsapp.net`
  (a user), `120207...@g.us` (a group), `120207...@newsletter` (a channel),
  or `status@broadcast` (status). A **bare JID** is the same address without
  a device suffix.
- `client` below is a `JaWaClient` (see §1). The service facades are
  reached through it: `client.messages()`, `client.groups()`,
  `client.media()`, `client.newsletters()`, `client.profile()`,
  `client.chats()`, and `client.privacy()`.

---

## 1. Quick start

### 1.1 Create a client and connect

```java
import id.jawa.client.JaWaClient;
import id.jawa.domain.store.FileAuthStore;

import java.nio.file.Path;

JaWaClient client = new JaWaClient(new FileAuthStore(Path.of("auth.json")));
```

`JaWaClient(AuthStore)` is the primary constructor. There is also
`JaWaClient(AuthStore, Path)` where the second argument is a directory for
file-based Signal keys (or `null` for in-memory keys). Credentials are
loaded on `connect()`:

- No stored credentials, or stored credentials with no account, means the
  client starts in **pairing** mode (QR or pairing code, §1.2).
- Stored paired credentials mean the client starts in **login** mode.

```java
client.connect();   // throws Exception on network/handshake failure
client.join();      // blocks until the client closes
```

`connect()` throws `IllegalStateException("already connected")` if called
twice on the same client. It starts a virtual reader thread and a
keep-alive ping (every 20–30 seconds). To shut down cleanly:

```java
client.close();       // also available as client.disconnect()
```

`close()` is idempotent: a second call is a no-op. `join()` waits on the
close latch, so a typical `main` ends with `client.join()` and closes the
client from another thread, a shutdown hook, or a signal handler.

### 1.2 Pairing: QR or pairing code

There are two pairing surfaces, and they are not interchangeable after
pairing has happened.

**QR pairing (the legacy listener).** Register the legacy single listener
before connecting. It is deliberately unchanged by the port, and it fires
alongside the event bus (§2):

```java
import id.jawa.client.JaWaClient;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;

client.listener(new JaWaClient.Listener() {
    @Override
    public void onQr(List<String> qrStrings) {
        // One call carries the QR refs wa-java delivers as a list.
        // Render each string (for example with QrTerminal.render, below).
        for (String qr : qrStrings) {
            System.out.println(qr);
        }
    }

    @Override
    public void onPaired(String jid, String pushName, String platform) {
        System.out.println("Paired as " + jid + " (" + pushName + ")");
    }

    @Override
    public void onConnected() {
        System.out.println("Connected");
    }

    @Override
    public void onMessage(MessageReceiver.Decoded decoded) {
        System.out.println("Message " + decoded.msgId() + " from " + decoded.senderJid());
    }

    @Override
    public void onError(Throwable t) {
        t.printStackTrace();
    }
});
```

`QrTerminal.render(String)` (in `id.jawa.feature.pairing`) renders one QR
string as terminal text; `render(String, int)` takes an explicit quiet-zone
size. The legacy listener's other callbacks are `onReceipt(Receipt)`,
`onAppStateKeysReceived(int)`, `onAppStateMutations(PatchName, ...)`,
`onTerminated(TerminationReason, String, boolean)`, `onAck(BinaryNode)`,
and `onStanza(BinaryNode)` — implement only the ones you need; every
method has a no-op default.

**Pairing-code pairing.** After `connect()`, while the client is still
unpaired, ask for a code for a phone number:

```java
String code = client.requestPairingCode("62812345678", null).join();
System.out.println("Pairing code: " + code);
```

`requestPairingCode(String phoneNumber, String customCode)` returns a
future of the code string. It throws `IllegalStateException` immediately
if the client is already paired (`creds.account` is set), and fails the
future if the server rejects the companion hello. Pass `null` for
`customCode` unless you have a specific code to request.

### 1.3 Minimal working skeleton

This is the smallest program that connects, prints connection updates and
inbound messages, and echoes text back. It deliberately uses only
verified APIs:

```java
import id.jawa.client.JaWaClient;
import id.jawa.domain.store.FileAuthStore;
import id.jawa.event.ConnectionUpdate;
import id.jawa.event.MessageUpsert;
import id.jawa.event.WaEventListener;
import id.jawa.feature.messaging.MessageReceiver;

import java.nio.file.Path;

public class EchoBot {
    public static void main(String[] args) throws Exception {
        JaWaClient client = new JaWaClient(new FileAuthStore(Path.of("auth.json")));

        client.addEventListener(new WaEventListener() {
            @Override
            public void onConnectionUpdate(ConnectionUpdate update) {
                System.out.println("connection: " + update.connection()
                        + " qr=" + (update.qr() != null)
                        + " newLogin=" + update.isNewLogin());
            }

            @Override
            public void onMessageUpsert(MessageUpsert upsert) {
                for (MessageReceiver.Decoded decoded : upsert.messages()) {
                    System.out.println("inbound: " + decoded.msgId()
                            + " from " + decoded.senderJid());
                    // Echo only plain inbound text; see §4 for the
                    // Decoded model and §6 for replies/reactions.
                    client.sendText(decoded.senderJid(), "echo").exceptionally(t -> {
                        t.printStackTrace();
                        return null;
                    });
                }
            }
        });

        client.connect();
        client.join();
    }
}
```

Two warnings about the skeleton, because both surprise people:

- `onMessageUpsert` fires for **every** inbound message the client decodes,
  including your own messages relayed from other devices in some flows.
  Filter on the decoded message before acting on it (see §4.1).
- `sendText(String, String)` returns a future of the sent message id. The
  skeleton above does not wait for it; in production, handle the failure
  branch, because a failed send is reported through the future, not by
  an exception from `sendText` itself.

### 1.4 Tuning the client before connecting

These setters all return the client, so they chain before `connect()`:

```java
client
    .autoReconnect(true)            // default true; reconnect after a drop
    .autoReadReceipt(false)         // default is whatever the client sets;
                                    // false stops automatic read receipts
    .autoComposingPresence(false)   // automatic typing presence
    .autoPreKeyUpload(true)         // upload pre-keys on connect
    .syncVersion(true)              // fetch the latest WA version first
    .autoEphemeral(false)
    .autoIgnoreOfflineMessages(false)
    .offlineMessageGracePeriodSec(30);
```

Read the current values back with `client.autoReconnect()`,
`client.autoReadReceipt()`, `client.autoComposingPresence()`,
`client.autoPreKeyUpload()`, `client.syncVersion()`,
`client.isAutoEphemeral()`, `client.isAutoIgnoreOfflineMessages()`, and
`client.offlineMessageGracePeriodSec()`. `client.getConnectedAtEpochSec()`
returns the connection time in epoch seconds (0 when disconnected).
`client.wsUrl(String)` overrides the WebSocket URL; `client.wsUrl()`
reads it back. None of these settings is a substitute for handling
`connection.update` (§2.3): auto-reconnect changes what happens after a
drop, it does not hide the drop from your listener.

---

## 2. Event system

The port adds a Baileys-shaped event model in `id.jawa.event`, alongside
— not instead of — the legacy single `JaWaClient.Listener` (§1.2). The
bus is synchronous, per-listener failures are isolated (one listener
throwing does not stop the next), and dispatch never throws out of the
client's reader loop: a dispatch failure is logged, not propagated.

### 2.1 Subscribing

```java
import id.jawa.event.WaEventBus;
import id.jawa.event.WaEventListener;

WaEventBus bus = client.eventBus();

WaEventListener listener = new WaEventListener() {
    // Override only what you need; every method is a default no-op.
};

client.addEventListener(listener);      // chaining; returns the client
client.removeEventListener(listener);   // unregisters it

bus.listenerCount();                    // how many listeners are registered
bus.listeners();                        // an unmodifiable view of them
```

You can also dispatch manually, which is useful in tests and in adapters
that translate another source into Baileys events:

```java
import id.jawa.event.BaileysEventType;
import id.jawa.event.ConnectionUpdate;

bus.dispatch(BaileysEventType.CONNECTION_UPDATE, ConnectionUpdate.open());
```

`WaEventBus.dispatch(BaileysEventType, Object)` calls the typed listener
method for the event, then the generic fallback, for each listener.
The generic fallback is:

```java
@Override
public void onEvent(BaileysEventType type, Object payload) {
    // Fires for every event, after the typed method, if you override it.
}
```

Use the typed methods when you know the event, and `onEvent` for logging,
metrics, or forwarding everything to another system. Do not rely on
`onEvent` alone to learn payload shapes: the typed method's parameter
type *is* the payload contract.

A second, lower-level waiting primitive exists for connection state:

```java
import id.jawa.event.ConnectionUpdate;

ConnectionUpdate matched = client
    .waitForConnectionUpdate(
        u -> u.connection() == ConnectionUpdate.ConnectionState.OPEN,
        30_000)
    .join();
// matched is null on timeout (it does not fail the future), and a
// close update is delivered to the predicate like any other update.
```

`waitForConnectionUpdate(Predicate<ConnectionUpdate>, long)` differs
from Baileys in two documented ways: it completes with the matching
update (Baileys resolves void), and on timeout it completes with `null`
instead of failing (Baileys rejects on timeout and on close).

### 2.2 The 34 events

`BaileysEventType` has one constant per Baileys event name, each carrying
its exact Baileys string via `baileysName()`. `fromName(String)` returns
the constant for a name, or `null` for an unknown name — it never invents
one. The 34 constants are 33 user-facing events plus the internal
`EVENT` fan-out, which the client deliberately does **not** re-emit
(re-emitting it would double-deliver).

| Baileys name | Listener method | Payload record |
|---|---|---|
| `connection.update` | `onConnectionUpdate` | `ConnectionUpdate` |
| `creds.update` | `onCredsUpdate` | `CredsUpdate` |
| `messaging-history.set` | `onMessagingHistorySet` | `MessagingHistorySet` |
| `messaging-history.status` | `onMessagingHistoryStatus` | `MessagingHistoryStatus` |
| `chats.upsert` | `onChatsUpsert` | `ChatsUpsert` |
| `chats.update` | `onChatsUpdate` | `ChatsUpdate` |
| `chats.delete` | `onChatsDelete` | `ChatsDelete` |
| `chats.lock` | `onChatsLock` | `ChatsLock` |
| `lid-mapping.update` | `onLidMappingUpdate` | `LidMappingUpdate` |
| `presence.update` | `onPresenceUpdate` | `PresenceUpdate` |
| `contacts.upsert` | `onContactsUpsert` | `ContactsUpsert` |
| `contacts.update` | `onContactsUpdate` | `ContactsUpdate` |
| `messages.upsert` | `onMessageUpsert` | `MessageUpsert` |
| `messages.update` | `onMessagesUpdate` | `MessagesUpdate` |
| `messages.delete` | `onMessagesDelete` | `MessagesDelete` |
| `messages.media-update` | `onMessagesMediaUpdate` | `MessagesMediaUpdate` |
| `messages.reaction` | `onMessagesReaction` | `MessagesReaction` |
| `message-receipt.update` | `onMessageReceiptUpdate` | `MessageReceiptUpdate` |
| `groups.upsert` | `onGroupsUpsert` | `GroupsUpsert` |
| `groups.update` | `onGroupsUpdate` | `GroupsUpdate` |
| `group-participants.update` | `onGroupParticipantsUpdate` | `GroupParticipantsUpdate` |
| `group.join-request` | `onGroupJoinRequest` | `GroupJoinRequest` |
| `group.member-tag.update` | `onGroupMemberTagUpdate` | `GroupMemberTagUpdate` |
| `blocklist.update` | `onBlocklistUpdate` | `BlocklistUpdate` |
| `call` | `onCall` | `CallEvent` |
| `labels.edit` | `onLabelsEdit` | `LabelsEdit` |
| `labels.association` | `onLabelsAssociation` | `LabelsAssociation` |
| `newsletter.reaction` | `onNewsletterReaction` | `NewsletterReaction` |
| `newsletter.view` | `onNewsletterView` | `NewsletterView` |
| `newsletter-participants.update` | `onNewsletterParticipantsUpdate` | `NewsletterParticipantsUpdate` |
| `newsletter-settings.update` | `onNewsletterSettingsUpdate` | `NewsletterSettingsUpdate` |
| `settings.update` | `onSettingsUpdate` | `SettingsUpdate` |
| `message-capping.update` | `onMessageCappingUpdate` | `MessageCappingUpdate` |
| `event` (internal fan-out) | `onEvent` only | — |

Payload notes that matter in practice:

- `message-capping.update` carries a single raw JSON string. The Baileys
  tree yields no field list for it, so wa-java invents none: parse the
  JSON yourself if you need fields out of it.
- `chats.update` carries no function field. Baileys' internal
  `conditional` function is stripped, never serialised.
- `ContactsUpdate.Contact` has a component named `notifyName`, not
  `notify`: `notify()` is a final method of `java.lang.Object` and
  cannot be a record component. It is the Baileys `notify` field.
- `MessageUpsert` is `(messages, type, requestId)`. `type` is `"notify"`
  on the client's inbound path; `requestId` is non-null only for
  peer-data-operation response upserts (§7.4), where it is
  `response.stanzaId`. A two-argument constructor `(messages, type)`
  exists and defaults `requestId` to `null`.
- `MessagesUpdate` carries a list of `MessageUpdate` entries, each with
  `messageId`, `remoteJid`, `fromMe`, `participant`, `status`,
  `messageTimestamp`, `starred`, plus the trailing nullable
  `groupInviteMessage` (community V4 invite-accept expiry only) and
  `messageStubParameters` (decrypt-failure PDO success only, §7.5).
  Older constructors delegate with those trailing fields `null`.
- `MessageReceiptUpdate` wraps the existing `Receipt` model
  (`type`, `chatJid`, `senderJid`, `recipientJid`, `msgIds`,
  `timestamp` in epoch seconds). There is no separate message-key
  field: the key is available through the receipt's own fields.

### 2.3 Which events come from where

This is the distinction the guide must not blur, because it decides
whether your handler will ever fire:

- **Dispatched from `JaWaClient` itself: 30 of 33 user-facing events.**
  These fire from real protocol/client data as the client reads it:
  connection and creds updates, history set/status, presence, calls,
  chats upsert/update, contacts update, messages upsert/update/reaction/
  media-update, message receipts, group upserts/updates/participants/
  join-requests/member-tags, blocklist updates, LID-mapping updates,
  label edits and associations, newsletter reaction/view/participants/
  settings, settings updates, and message-capping updates.
- **Dispatched only by a bound `InMemoryStore`: 3 events.**
  `chats.delete`, `contacts.upsert`, and `messages.delete` have no
  client dispatch path. They fire only when a store bound to a bus
  (§3.1) performs the corresponding mutation. If you never bind a
  store, you will never see them — that is by design, not a bug.
- **Every user-facing event therefore has at least one real dispatch
  path** (30 from the client + 3 from a bound store), but the
  client-only figure is 30/33. Code that must observe all 33 events
  must bind a store.

The client's own store (§3) is deliberately **unbound** by default, so
that inbound traffic feeding the store does not double-emit events the
client already dispatches. If you bind the client's store to the same
bus yourself, expect duplicate deliveries for the events both paths
emit (for example `chats.upsert` / `contacts.update` /
`messages.reaction`); dedupe at your consumer if you do that.

### 2.4 Partial payloads: merge, don't replace

Baileys emits **partials**, and wa-java preserves that semantics.
`ConnectionUpdate` and `CredsUpdate` are the two places this bites.

```java
import id.jawa.event.ConnectionUpdate;

// WRONG: replacing your whole connection state with one update.
ConnectionState state = null; // your own model
// state = update;             // do not do this

// RIGHT: merge each non-null field into your stored state.
ConnectionUpdate.ConnectionState connection = null;
String qr = null;
Boolean isNewLogin = null;
Boolean isOnline = null;
String lastDisconnectError = null;

void apply(ConnectionUpdate update) {
    if (update.connection() != null) connection = update.connection();
    if (update.qr() != null) qr = update.qr();
    if (update.isNewLogin() != null) isNewLogin = update.isNewLogin();
    if (update.isOnline() != null) isOnline = update.isOnline();
    if (update.lastDisconnectError() != null) {
        lastDisconnectError = update.lastDisconnectError();
    }
}
```

The `ConnectionUpdate` fields are: `connection` (`OPEN` / `CONNECTING`
/ `CLOSE` / `UNKNOWN`), `qr` (present only during pairing, otherwise
`null`), `receivedPendingNotifications`, `isNewLogin`, `isOnline`,
`lastDisconnectError` (a diagnostic string), `lastDisconnectDateEpochMs`,
and `reachoutTimeLock` (a nested record: `isActive`,
`timeEnforcementEndsEpochMs`, `enforcementType`). Factories exist for
the common shapes: `ConnectionUpdate.open()`,
`ConnectionUpdate.connecting()`, `ConnectionUpdate.qr(String)`,
`ConnectionUpdate.isNewLogin(boolean)`, and
`ConnectionUpdate.close(String)` / `close(String, Long)`. A QR update
is emitted once per QR ref, and a pairing success emits
`CREDS_UPDATE` plus a `CONNECTION_UPDATE` carrying only
`isNewLogin=true`. A close update is emitted from every termination
site, with the termination detail as `lastDisconnectError`.

`CredsUpdate` is likewise polymorphic in Baileys: it carries either a
full creds object or a one-field partial. wa-java's record carries the
fields the client actually emits today — `meJid` and `pushName` (the
paired identity, via `CredsUpdate.paired(String, String)`), plus the
scalar partials `accountSyncCounter`, `lastAccountSyncTimestamp`
(epoch seconds), and `myAppStateKeyId`. Everything else stays
`null` until a dispatch site for it exists; the guide does not invent
fields Baileys would carry but wa-java does not emit yet. Merge on
receipt, exactly as for connection updates.

### 2.5 Calls

`CallEvent` is the payload for the `call` event. Its fields are
`chatId`, `from`, `callerPn`, `id`, `dateEpochSec`, `offline`,
`status`, `isVideo`, `isGroup`, `groupJid`, and `latencyMs`, with
`dateEpochMs()` defined as `dateEpochSec * 1000`. `CallStatus` is
`OFFER`, `ACCEPT`, `REJECT`, `TIMEOUT`, `TERMINATE`, `PREACCEPT`,
`TRANSPORT`, `RELAYLATENCY`, `RINGING`, or `UNKNOWN`. The mapping from
stanza children follows Baileys' `getCallStatusFromNode`: `offer` /
`offer_notice` → `OFFER`, `terminate` with `reason=timeout` → `TIMEOUT`
(otherwise `TERMINATE`), `preaccept` → `PREACCEPT`,
`transport` → `TRANSPORT`, `relaylatency` → `RELAYLATENCY`,
`reject` → `REJECT`, `accept` → `ACCEPT`, anything else → `RINGING`.
On an offer, `isVideo` is whether a `video` child is present, `isGroup`
is whether the info child is `type=group` or carries `group-jid`, and
later events for the same call id backfill those fields from the
cached offer; the cache entry is deleted on reject/accept/timeout/
terminate (after backfill).

One documented difference from Baileys: wa-java's call branch sends **no
ack** for call nodes (Baileys acks them after handling), and the
presence/call branches return `false` from stanza handling so the
legacy `onStanza` fallthrough still fires for existing consumers.

```java
import id.jawa.event.CallEvent;

@Override
public void onCall(CallEvent call) {
    System.out.println("call " + call.id() + " status=" + call.status()
            + " video=" + call.isVideo() + " group=" + call.isGroup());
    if (call.status() == CallStatus.OFFER) {
        // Decide whether to answer; wa-java exposes rejectCall (see §10).
        client.rejectCall(call.from(), call.id());
    }
}
```

`CallStatus` here is `id.jawa.event.CallStatus`; the snippet above
assumes the usual imports for it alongside `CallEvent`.

---

## 3. InMemoryStore

`InMemoryStore` (`id.jawa.feature.store`) is wa-java's equivalent of
Baileys' `makeInMemoryStore`: a rebuildable in-memory cache of chats,
messages, contacts, groups, presences, labels, LID mappings, and
reactions. It is **not** a database and it is **not** persisted unless
you ask (§3.4).

### 3.1 Getting a store, and binding it

```java
import id.jawa.feature.store.InMemoryStore;

InMemoryStore store = client.store();   // the client's own store
```

The client's own store is populated by the client's reader loop from
inbound traffic. It starts unbound (§2.3). To create a store you control
— for example one you feed yourself in tests, or one whose mutations
you want as events — construct and bind it:

```java
InMemoryStore myStore = new InMemoryStore();          // 500-message cap
InMemoryStore capped = new InMemoryStore(100);        // explicit cap
myStore.bind(client.eventBus());                      // subsequent mutations dispatch
myStore.bind(null);                                   // unbind again
```

`InMemoryStore(int maxMessagesPerChat)` requires a positive cap, and
`maxMessagesPerChat()` reads it back. The default is
`InMemoryStore.DEFAULT_MAX_MESSAGES_PER_CHAT`, which is **500**.

> **Double-delivery warning, repeated because it is the most common
> store mistake:** binding the *client's own* store to the client's bus
> will double-emit events the client already dispatches. Bind a
> separate store when you want store-driven events, or accept and
> dedupe duplicates at your consumer.

### 3.2 Chats and messages

```java
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.feature.store.InMemoryStore;

import java.util.List;

// Chats, in Baileys KeyedDB order (most-recent first by last message).
List<InMemoryStore.StoredChat> chats = store.chats();
InMemoryStore.StoredChat chat = store.getChat("62812345678@s.whatsapp.net");
int count = store.chatCount();

// Messages for a chat, in arrival order.
List<MessageReceiver.Decoded> all = store.messages(chatId);
List<MessageReceiver.Decoded> latest = store.latestMessages(chatId, 20);
MessageReceiver.Decoded one = store.getMessage(chatId, messageId);

// Before-only pagination (see below).
List<MessageReceiver.Decoded> page =
    store.loadMessages(chatId, 20, cursorMessageId);
```

`StoredChat` is a record with exactly five components: `id`,
`lastMessageTimestamp`, `unreadCount`, `pinned`, and `archived`.
`messages(chatId)` returns the chat's stored messages in
arrival order; `latestMessages(chatId, limit)` returns at most `limit`
of them, newest last.

**The 500-message cap.** When a chat exceeds its cap, the **oldest**
messages are dropped first. This is a documented adaptation: Baileys'
store grows until the caller evicts, whereas wa-java caps per chat so a
long-lived process cannot grow without bound. If you need full history,
page it out with `loadMessages` before it ages out, or persist the
store (§3.4) — but neither changes the cap itself.

**Before-only pagination.** `loadMessages(chatId, count, cursorMessageId)`
returns messages *before* the cursor message (exclusive), or the whole
history when the cursor is `null`, limited to the last `count` of that
range, in arrival order. There is deliberately no forward pagination:
Baileys' `after` branch always returns an empty list, and wa-java
mirrors that. A non-positive `count` returns an empty list, and a
cursor that is not found pages before the end of the list (the code
falls through to the full range) — so treat an unknown cursor as
"latest", not as an error.

Mutations that a bound store turns into events:

```java
store.markChatRead(chatId);                       // clears unread count
store.setChatFlags(chatId, true, false);          // pinned, archived
store.deleteChat(chatId);                         // -> chats.delete
store.clearChat(chatId);                          // clears messages, keeps chat
store.deleteMessage(chatId, msgId, false, participantJid);
                                                  // -> messages.delete (bound only)
```

`deleteMessage(chatId, messageId, fromMe, participant)` and
`applyReaction(chatId, messageId, fromMe, participant, code)` (below)
take the same key-shaped arguments as the Baileys key: id, remote JID
(the chat), `fromMe`, and participant (null for 1:1 chats).

### 3.3 Contacts, groups, presences, labels, LID mappings, reactions

```java
import id.jawa.event.ContactsUpsert;
import id.jawa.event.GroupsUpsert;
import id.jawa.event.PresenceUpdate;

// Contacts.
store.upsertContact(new ContactsUpsert.Contact(
    "62812345678@s.whatsapp.net", "Aisha", null, null, "62812345678"));
ContactsUpsert.Contact contact = store.getContact("62812345678@s.whatsapp.net");
List<ContactsUpsert.Contact> contacts = store.contacts();
String imgUrl = store.cachedContactImgUrl("62812345678@s.whatsapp.net");
store.updateContactImgUrl("62812345678@s.whatsapp.net", "https://…/pic.jpg");

// Groups (participant add/remove through the store's helper).
store.upsertGroup(groupInfo);                     // GroupsUpsert.GroupInfo
GroupsUpsert.GroupInfo group = store.getGroup("120207…@g.us");
List<GroupsUpsert.GroupInfo> groups = store.groups();
store.updateGroupParticipants(groupId, List.of("628…@s.whatsapp.net"), "add");
// action is Baileys' action string ("add" / "remove" in practice).

// Presences (merged, never emitted by the store itself).
store.upsertPresence(presenceUpdate);             // PresenceUpdate
Map<String, PresenceInfo> perChat = store.presences(chatId);
List<String> presenceChats = store.presenceChatIds();

// LID <-> phone-number mappings.
store.upsertLidMapping("123…@lid", "628…@s.whatsapp.net");
String pn = store.pnForLid("123…@lid");
Map<String, String> mappings = store.lidMappings();

// Reactions.
store.applyReaction(chatId, msgId, false, participantJid, "👍");
String code = store.reactionFor(chatId, msgId);
List<InMemoryStore.ReactionEntry> reactions = store.reactions();
```

Labels follow Baileys' **20-label cap**: new labels beyond 20 are
rejected (`upsertLabel` returns `false` for them) rather than evicting
an old one. The store-level label API is:

```java
store.upsertLabel(labelEdit);                     // LabelsEdit
LabelsEdit label = store.getLabel(labelId);
List<LabelsEdit> labels = store.labels();
store.applyLabelAssociation(association);         // LabelsAssociation
List<LabelsAssociation> forChat = store.chatLabels(chatId);
List<String> forMessage = store.messageLabels(messageId);
List<LabelsAssociation> all = store.allLabelAssociations();
```

`recordBlocklistChange(jid, blocked)` records a block/unblock in the
store and, when bound, dispatches `blocklist.update`. Blocklist
*server* operations are in §8.2 — the store method is only the local
cache side.

Finally, `store.messageCounts()` returns a per-chat message-count map,
`store.connectionState()` / `store.setConnectionState(String)` track a
plain connection-state string (the client dispatches
`connection.update` itself; the store does not), and
`store.replaceWith(InMemoryStore)` wholesale-replaces one store's
contents with another's while keeping the target's bus binding and
message cap — that is exactly what `client.loadStore(Path)` uses (§3.4).

### 3.4 Persistence: save and load explicitly

Nothing saves or loads the store automatically — not on mutation, not
on startup, not on shutdown. You call it:

```java
import java.nio.file.Path;

client.saveStore(Path.of("store.json"));          // atomic: sibling .tmp + move
boolean loaded = client.loadStore(Path.of("store.json"));
```

`saveStore(Path)` throws `IOException` if the file cannot be written.
`loadStore(Path)` returns `false` and changes nothing when the file
does not exist; an empty or corrupt file replaces the store with an
empty one (the store is a rebuildable cache) and returns `true`; any
other read failure throws `IOException`. The file format is handled by
`StorePersistence` / `StoreJson` / `FileStorePersistence` in
`id.jawa.feature.store`; do not hand-edit the snapshot and expect the
loader to repair it — corrupt means empty, by policy, not best-effort
recovery.

### 3.5 Store-dispatched events, in one place

When bound (§3.1), a store dispatches: `chats.upsert`,
`chats.update`, `chats.delete`, `contacts.upsert`,
`contacts.update`, `messages.delete`, `messages.reaction`,
`groups.upsert`, `groups.update`, `lid-mapping.update`,
`blocklist.update`, `labels.edit`, and `labels.association`.
Presence is the exception: `upsertPresence` merges and emits nothing,
because the client dispatches `presence.update` itself. The three
events that are *only* available from a bound store are
`chats.delete`, `contacts.upsert`, and `messages.delete` (§2.3).

---

## 4. Sending messages

All sends go through `JaWaClient` or its `MessagingService` facade
(`client.messages()`); the two are thin wrappers over the same send
path, and every send returns a future of the sent message id (or, for
albums, the list of ids, album declaration first). The routing rule is
worth knowing because it is implicit: `sendMessage(chatJid, msg)`
sends to a newsletter when the JID ends with `@newsletter`, to a group
when it ends with `@g.us`, and otherwise as a direct message. If you
pass the wrong JID suffix, you will send to the wrong kind of
destination without an error from the router — check your JIDs.

### 4.1 The decoded inbound message, and plain text

```java
import id.jawa.feature.messaging.MessageReceiver;

// Inbound, from onMessageUpsert (§2) or the legacy onMessage callback:
MessageReceiver.Decoded decoded = upsert.messages().get(0);
String sender = decoded.senderJid();     // 1:1 sender, or group sender
String group = decoded.groupJid();      // null for 1:1 chats
String id = decoded.msgId();
long timestamp = decoded.timestamp();   // epoch seconds
```

`Decoded` also carries `encType`, `messageStubType` (a generated
`Wa.WebMessageInfo.StubType`, or `null`), and
`messageStubParameters` (a list of strings, or `null`) — the stub
fields Baileys carries on `WebMessageInfo`, populated on the V4
synthetic path (§7.4) and on decrypt-failure stubs (§7.5). Do not
assume a stub type is present on ordinary messages.

Plain text, replies, and the two send entry points:

```java
// Via the client directly.
String id1 = client.sendText("62812345678@s.whatsapp.net", "Hello").join();
String id2 = client.sendMessage("62812345678@s.whatsapp.net",
        MessageEncoder.text("Hello")).join();

// Via the messaging facade (same path).
String id3 = client.messages().sendText("62812345678@s.whatsapp.net", "Hello").join();

// Reply to a decoded message (the facade overload takes the target).
String id4 = client.messages().sendReply(decoded, "Got it").join();
// Or, with an explicit quoted key:
String id5 = client.messages().sendReply(chatId, "Got it",
        quotedMsgId, quotedSenderJid, quotedText).join();
```

`MessageEncoder.text(String)` builds the underlying `Wa.Message`.
Prefer the facades (`sendText`, `sendReply`, …) when one exists for
what you want; use `MessageEncoder` + `sendMessage` when you are
composing a message no facade covers (the builder families below).

### 4.2 Media: image, video, audio, document, sticker

```java
// Via the media facade.
String img = client.media().sendImage(chatId, imageBytes, "image/jpeg", "A caption").join();
String vid = client.media().sendVideo(chatId, videoBytes, "video/mp4", "A caption").join();
String aud = client.media().sendAudio(chatId, audioBytes, "audio/ogg", 12, true).join();
String doc = client.media().sendDocument(chatId, docBytes, "application/pdf",
        "report.pdf", "Report").join();
String stk = client.media().sendSticker(chatId, webpBytes, false).join();

// View-once variants (client-level; the media facade mirrors them).
String imgOnce = client.sendImageViewOnce(chatId, imageBytes, "image/jpeg", "Once").join();
String vidOnce = client.sendVideoViewOnce(chatId, videoBytes, "video/mp4", "Once").join();
String audOnce = client.sendAudioViewOnce(chatId, audioBytes, "audio/ogg", 12, true).join();
```

The `sendAudio(..., int seconds, boolean ptt)` arguments are duration
in seconds and whether the audio is a push-to-talk voice note.
`sendSticker(chatId, webpBytes, boolean isAnimated)` takes the animated
flag; the two-argument `sendSticker(chatJid, webpBytes)` on the client
defaults it. Downloading media is in §9.

### 4.3 Albums

An album is a declaration message followed by its member media
messages, associated to the declaration's key. Members must be image
or video messages, and there must be at least two of them — both
rules are enforced by failing the future, not by a send-time error
from the server:

```java
import id.jawa.feature.messaging.MessageEncoder;
import id.jawa.proto.Wa;

import java.util.List;

// The high-level way (recommended): the client sends the declaration,
// then each member in order, associated to it.
List<Wa.Message> members = List.of(
    MessageEncoder.imageMessage(uploadedUrl, directPath, mediaKey,
        fileSha256, fileEncSha256, fileLength, "image/jpeg", "Caption"),
    MessageEncoder.videoMessage(uploadedUrl, directPath, mediaKey,
        fileSha256, fileEncSha256, fileLength, "video/mp4", "Caption",
        0, 0, 0));  // caption, seconds, width, height
List<String> ids = client.sendAlbum(chatId, members).join();
// ids.get(0) is the album declaration; ids.get(1..) are the members.

// The low-level way, if you are driving the send yourself:
Wa.Message album = MessageEncoder.albumMessage(1, 1); // images, videos
String albumId = client.sendMessage(chatId, album).join();
Wa.MessageKey parentKey = Wa.MessageKey.newBuilder()
    .setRemoteJid(chatId).setFromMe(true).setId(albumId).build();
Wa.Message member = MessageEncoder.withAlbumAssociation(members.get(0), parentKey, 0);
client.sendMessage(chatId, member);
```

`albumMessage(int expectedImageCount, int expectedVideoCount)`
requires the total to be at least two. `withAlbumAssociation(Wa.Message,
Wa.MessageKey, int)` sets the top-level
`MessageContextInfo.messageAssociation` to `MEDIA_ALBUM` with the
parent key and the zero-based member index, preserving any existing
message-context info. The `imageMessage` / `videoMessage` signatures
above are abbreviated with `/* ... */` on purpose: their full
parameter lists are the uploaded-media fields (URL, direct path,
media key, hashes, length, caption, and media-specific flags) — read
them from `MessageEncoder` in source rather than guessing them from
this guide, and prefer `client.media().sendImage/sendVideo` (§4.2)
when you have raw bytes and want wa-java to do the upload.

### 4.4 Polls and quizzes

```java
// A plain poll. selectableCount 1 uses the V3 shape, otherwise V2.
String pollId = client.sendPoll(chatId, "Lunch?",
        List.of("Pizza", "Sushi", "Salad"), 1).join();

// A quiz: V5, single-select, with a correct answer. Baileys' send path
// allows quizzes only in newsletters; the builder builds the message
// and that send-time restriction is the caller's to observe.
String quizId = client.messages().sendQuiz(newsletterJid, "Capital of France?",
        List.of("Paris", "Rome", "Berlin"), 0).join();

// A poll-result snapshot (aggregated votes), quiz or plain.
String snapId = client.messages().sendPollResult(chatId, "Lunch?",
        List.of(
            new MessageEncoder.PollVoteInput("Pizza", 12),
            new MessageEncoder.PollVoteInput("Sushi", 7)),
        false).join();
```

`MessageEncoder.pollMessage(String, List<String>, int)` is the
builder behind `sendPoll`; `quizMessage(String, List<String>, int)`
is the V5 quiz builder (options must number at least two, and
`correctOptionIndex` must be in range, or it throws
`IllegalArgumentException`); `pollResultSnapshotMessage(String,
List<PollVoteInput>, boolean)` builds the snapshot (V3 for quizzes,
plain otherwise). Option hashes are not computed by any of these
builders — the existing poll builder does not compute them either,
so do not expect them.

**Casting a poll vote** is a different shape, and the guide is explicit
about its one hard requirement: the vote payload is **caller-supplied
encrypted bytes**. wa-java does not encrypt a vote for you, and
Baileys likewise takes the encrypted vote from the caller:

```java
import id.jawa.proto.Wa;

Wa.MessageKey pollKey = Wa.MessageKey.newBuilder()
    .setRemoteJid(chatId).setFromMe(false).setId(pollCreationMsgId).build();
Wa.Message vote = MessageEncoder.pollUpdateMessage(
    pollKey, encPayloadBytes, encIvBytes, System.currentTimeMillis());
client.sendMessage(chatId, vote);
```

`pollUpdateMessage(Wa.MessageKey, byte[], byte[], long)` takes the
poll creation key, the encrypted payload, the encrypted IV, and the
sender timestamp in milliseconds. Inbound vote *decryption* is not
wired: Baileys' own `pollUpdateMessage` receive branch is commented
out, and wa-java correctly does not emit a `messages.update` for an
inbound poll vote either (§11).

### 4.5 Mentions: specific users, everyone, groups, contacts

```java
// Mention specific users (bare JIDs, without device suffixes).
String m1 = client.sendTextWithMentions(chatId, "Hi @Aisha @Bilal",
        List.of("628111@s.whatsapp.net", "628222@s.whatsapp.net")).join();

// Mention everyone: the same wire shape, plus ContextInfo.nonJidMentions = 1.
// The participant list comes from you (group query or store) — the
// builder performs no participant expansion, and Baileys doesn't either.
String m2 = client.messages().sendMentionAll(chatId, "Attention everyone",
        participantJids).join();

// Group mentions: the subject is carried verbatim, not looked up.
String m3 = client.messages().sendGroupMentions(chatId, "See the group",
        List.of(new MessageEncoder.GroupMentionInput(
            "120207…@g.us", "Engineering"))).join();

// Several contacts in one message (at least one entry required).
String m4 = client.messages().sendContactsArray(chatId, "Team",
        List.of(
            new MessageEncoder.ContactInput("Aisha", "BEGIN:VCARD\n…\nEND:VCARD"),
            new MessageEncoder.ContactInput("Bilal", "BEGIN:VCARD\n…\nEND:VCARD"))).join();
```

The underlying builders are `MessageEncoder.textWithMentions(String,
List<String>)`, `mentionAllMessage(String, List<String>)`,
`textWithGroupMentions(String, List<GroupMentionInput>)`, and
`contactsArrayMessage(String, List<ContactInput>)`. Blank and
duplicate JIDs are dropped by `mentionAllMessage`; `contactsArrayMessage`
requires at least one contact. A single contact (not an array) is
`client.sendContact(chatId, displayName, vcard)` / `MessageEncoder
.contactMessage(String, String)`, and a location pin is
`client.sendLocation(chatId, latitude, longitude, name, address)`.

### 4.6 Lottie stickers, group status, spoiler, interactive templates

```java
import id.jawa.feature.messaging.MessageEncoder;

// Lottie: a sticker wrapped in a FutureProofMessage, exactly as the
// Baileys fork wraps it. The flag overload also exists on the plain
// sticker builder so lottie stickers are not silently downgraded.
Wa.Message lottie = MessageEncoder.lottieStickerMessage(
    uploadedUrl, directPath, mediaKey, fileSha256, fileEncSha256,
    fileLength, true);
client.sendMessage(chatId, lottie);

// Or wrap any sticker message you already built:
Wa.Message wrapped = MessageEncoder.wrapAsLottie(stickerMessage);

// Group status: prefer an extended-text inner message. For inner types
// with no context info (for example plain conversation text) only the
// wrapper is set — Baileys likewise has nothing to flag there.
Wa.Message groupStatus = MessageEncoder.groupStatusWrap(
    MessageEncoder.text("Hello, group"));
client.sendMessage(chatId, groupStatus);

// Spoiler: ContextInfo.isSpoiler (proto field 73) and
// Message.spoilerMessage (proto field 118) — see §11 for the field-count
// correction this sits alongside.
Wa.Message spoiler = MessageEncoder.spoilerWrap(MessageEncoder.text("Secret"));
client.sendMessage(chatId, spoiler);

// Interactive message as a template, with an explicit template id
// (or none, for a generated "template-" + current-time id).
Wa.Message interactive = MessageEncoder.interactiveCtaButtons(
    "Pick one", "Footer",
    List.of(MessageEncoder.CtaButton.url("Open", "https://example.com")));
Wa.Message templated = MessageEncoder.interactiveAsTemplate(interactive, "tpl-1");
client.sendMessage(chatId, templated);
```

`CtaButton` also has `copy(displayText, copyCode)`,
`call(displayText, phoneNumber)`, `quickReply(displayText, id)`, and
`singleSelect(displayText, sections)` factories. `interactiveCarousel`
and `buttonsMessage` build the other interactive shapes; their
signatures are in `MessageEncoder` and follow the same
`(body, footer, …)` pattern. `interactiveAsTemplate` throws
`IllegalArgumentException` if the input carries no interactive
message.

### 4.7 Rich code and table messages

These are Baileys' AI rich-response messages. The builders produce the
core `richResponseMessage` payload only — Baileys wraps rich responses
in a `botForwardedMessage` carrying a generated unified response and
verification proofs, and wa-java deliberately does **not** fabricate
proofs, so the two payloads differ in exactly that wrapper:

```java
// Code: at least one block required; highlight type stays at the proto
// default (wa-java has no syntax-highlighting input to map).
String codeId = client.messages().sendRichCode(chatId, "java",
        List.of("System.out.println(\"hi\");")).join();

// Table: at least one row required; heading flags are carried verbatim.
String tableId = client.messages().sendRichTable(chatId, "Results",
        List.of(
            new MessageEncoder.TableRowInput(List.of("Name", "Score"), true),
            new MessageEncoder.TableRowInput(List.of("Aisha", "42"), false))).join();
```

Builders: `MessageEncoder.richCodeMessage(String, List<String>)` and
`richTableMessage(String, List<TableRowInput>)`.

### 4.8 Payments

Amounts on the request side are in **thousandths** of the currency
unit (`amount1000`), and the amount/currency live on the request
message, not on the send-payment message:

```java
// Request a payment: currency, amount in thousandths, requester JID, note.
Wa.Message request = MessageEncoder.requestPaymentMessage(
    "PKR", 250_000L, "62812345678@s.whatsapp.net", "Lunch share");
client.sendMessage(chatId, request);

// Answer a payment request: a note, the request's key, opaque data.
Wa.Message answer = MessageEncoder.sendPaymentMessage(
    MessageEncoder.text("Paid, thanks"), requestKey, "opaque-txn-data");
client.sendMessage(chatId, answer);
```

`sendPaymentMessage(Wa.Message, Wa.MessageKey, String)` takes a note
message (`null` becomes an empty message), a request key (may be
`null`), and transaction data (may be `null`).

### 4.9 Lists, buttons, carousels, and other interactive shapes

```java
String listId = client.messages().sendListMessage(
    chatId, "Menu title", "Body", "Footer", "Open menu", sections).join();
String ctaId = client.messages().sendCtaButtons(
    chatId, "Body", "Footer", buttons).join();
String btnId = client.messages().sendButtonsMessage(
    chatId, "Body", "Footer", quickReplyButtons).join();
String carId = client.messages().sendCarousel(
    chatId, "Body", "Footer", cards).join();
```

The `sections`, `buttons`, and `quickReplyButtons` arguments are the
`MessageEncoder.ListSection`, `CtaButton`, and `QuickReplyButton`
record types respectively. The carousel argument is different: the
facade's `sendCarousel` takes `JaWaClient.CarouselCardInput` records
`(title, caption, mediaBytes, mimetype, footer, buttons)`, not the
`MessageEncoder.CarouselCard` used by the raw
`MessageEncoder.interactiveCarousel` builder (whose cards do require
an image or video header, or it throws `IllegalArgumentException`).
The client-level `sendListMessage`, `sendCtaButtons`,
`sendButtonsMessage`, and `sendCarousel` methods exist on
`JaWaClient` as well as on the facade; the facade versions above are
the ones whose signatures were verified for this guide. There is
also a `sendCarousel` overload taking a trailing `Wa.ContextInfo`
quoted argument.

### 4.10 Ephemeral (disappearing) messages

```java
// Per-chat timer, in seconds (0 disables it for the chat).
client.setChatEphemeralTimer(chatId, 3600);
int timer = client.getChatEphemeralTimer(chatId);
Map<String, Integer> timers = client.getChatEphemeralTimers();

// Wrap a message so the timer applies to it.
Wa.Message ephemeral = client.wrapEphemeralIfNeeded(chatId, MessageEncoder.text("Hi"));
client.sendMessage(chatId, ephemeral);

// Account-wide default (see also §8.1).
client.setDefaultDisappearingTimer(86400);
client.updateDefaultDisappearingMode(86400);
```

`wrapEphemeralIfNeeded(String, Wa.Message)` applies the chat's timer
when one is set and returns the message unchanged otherwise — call it
yourself; the plain `sendText` path does not wrap for you.


---

## 5. Groups, communities, newsletters, contacts, labels, catalog and business

All of these live behind `client.groups()`, `client.newsletters()`,
`client.profile()`, and `client.chats()`. Futures behave as in §4:
a failed operation fails the future; a few operations return
`BinaryNode` (the raw stanza response) rather than a parsed model,
because no parsed model exists for that response yet — check the
return type before you assume a typed result.

### 5.1 Groups

```java
import id.jawa.feature.messaging.GroupListQuery;
import id.jawa.feature.messaging.GroupMetadataQuery;

// Create, leave, and manage membership.
String groupJid = client.groups().createGroup("Engineering",
        List.of("628111@s.whatsapp.net", "628222@s.whatsapp.net")).join();
client.groups().leaveGroup(groupJid).join();
client.groups().updateGroupParticipants(groupJid,
        List.of("628333@s.whatsapp.net"), "add").join();

// Subject, description, and the group settings Baileys exposes.
client.groups().setGroupSubject(groupJid, "Engineering v2").join();
client.groups().setGroupDescription(groupJid, "New description").join();
client.groups().setGroupLocked(groupJid, true).join();      // only admins send
client.groups().setGroupAnnounce(groupJid, true).join();   // announcement mode
client.groups().setGroupJoinApprovalMode(groupJid, true).join();
client.groups().setGroupMemberAddMode(groupJid, true).join();

// Join-request queue (approval mode on).
List<GroupJoinRequest> requests =
    client.groups().getGroupRequestParticipants(groupJid).join();
client.groups().updateGroupRequestParticipants(groupJid,
        List.of("628444@s.whatsapp.net"), true).join();     // true = approve

// Invite links.
String link = client.groups().getGroupInviteLink(groupJid).join();
String resetLink = client.groups().getGroupInviteLink(groupJid, true).join();
String revoked = client.groups().revokeGroupInviteLink(groupJid).join();
String joined = client.groups().joinGroupWithLink("invite-code").join();

// Queries: joined groups, one group's info, full metadata.
List<GroupListQuery.GroupInfo> joinedGroups =
    client.groups().queryJoinedGroups().join();
GroupListQuery.GroupInfo info = client.groups().queryGroupInfo(groupJid).join();
GroupMetadataQuery.GroupMetadata metadata =
    client.groups().queryGroupMetadata(groupJid).join();
```

`updateGroupParticipants(groupJid, userJids, action)` takes Baileys'
action string (`"add"` / `"remove"` in practice). The group-picture
operations are `updateGroupPicture(groupJid, imageBytes)` and
`removeGroupPicture(groupJid)` on both `GroupService` and
`JaWaClient`. Group invite-by-code flows that carry an inviter and an
expiry are separate: `getGroupInfoFromInvite(groupJid, inviterJid,
code, expiration)` and `joinGroupWithInvite(groupJid, inviterJid,
code, expiration)` — use those when you have a V4-style invite, not
the plain link methods.

Group events (§2) fire from the `w:gp2` notification path: group
creation dispatches `chats.upsert` + `groups.upsert`, participant
changes dispatch `group-participants.update` (a leave is emitted as
`remove`, matching Baileys), join requests dispatch
`group.join-request`, and group-change updates (subject, description,
announce, restrict, invite code, member-add mode, join-approval
mode) dispatch `groups.update` with those fields carried on the
payload. Member-tag changes dispatch `group.member-tag.update`;
note that wa-java's `MessageKey` has no `participantAlt` field, so
that piece of the Baileys payload is not carried (see §11).

### 5.2 Communities

Communities are groups with a linked-group structure, and the API
mirrors that: create the community, then link child groups into it.

```java
// Create a community, and a community group, with metadata when needed.
BinaryNode community = client.groups().createCommunity(
        "Neighbourhood", "Local announcements").join();
GroupMetadataQuery.GroupMetadata communityMeta =
    client.groups().createCommunityWithMetadata(
        "Neighbourhood", "Local announcements").join();

// Link / unlink child groups, and read the structure back.
client.groups().linkGroup(communityJid, childGroupJid).join();
client.groups().unlinkGroup(communityJid, childGroupJid).join();
List<CommunitySubGroup> subGroups =
    client.groups().getSubGroups(communityJid).join();
List<String> linkedParticipants =
    client.groups().getLinkedGroupsParticipants(communityJid).join();

// Community settings and invites.
client.groups().updateCommunitySubject(communityJid, "New name").join();
client.groups().updateCommunityDescription(communityJid, "New text").join();
client.groups().updateCommunitySetting(communityJid, "setting-name").join();
String inviteCode = client.groups().getCommunityInviteCode(communityJid).join();
String accepted = client.groups().acceptCommunityInvite(inviteCode).join();
client.groups().leaveCommunity(communityJid).join();
```

`updateCommunitySetting(communityJid, setting)` takes Baileys'
setting string — this guide does not invent an enum for it, because
wa-java defines none. Member-add and join-approval modes for
communities take a mode string (`setCommunityMemberAddMode`,
`setCommunityJoinApprovalMode`), unlike the boolean group versions
in §5.1. Ephemeral toggling is
`toggleCommunityEphemeral(communityJid, ephemeralExpiration)` with a
nullable expiration. V4 invite flows are
`acceptCommunityInviteV4(inviterRemoteJid, messageId, …)` and
`revokeCommunityInviteV4(communityJid, invitedJid)`; on a full-key
V4 accept, wa-java also dispatches `messages.update` with the invite
copy rewritten as expired (`inviteExpiration = 0`,
`inviteCode = ''`). The synthetic `GROUP_PARTICIPANT_ADD` upsert
Baileys also performs on V4 accept is **not** ported — wa-java has no
synthetic-message upsert path for it (§11).

`GroupMetadataQuery.GroupMetadata` (not `GroupListQuery.GroupInfo`)
is the type that carries `linkedParent` for community-linked groups.
If you are reading a community's parent link, query metadata, not
the group list.

### 5.3 Newsletters (channels)

```java
import id.jawa.domain.model.NewsletterMetadata;
import id.jawa.domain.model.NewsletterMessage;

// Follow / unfollow / mute, and read channel info.
client.newsletters().follow(newsletterJid).join();
client.newsletters().unfollow(newsletterJid).join();
client.newsletters().toggleMute(newsletterJid, true).join();
NewsletterMetadata info = client.newsletters().getInfo(newsletterJid).join();
NewsletterMetadata viaInvite =
    client.newsletters().getInfoWithInvite("invite-code-or-url").join();

// Send text, a full message, a reaction, and mark messages viewed.
String msgId = client.newsletters().sendText(newsletterJid, "Hello, channel").join();
client.newsletters().sendReaction(newsletterJid, serverId, "👍").join();
client.newsletters().markViewed(newsletterJid, List.of(1L, 2L)).join();

// Read messages, newest-first paging by server id.
List<NewsletterMessage> messages =
    client.newsletters().getMessages(newsletterJid, 20).join();
List<NewsletterMessage> older =
    client.newsletters().getMessages(newsletterJid, 20, beforeServerId).join();

// Admin: create, rename, re-describe, picture, subscribers, owner, delete.
NewsletterMetadata created = client.newsletters()
    .createNewsletter("My channel", "About this channel").join();
client.newsletters().updateNewsletterName(newsletterJid, "New name").join();
client.newsletters().updateNewsletterDescription(newsletterJid, "New text").join();
client.newsletters().updateNewsletterPicture(newsletterJid, imageBytes).join();
client.newsletters().removeNewsletterPicture(newsletterJid).join();
String subscribers = client.newsletters().getNewsletterSubscribers(newsletterJid).join();
String subscribed = client.newsletters().getSubscribedNewsletters().join();
int adminCount = client.newsletters().getNewsletterAdminCount(newsletterJid).join();
client.newsletters().changeNewsletterOwner(newsletterJid, newOwnerJid).join();
client.newsletters().demoteNewsletterAdmin(newsletterJid, userJid).join();
client.newsletters().deleteNewsletter(newsletterJid).join();
```

Notes that prevent the usual newsletter mistakes:

- `sendText` / `sendMessage` on the newsletter facade are the
  channel-send path. `JaWaClient.sendMessage` also routes to it
  automatically when the JID ends with `@newsletter` (§4), and
  `sendNewsletterText` / `sendNewsletterReaction` exist on the
  client as well.
- `getNewsletterSubscribers` and `getSubscribedNewsletters` return
  futures of `String` (the raw mex response), not parsed lists —
  parse them yourself if you need structured subscriber data.
- `sendMexQuery(queryId, jsonVariables)` is the generic mex
  escape hatch, and `NewsletterMexQuery` builds the standard
  requests (`create`, `update`, `updateName`,
  `updateDescription`, `removePicture`, `subscribers`,
  `subscribed`, `adminCount`, `changeOwner`, `demote`, `delete`,
  `messageCappingInfo`). Each returns a `MexRequest` record
  `(queryId, dataPath, jsonVariables)`:

```java
import id.jawa.feature.newsletter.NewsletterMexQuery;

NewsletterMexQuery.MexRequest req =
    NewsletterMexQuery.create("My channel", "About this channel");
client.newsletters().sendMexQuery(req.queryId(), req.jsonVariables()).join();
```

- Newsletter **promote** does not exist as a Baileys query and was
  not invented: there is no promote facade, and this guide does not
  offer a workaround for it.
- Newsletter notifications dispatch `newsletter.reaction`,
  `newsletter.view`, `newsletter-participants.update`, and
  `newsletter-settings.update` (§2). Plaintext newsletter message
  decoding is `NewsletterService.decodeNewsletterMessages(BinaryNode,
  String)`, and linked-profile notifications are parsed by
  `NewsletterService.parseLinkedProfilesNotification(String)`,
  which takes mex JSON.

### 5.4 Contacts, labels, and quick replies (app-state patches)

Contact and label *server* operations in wa-java are app-state
patches, not dedicated IQs. There are two layers, and the guide
keeps them separate because confusing them is the main source of
bugs here:

1. **Patch models** (`AppPatchBuilder` in
   `id.jawa.feature.appstate`): pure builders, no I/O. For example
   `buildRemoveContact(String)` and `buildPushNameSetting(String)`
   take a single argument each; the other builders
   (`buildAddOrEditContact`, `buildLabelEdit`, `buildLabelDelete`,
   `buildChatLabel`, `buildMessageLabel`, `buildQuickReply`,
   `buildAddOrEditQuickReply`, `buildRemoveQuickReply`,
   `buildDisableLinkPreviews`) take the fields their patch needs —
   read their signatures in source before constructing one, because
   this guide does not reproduce every parameter list.
2. **Sending a patch.** The client hook is:

```java
import id.jawa.feature.appstate.AppPatchBuilder;
import id.jawa.feature.appstate.AppPatchEncoder;

AppPatchBuilder.AppPatch patch =
    AppPatchBuilder.buildRemoveContact("62812345678@s.whatsapp.net");
AppPatchEncoder.EncodedPatch encoded = client.appPatch(patch).join();
```

`client.appPatch(AppPatchBuilder.AppPatch)` returns a future of
the encoded patch. The version is persisted only on success, and
the failure checks are ordered (see the method's Javadoc for the
exact order). `ChatService` also offers `sendServer*` facades
(`sendServerContactPatch`, `sendServerRemoveContactPatch`,
`sendServerLabelEditPatch`, `sendServerLabelDeletePatch`,
`sendServerChatLabelPatch`, `sendServerMessageLabelPatch`,
`sendServerQuickReplyPatch`, `sendServerRemoveQuickReplyPatch`,
`sendServerPushNamePatch`, `updateProfileName`,
`sendServerDisableLinkPreviewsPatch`, and the quick-reply pair
`addOrEditQuickReply` / `removeQuickReply`) — in both
caller-supplied-transport and no-transport forms, where the
no-transport form delegates to `client.appPatch`. Quick-reply
patches mirror Baileys exactly, including the quirk that remove
uses `SET` with a `deleted` flag.

App-state patch *transport* alone (`AppPatchTransport`, with
`AppPatchEncoder` mirroring Baileys' `encodeSyncdPatch` and
round-tripping through `AppStateProcessor.decode` with MAC
validation) is available for callers who drive transport themselves;
the client hook above is the normal path. Two inherited crypto
formulae are **not** re-verified against a live server (see §11):
the `AppStateKey.expand()` split order and wa-java's `LtHash`
versus the rust bridge — round-trip proves self-consistency only,
not wire compatibility.

Local (store-level) labels are different from server labels: the
store methods in §3.3 (`upsertLabel`, `applyLabelAssociation`,
…) never touch the server. Server label changes go through the
patch path above and, when they arrive back as sync actions, are
mapped to `labels.edit` / `labels.association` / `settings.update`
/ `chats.lock` events (§7.1).

### 5.5 Catalog and business profiles

```java
import id.jawa.domain.model.BusinessProfile;
import id.jawa.domain.model.CatalogProduct;
import id.jawa.feature.catalog.CatalogQuery;

// Read a business's catalog and collections.
CatalogQuery.CatalogResult catalog =
    client.profile().getCatalog(businessJid, 20).join();
List<CatalogQuery.Collection> collections =
    client.profile().getCollections(businessJid, 20).join();

// Create / update / delete products. ProductInput's fields are in
// CatalogProduct in source; construct it there, don't guess them here.
CatalogProduct created = client.profile().createProduct(input).join();
CatalogProduct updated = client.profile().updateProduct(productId, input).join();
int deleted = client.profile().deleteProducts(List.of(productId)).join();

// Business profile and cover photo.
BusinessProfile profile = client.profile().getBusinessProfile(businessJid).join();
client.profile().updateBusinessProfile(profile).join();
client.profile().removeBusinessCoverPhoto(coverPhotoId).join();
```

Product-image and business-cover-photo uploads use Baileys'
**raw-upload** path (bytes as-is, token = SHA-256 of the raw bytes,
`/product/image` and `/pps/biz-cover-photo`), not encrypted MMS.
The `ProfileService` bytes overloads take a caller-supplied
`MediaConn`; `null` or empty bytes keep the existing URL-only
behaviour, and uploaded URLs replace `imageUrls()` wholesale.
Order details are `client.profile().getOrderDetails(orderId,
tokenBase64)`, and the bot list is `client.profile().getBotList()`.

---

## 6. Presence, receipts, reactions, edits, revokes, media retry

### 6.1 Presence: sending and receiving

```java
import id.jawa.domain.model.ChatPresence;

// Sending: typing / recording / paused in a chat, and global availability.
client.sendChatPresence(chatId, ChatPresence.COMPOSING);
client.sendChatPresence(chatId, ChatPresence.RECORDING);
client.sendChatPresence(chatId, ChatPresence.PAUSED);
client.sendPresence(true);              // available
client.sendPresence(false);             // unavailable
client.subscribePresence(jid);          // ask for that JID's presence

// Receiving: presence.update (§2), and the store's merged view (§3.3).
```

`ChatPresence` has exactly three values: `COMPOSING`, `RECORDING`,
and `PAUSED`. The inbound `PresenceUpdate` payload is
`(id, presences)` where `presences` maps participant JID to
`PresenceInfo` (`lastKnownPresence`, `lastSeen` in epoch seconds,
`groupOnlineCount`). The parser rules follow Baileys: `lastKnownPresence`
is `unavailable` iff the stanza's `type` is `"unavailable"`,
otherwise `available`; `lastSeen` comes from the stanza's `last`
attribute unless it is `"deny"`, absent, or unparseable; a non-`presence`
child shape maps `paused` → `available` and `media=audio` →
`recording`. One routing caveat from the implementation: the
chat-presence child branch only applies to non-`presence` tags,
which `JaWaClient` does not route — typing carried on a
differently-tagged stanza still falls through to the legacy
`onStanza` callback, as before.

### 6.2 Receipts: read, played, batches, self-receipts

```java
// For a decoded message, or by explicit key fields.
client.sendReadReceipt(decoded);
client.sendReadReceipt(chatId, msgId, senderJid);
client.sendPlayedReceipt(decoded);
client.sendPlayedReceipt(chatId, msgId, senderJid);
client.sendReadReceiptBatch(chatId, List.of(msgId1, msgId2), senderJid);
client.sendReadSelfReceipt(chatId, msgId);
client.sendPlayedSelfReceipt(chatId, msgId);
```

All of these are fire-and-forget (`void`): they send a receipt
stanza and return immediately. Inbound receipts arrive as
`message-receipt.update` wrapping a `Receipt` (§2.2). If
`autoReadReceipt` is enabled (§1.4), the client sends read receipts
automatically; disable it and send them explicitly when you want
control over exactly when a message is marked read.

### 6.3 Reactions, edits, revokes

```java
// Reaction: by explicit key, or by decoded target.
String reactionId = client.messages()
    .sendReaction(chatId, targetMsgId, targetParticipant, false, "👍").join();
String reactionId2 = client.messages().sendReaction(decoded, "❤️").join();

// Edit: replace the text of a message you sent.
String editId = client.messages().sendEdit(chatId, targetMsgId, "Corrected text").join();
String editId2 = client.messages().sendEdit(decoded, "Corrected text").join();

// Revoke (delete for everyone): by explicit key, or by decoded target.
String revokeId = client.messages()
    .sendRevoke(chatId, targetMsgId, targetParticipant, false).join();
String revokeId2 = client.messages().sendRevoke(decoded).join();
```

The `fromMe` boolean in the explicit-key forms is part of the
message key, not a permission flag: pass the key of the message as
it actually exists. Inbound edits and revokes arrive as key-only
`messages.update` entries (§2.2) — the update carries the key and
the scalar fields, not a full replacement message, because at the
time that path was written there was no stored-message layer to
apply nested edited payloads to. Inbound reactions arrive as
`messages.reaction`, and the store's `applyReaction` (§3.3) tracks
the latest reaction code per message when you feed it.

Starred messages are a separate chat operation, not a reaction:

```java
client.chats().starMessage(chatId, msgId, true).join();   // star
client.chats().starMessage(chatId, msgId, false).join();  // unstar
```

### 6.4 Media retry and updateMediaMessage

When a recipient cannot decrypt your media, WhatsApp asks for a
media retry. Two halves exist in wa-java, and they must not be
confused:

- **Answering a retry request** (the other side wants your media
  again): `sendMediaRetryReceipt`. The media-facade and
  messaging-facade forms take either explicit fields or a decoded
  target plus a media key:

```java
client.messages().sendMediaRetryReceipt(decoded, mediaKey).join();
client.media().sendMediaRetryReceipt(decoded, mediaKey).join();
```

  The client-level forms are
  `sendMediaRetryReceipt(String, String, String, …)` /
  `sendMediaRetryReceipt(MessageReceiver.Decoded, byte[])` — read
  the exact overload in source; the guide shows the decoded-target
  form because it is the one whose arguments are unambiguous
  without a signature table.

- **Requesting a retry** (you cannot decrypt someone else's media):
  `updateMediaMessage`. It builds the retry-request, waits for the
  re-upload notification, and returns it — it does **not** mutate
  your copy of the message, and it does **not** emit
  `messages.update`, because wa-java's protos are immutable and
  the method therefore returns the notification instead of
  rewriting content:

```java
import id.jawa.proto.Wa;

Wa.MediaRetryNotification notification = client.updateMediaMessage(
    messageId, remoteJid, fromMe, participant, mediaKey).join();
Wa.MediaRetryNotification byMessage = client.updateMediaMessage(
    messageId, remoteJid, fromMe, participant, message).join();
```

  Both forms have a `long timeoutMs` overload. The caller supplies
  the media key — wa-java never re-uploads bytes it does not hold,
  so a retry request for media you do not have cannot be
  fulfilled by this method, and that is by design. The parser
  carries the notification's `ciphertext` / `iv` fields, and
  `JaWaClient.decryptMediaRetryNotification` is the static
  decryption helper for a received notification. Inbound
  `mediaretry` notifications also dispatch `messages.media-update`
  (§2). **Live-gated:** `updateMediaMessage` success through a
  real socket — the phone actually re-uploading — still needs a
  live connection to confirm (§11).

---

## 7. App-state, history sync, mex, peer/PDO and decrypt-failure placeholders

This section is the most stateful part of the port, and the most
honest about its limits. Read the gate and limit notes before
building on any of it.

### 7.1 App-state sync actions map to events

`AppStateEventMapper` (`id.jawa.feature.appstate`) is a pure mapper:
it turns a sync action or a mutation into Baileys events, without
any I/O. Its entry points are `map(Wa.SyncActionData)`,
`mapMutation(AppStateProcessor.Mutation)`, `mapHistorySet(...)`, and
`historyStatusForNotification(...)`. The client wires it in
`requestAppStateSync`: decoded sync actions for labels, settings,
and chat locks are dispatched as `labels.edit`,
`labels.association`, `settings.update`, and `chats.lock`
respectively. That is how those four events, which have no stanza
dispatch path of their own, reach your listener at all.

What the mapper does **not** do: it does not fetch sync state, it
does not persist versions, and it does not decide which collections
to sync. Version handling lives in `AppStateVersionStore` (with
`FileAppStateVersionStore` under `<signalDir>/appstate-versions/`
or a SQLite table when a `SqliteAuthStore` is in use, and
`InMemoryAppStateVersionStore` otherwise) and in
`SyncStateEvolver` / `requestAppStateSyncWithVersions`, which
apply Baileys' version precedence without fabricating versions.
A plain `FileAuthStore` client with no `signalDir` keeps the
in-memory version store, so versions are lost on restart. Known
limits of the wired path: resync is single-request (it does not
loop on `hasMorePatches`); external-blob snapshots are still not
downloaded; patch-only syncs with no base state do not persist
evolved state; and decode runs with `validateMacs=false`
(pre-existing).

### 7.2 History sync: inline, download, dispatch

History arrives as `HistorySyncNotification`s inside messages.
`HistorySyncNotificationParser` (`id.jawa.feature.messaging`)
is the pure side: `extractNotification(Wa.Message)`,
`shouldProcessHistoryMessage(Wa.Message.HistorySyncType)`,
`decodeInline(...)`, `decodeDownloaded(byte[])`,
`downloadHistory(...)`, `toHistorySet(Wa.HistorySync, …)`, and
`lidPnMappings(Wa.HistorySync)`. Only the types in
`PROCESSABLE_HISTORY_TYPES` are processed; the rest are skipped,
not failed.

The client-side behaviour is: inline payloads are tried first,
then a download, then log-and-skip. A download uses the history
media type (`MediaType.HISTORY`, "WhatsApp History Keys" — verifiable
from Baileys' HKDF mapping, not guessed), fetches from
`mmg.whatsapp.net` via the direct path, decrypts, inflates, and
decodes; a MAC failure yields `null`, never a partial sync. On
success the client dispatches `messaging-history.set` (inline
payloads) and `messaging-history.status` (bootstrap / RECENT-100 /
paused, deduplicated via `HistorySyncStatusTracker`). Standard
media format is reused for the download, but **live-gated:** the
format is not yet confirmed against a live history file (§11).

### 7.3 Mex (newsletter / metadata exchange)

Mex is the JSON-over-stanza channel Baileys uses for newsletter
administration and related queries (§5.3 shows the newsletter
facades built on it). The receive side is
`MexNotificationParser.parse(BinaryNode)` and
`parseLegacy(BinaryNode)`, returning a list of `EventDispatch`
values — that is, parsed mex notifications are translated into bus
events rather than handed to you as raw JSON. `message-capping.update`
arrives this way too: the mex notification carries the raw JSON
verbatim as the payload (§2.2), and the `connection.update`
`reachoutTimeLock` variant also arrives via mex timelock
notifications. `NewsletterService.fetchNewChatMessageCap()` is the
facade for the capping query, and `NewsletterMexQuery.messageCappingInfo()`
builds the underlying request. If you are debugging mex, the
useful facts are the `MexRequest` shape (§5.3) and that mex
failures surface through the future of the query that sent them,
not through an event.

### 7.4 Peer-data operations (PDO) and placeholder resend, manually

A peer-data operation is a message to your own account's other
devices (notably device 0). The manual surface is:

```java
import id.jawa.feature.messaging.PeerDataOperations;
import id.jawa.proto.Wa;

// History-sync-on-demand request, as a PDO payload.
Wa.Message.PeerDataOperationRequestMessage historyRequest =
    PeerDataOperations.buildHistorySyncOnDemandRequest(/* … */);
client.sendPeerDataOperationMessage(historyRequest).join();

// Placeholder resend request, as a PDO payload.
Wa.Message.PeerDataOperationRequestMessage placeholderRequest =
    PeerDataOperations.buildPlaceholderResendRequest(messageKey);
client.sendPeerDataOperationMessage(placeholderRequest).join();

// The higher-level placeholder helper: claim, 2-second delay,
// re-check, send, 8-second expiry (see §7.5 for what it returns).
String result = client.requestPlaceholderResend(messageKey, msgData).join();
// result is null (already claimed), "RESOLVED" (real message arrived
// during the delay), or the sent PDO message id.
```

`sendPeerDataOperationMessage(Wa.Message.PeerDataOperationRequestMessage)`
fails fast when there is no protocol store (not connected) instead
of silently dropping, and fails the future when not authenticated.
When `creds.meJid` is bare (device 0 itself), the Increment 6
self-DM path is kept — Baileys' own relay loop likewise skips the
exact sender device, so the self-DM fallback is arguably correct,
but it is **unconfirmed against a live companion** (§11). When a
device-0 session can be installed, the live path encrypts for the
single own device-0 JID only (`MessageSender.buildPeerStanza`),
reframes with `MessageSender.peerFrame`, and sends with peer
attributes plus the `<meta appdata='default'/>` node
(`PeerDataOperations.peerMetaNode()` / `peerAttributes()`).
PDO response kinds beyond placeholder resend are **not** ported:
Baileys itself marks them TODO (§11).

`buildHistorySyncOnDemandRequest` and
`buildPlaceholderResendRequest` take the fields their request
needs — read their signatures in `PeerDataOperations` before
constructing one, because this guide does not reproduce their full
parameter lists. The same applies to
`buildPeerDataOperationMessage(...)` (the wrapper that turns a
request into a sendable `Wa.Message`) and
`buildMemberLabelMessage(String, long)`, which builds the
member-label message with Baileys' 30-character truncation
(`PeerDataOperations.MEMBER_LABEL_MAX_LENGTH`); the client facade
for that last one is `client.updateMemberLabel(jid, label)`.

**V4 synthetic upserts.** When a community V4 invite is accepted,
and in the peer-response path, wa-java can synthesise a message
the way Baileys does: `generateMessageIdV2()` implements Baileys'
exact V2 id algorithm (use `client.generateMessageIdV2()` when
you need a Baileys-shaped id; `client.generateMessageId()` is the
pre-existing generator), and the synthetic message is dispatched
as `messages.upsert` with type `"notify"` and fed to the store.
Do not confuse a synthetic upsert with an inbound message: it is
constructed locally, and its `requestId` (PDO path only) is the
response's `stanzaId` (§2.2).

### 7.5 Decrypt-failure placeholders: the automatic path and its gates

This is the part of the port most likely to be misread, so it is
stated as gates, in order, with no paraphrase drift:

A decrypt failure triggers a placeholder upsert + claim **only if
all** of these hold (checked by
`PeerDataOperations.isPlaceholderResendEligible(kind,
unavailableType, category, messageTimestampSec, nowSec)`):

1. Its kind is `NO_MESSAGE_FOUND`. Any other kind — `MISSING_KEYS`,
   libsignal failures, malformed stanzas — is ineligible, and
   existing behaviour for them is unchanged.
2. The stanza is not `category: 'peer'`.
3. Its `<unavailable>` type (null when absent) is not one of
   `PeerDataOperations.EXCLUDED_UNAVAILABLE_TYPES`:
   `bot_unavailable_fanout`, `hosted_unavailable_fanout`,
   `view_once_unavailable_fanout`.
4. It is not older than `PeerDataOperations.PLACEHOLDER_MAX_AGE_SECONDS`
   (14 days). Age is `nowSec - messageTimestampSec`; when the
   timestamp is missing or unparseable (0 or negative here),
   the message is treated as fresh, not skipped — Baileys computes
   `NaN > 14 days` as false, so it likewise does not skip a
   timestamp-less message on age grounds.

The failure kinds are `MessageReceiver.DecryptFailureKind`:
`NO_MESSAGE_FOUND`, `MISSING_KEYS`, `OTHER`, with
`isPlaceholderEligible()` true only for `NO_MESSAGE_FOUND`, and
`MessageReceiver.classifyFailure(Throwable)` mapping a cause chain
to a kind (a cause whose message contains
`MISSING_KEYS_ERROR_TEXT` — `"Key used already or never filled"`
— is `MISSING_KEYS`; anything else is `OTHER`, except that the
no-message path raises `DecryptException` with
`NO_MESSAGE_FOUND_ERROR_TEXT` — `"Message absent from node"`).
Legacy `DecryptException` constructors default the kind to
`OTHER`.

When the gates pass, the automatic path is: a `CIPHERTEXT` stub
upsert is dispatched, the message id is claimed, and the stub is
fed to the store — for eligible failures only; a non-eligible
failure is a pure no-op on this path. The catch then auto-sends
the PDO resend (the existing `requestPlaceholderResend`: claim +
2-second delay + PDO send + 8-second expiry,
`PLACEHOLDER_RESEND_TIMEOUT_MS = 8000`), fire-and-forget with
failures swallowed — that swallowing is pre-existing behaviour of
the auto-send future, not a new silent failure introduced by the
port, and it is the one place where a failure does not reach your
code. On **successful** PDO auto-send only, the client dispatches
`messages.update` with a single entry whose
`messageStubParameters` are `[NO_MESSAGE_FOUND_ERROR_TEXT,
requestId]`, gated on the request id being truthy, non-blank, and
not `"RESOLVED"`. The manual `requestPlaceholderResend` path
emits **no** such update — the handler lives only on the catch's
auto-send future — and failures never reach the handler, so
failure behaviour is unchanged.

`PeerDataOperations.PlaceholderResendManager` (claim / pending /
stored / resolve / expire) and
`resolvePlaceholderResendResponses(...)` are the lower-level
pieces: resolved placeholder responses are dispatched as
`messages.upsert` type `"notify"` (stored metadata preserved,
content/timestamp from the reply; dispatch-only, not fed to the
store or the legacy `onMessage`). If you drive placeholders
manually, use `requestPlaceholderResend` (§7.4) rather than the
manager directly, unless you are writing tests for the manager
itself.

---

## 8. Privacy, blocking, profile and picture, connection lifecycle

### 8.1 Privacy settings

Privacy values are passed **verbatim** — wa-java defines no value
enum, matching Baileys, which takes no enum either. The values in
practice are `all`, `contacts`, `contact_blacklist`, and `none`;
the category names are Baileys' verbatim `privacyQuery` names
(`messages`, `calladd`, `last`, `online`, `profile`, `status`,
`readreceipts`, `groupadd`), available as constants on
`PrivacyQuery` (`CATEGORY_MESSAGES`, `CATEGORY_CALL_ADD`,
`CATEGORY_LAST_SEEN`, `CATEGORY_ONLINE`,
`CATEGORY_PROFILE_PICTURE`, `CATEGORY_STATUS`,
`CATEGORY_READRECEIPTS`, and the group-add category).

```java
import id.jawa.domain.model.PrivacySettings;
import id.jawa.domain.model.StatusPrivacy;

import java.util.List;

// Fetch: the cached, Baileys-faithful form.
PrivacySettings settings = client.fetchPrivacySettings().join();
PrivacySettings forced = client.fetchPrivacySettings(true).join();
// The uncached form (fetches every time).
PrivacySettings fresh = client.getPrivacySettings().join();

// Typed updates (each is a thin facade over setPrivacySetting).
client.updateMessagesPrivacy("contacts").join();
client.updateCallPrivacy("contacts").join();
client.updateLastSeenPrivacy("contacts").join();
client.updateOnlinePrivacy("all").join();
client.updateProfilePicturePrivacy("contacts").join();
client.updateStatusPrivacy("contacts").join();
client.updateReadReceiptsPrivacy("all").join();
client.updateGroupsAddPrivacy("contacts").join();

// Generic form, and the disappearing-mode alias.
client.setPrivacySetting("messages", "contacts").join();
client.updateDefaultDisappearingMode(86400).join();
client.setDefaultDisappearingTimer(86400).join();

// Status privacy (a separate list model).
List<StatusPrivacy> statusPrivacy = client.getStatusPrivacy().join();
```

**Cache warning, stated plainly:** `fetchPrivacySettings`
faithfully reproduces Baileys' cache — updates do **not**
invalidate it. After an update, call `fetchPrivacySettings(true)`
if you need the fresh value; otherwise you will read the stale
cached one and conclude your update did not apply, when it did.
The same facades exist on `client.privacy()` (`PrivacyService`):
`getPrivacySettings`, `fetchPrivacySettings(boolean)`,
`fetchPrivacySettings()`, `setPrivacySetting`,
`setDefaultDisappearingTimer`, `updateDefaultDisappearingMode`,
the eight `update*Privacy` methods, `updateDisableLinkPreviewsPrivacy`
(which is an app-state patch, not a privacy IQ — corrected while
verifying, so do not expect it in the IQ path), and
`getStatusPrivacy`.

Disappearing-duration queries are a USync operation, not a privacy
IQ: `client.fetchDisappearingDuration(List<String>)` (and the
varargs overload) returns a JID-keyed map, omitting users whose
duration is unparseable — where Baileys would yield `NaN`.

### 8.2 Blocking

```java
import id.jawa.domain.model.Blocklist;

// Via the privacy facade (recommended for block operations).
Blocklist afterBlock = client.privacy().block("62812345678@s.whatsapp.net").join();
Blocklist afterUnblock = client.privacy().unblock("62812345678@s.whatsapp.net").join();
Blocklist list = client.privacy().getBlocklist().join();
Blocklist withDhash = client.privacy().getBlocklist("dhash-value").join();
Blocklist updated = client.privacy().updateBlocklist(jid, true).join();

// The same operations exist on JaWaClient and on ProfileService:
// client.updateBlockStatus(jid, block), client.fetchBlockList(),
// client.getBlocklist(), client.getBlocklist(dhash),
// client.updateBlocklist(jid, block).
```

Blocklist changes dispatch `blocklist.update`, one event per JID
(Baileys emits one per item, and wa-java matches that: the
`account_sync` blocklist notification path emits one event per
item, and a successful local block/unblock also dispatches it).
There is no `blocklist.set` event in Baileys at all — an early
README error in Baileys suggested one, but `fetchBlocklist()`
returns the list via a query and the real event is
`blocklist.update`. Do not wait for a `blocklist.set` event that
will never arrive.

### 8.3 Profile, status text, pictures, and push name

```java
import id.jawa.domain.model.ProfilePictureInfo;

// Status text (two spellings, same operation).
client.profile().updateStatus("Working from home").join();
String statusId = client.profile().setStatusMessage("Working from home").join();
String status = client.profile().fetchStatus("62812345678@s.whatsapp.net").join();

// Pictures: URL fetch, info (preview or full), update, remove.
String url = client.profile().fetchProfilePictureUrl(targetJid).join();
ProfilePictureInfo info = client.profile().getProfilePictureInfo(targetJid).join();
ProfilePictureInfo preview =
    client.profile().getProfilePictureInfo(targetJid, true).join();
client.profile().updateProfilePicture(imageBytes).join();
client.profile().removeProfilePicture().join();

// Group pictures live on the group facade (§5.1), not here.
// User info, contact QR, business profile: see the service listings.
client.profile().updatePushName("New Name").join();
```

Push-name updates go through the app-state patch path (§5.4):
`ChatService.sendServerPushNamePatch` / `updateProfileName` are
the facades, and the client hook is `client.appPatch` with
`AppPatchBuilder.buildPushNameSetting(name)`. Profile-picture
**sharp centre-crop geometry is divergent** (see §11): the
implementation crops top-left, not centre, so do not assume
pixel-identical thumbnails to Baileys'. The single-group
metadata and profile-picture (tcToken) queries added in
Increment 3 are the `queryGroupMetadata` and
`getProfilePictureInfo` paths above — if a picture URL comes
back stale, re-fetch with `preview=false` before concluding the
picture itself changed.

### 8.4 Connection lifecycle, reconnect, and logout

```java
// Reconnect behaviour is a setting (§1.4), not a method you call
// per-drop: autoReconnect(true) (the default) reconnects after a
// drop when paired credentials exist; autoReconnect(false) leaves
// the client terminated after a drop, and you must connect() again
// yourself (which throws if the client is already connected, so
// close() first in that path).

// Wait for a specific connection state (§2.1).
ConnectionUpdate opened = client.waitForConnectionUpdate(
        u -> u.connection() == ConnectionUpdate.ConnectionState.OPEN,
        30_000).join();

// Log out of WhatsApp entirely (removes the companion device).
client.logout().join();
```

`logout()` fails the future with `IllegalStateException("Not logged
in")` when there is no logged-in identity, and otherwise sends
the remove-companion-device IQ. It is not the same as `close()`:
`close()` / `disconnect()` only tear down this client's transport
and keep credentials, so the next `connect()` logs back in;
`logout()` ends the pairing. Termination details arrive as
`connection.update` close updates (§2.4) and through the legacy
`onTerminated(TerminationReason, String, boolean)` callback, whose
third argument is whether the termination is permanent.

`sendIq` / `sendIqAsync` / `sendStanzaAsync` are the low-level
escape hatches (`JaWaClient.sendIq(BinaryNode, Consumer<BinaryNode>)`,
`sendIqAsync(BinaryNode)`, `sendStanzaAsync(BinaryNode)`). They
have **no timeout**, unlike Baileys' query timeout — if you use
them directly, apply your own timeout with
`CompletableFuture.orTimeout(...)` rather than waiting forever.
`send(BinaryNode)` is the raw stanza send: it drops silently when
disconnected (see §11 — that disconnected-drop class of behaviour
was deliberately not changed by the port).

---

## 9. Media: upload, download, newsletter media

```java
import id.jawa.feature.media.MediaConn;
import id.jawa.feature.media.MediaUploader;

// Download media already referenced by a message.
byte[] bytes = client.downloadMedia(message);           // Wa.Message
byte[] bytes2 = client.downloadMedia(decoded);          // Decoded target
byte[] bytes3 = client.media().downloadMedia(message).join();
// The URL/direct-path form takes all five fields explicitly:
byte[] bytes4 = client.media().downloadMedia(
        url, directPath, mediaKey, fileEncSha256,
        MediaCrypto.MediaType.IMAGE).join();
```

`downloadMedia(String url, String directPath, byte[] mediaKey,
byte[] fileEncSha256, MediaCrypto.MediaType type)` is a
`CompletableFuture<byte[]>` form: when `url` is non-empty it
downloads by URL, otherwise it refreshes the media connection and
downloads by direct path, failing the future if both are empty.
Prefer the `Wa.Message` / `Decoded` forms unless you already hold
the URL, direct path, and key separately. (`MediaType.IMAGE` is
shown as an example value; the enum's other members are in
`MediaCrypto` in source.)

**Newsletter media uploads are different from MMS uploads, and
that difference is the whole point of the Increment 2 fix.**
Newsletter uploads use Baileys' `NEWSLETTER_MEDIA_PATH_MAP`
(`/newsletter/newsletter-<type>`) plus `server_thumb_gen=1`,
through the pure, unit-tested `MediaUploader.uploadUrl(host, auth,
fileEncSha256, type, newsletter)` helper. The helper's exact
parameter order is `(String host, String auth, byte[] fileEncSha256,
…)` — read the signature in `MediaUploader` before calling it
directly; normal callers reach it through the newsletter send
path, not by constructing the URL themselves. The earlier
Increment 1 claim about a `/m1/` versus `/o1/` host difference
was wrong and was corrected in Increment 2: **there is no literal
`/m1/` or `/o1/` host logic anywhere in the Baileys tree** — the
fork's newsletter media difference is the path map, and that is
what is implemented (§11).

Deleting uploaded media (by direct path, encrypted file hash, and
type) is `client.media().deleteMedia(directPath, encFileHash,
type)` / `client.deleteMedia(…)`, and refreshing the media
connection is `client.refreshMediaConn()` /
`client.media().refreshMediaConn()`.

---

## 10. Calls, status, and miscellaneous queries

### 10.1 Calls

Call events are in §2.5. The client-side call operations that
exist are narrow, and the guide lists only those:

```java
// Reject an incoming call (see §2.5 for the CallEvent that carries
// the from JID and call id).
client.rejectCall(callFrom, callId).join();

// Create a call link (media type + optional event start time).
String link = client.createCallLink("video", null).join();
String timed = client.createCallLink("audio", eventStartTimeEpochMs).join();
```

`createCallLink(String, Long)` takes the media type and an
event-start time that may be `null`. **Live-gated:** live
call-link creation still needs a real connection to confirm (§11).
There is no accept-call / place-call facade in the ported surface
beyond what `rejectCall` and `createCallLink` cover — do not
assume one from Baileys' call API, because wa-java does not expose
it.

### 10.2 Status (stories)

```java
// Plain status text / media (status@broadcast is the destination
// the client uses internally; you do not pass it yourself).
String textId = client.sendStatusText("Morning!", 0xFF000000, 1).join();
String mediaId = client.sendStatusMedia(mediaMessage).join();

// Status mentioning specific users/groups (§7.4's sibling: the
// status-mention send, with a 1500 ms default inter-target delay).
String mentionId = client.sendStatusMention(
        List.of("628111@s.whatsapp.net", "120207…@g.us"),
        MessageEncoder.text("For you")).join();
String fastMentionId = client.sendStatusMention(targets, content, 0).join();
```

`sendStatusText(String, int, int)` takes text, background colour
as ARGB, and font type. The status-mention send relays the content
once to `status@broadcast` with the expanded participant list
(groups replaced by their participants, duplicates dropped) and a
`<meta><mentioned_users>` node, then one mention message per
original target, with the default 1500 ms delay between targets;
group-metadata lookup failures are logged and that group simply
contributes no expanded users (it still appears in
`mentioned_users` and still gets a mention attempt). Failures of
the status relay or of a mention relay fail the returned future —
this method introduces no silent-failure change to any existing
send path. **Live-gated:** live `sendStatusMention` still needs
a real connection to confirm (§11).

### 10.3 Miscellaneous queries

```java
// Is a phone number on WhatsApp? (USync contact query.)
Map<String, ContactStatus> onWa =
    client.isOnWhatsApp(List.of("62812345678", "62887654321")).join();

// LID -> phone-number resolution (also §3.3 for the store cache).
String pn = client.resolveLidToPn("123…@lid").join();
String cached = client.getCachedPnForLid("123…@lid");

// USync, generic form.
UsyncQuery.GenericResult result =
    client.executeUSyncQuery(query).join();

// Reachout timelock (the mex timelock behind §2.4's reachoutTimeLock).
ConnectionUpdate.ReachoutTimeLock lock =
    client.messages().fetchAccountReachoutTimelock().join();

// Contact QR links and business message links.
String qrLink = client.getContactQrLink(true).join();   // true = revoke first
ContactQrTarget target = client.resolveContactQrLink(codeOrUrl).join();
BusinessMessageLinkTarget bizTarget =
    client.resolveBusinessMessageLink(codeOrUrl).join();
```

`isOnWhatsApp` takes phone numbers (not JIDs) on the client
facade; the `ChatService` form takes a list of phone numbers as
well. `getContactQrLink(boolean)` revokes the existing link first
when passed `true`; the no-argument form does not revoke.

---

## 11. Not yet / not ported / needs live verification

This section is carried over faithfully from the implementation
report. Nothing here is a skipped portable feature that the guide
forgot to document — each item is either transport-gated,
deliberately not ported, or awaiting a user decision.

### 11.1 Live-WhatsApp verification still owed (the principal gap)

No live connection was ever available in the sandbox, so all
verification is unit/protocol tests with mocks, fixtures, and real
in-memory crypto (including Signal sessions for peer/PDO paths).
Live confirmation is still owed for:

- Peer stanzas and V4 synthetic upserts against a real connection.
- `updateMediaMessage` success through a real socket (the phone
  actually re-uploading).
- The history-file MAC format (a MAC failure yields `null`, never
  a partial sync, but the format itself is unconfirmed live).
- The raw-upload token shape (one Baileys line involved is
  literally redacted in the source).
- Live call-link creation, live community refetch, and live
  `sendStatusMention`.

Anything in §§4–10 marked **live-gated** belongs on this list.
Do not present a live-gated flow as confirmed working on a real
account until it has been run against one.

### 11.2 Silent-failure send-path changes: NOT made

These were never silently changed, and they still need an
explicit user decision before anyone changes them:

- `send()` is a no-op when disconnected (it drops the stanza
  rather than failing).
- Fire-and-forget newsletter sends.
- Swallowed app-state errors.

Related, but distinct and also unchanged: `sendIqAsync` /
`sendStanzaAsync` have no timeout (unlike Baileys' query
timeout), and `cleanDirtyBits` uses `sendIqAsync` where Baileys
uses fire-and-forget `sendNode` — same disconnected-drop class,
but testable. The PDO auto-send future's swallowed failures
(§7.5) are pre-existing behaviour of that future, not a new
silent failure introduced by the port.

### 11.3 Faithfully not ported (not gaps)

- `sendUnifiedSession` / `sendWAMBuffer`: telemetry-only.
- PDO response kinds beyond placeholder resend: Baileys itself
  marks them TODO.
- `fetchPrivacySettings` cache invalidation on update: matches
  Baileys (see the cache warning in §8.1).
- Inbound poll-vote `messages.update`: commented out in the
  Baileys source itself (§4.4).
- `communityQuery` as a generic helper: its uses are ported as
  specific facades (§5.2), not as the generic helper.
- Device-0-is-the-sender peer case: Baileys' own relay loop
  skips the exact sender device, so the self-DM fallback (§7.4)
  is arguably correct — unconfirmed against a live companion.
- Newsletter promote: no Baileys query exists for it, so none
  was invented (§5.3).
- The synthetic `GROUP_PARTICIPANT_ADD` upsert on V4 accept:
  wa-java has no synthetic-message upsert path for it (§5.2).

### 11.4 Documented assumptions and divergences

- PDO key `fromMe = false`: helper inputs carry no `fromMe`
  flag, so the value is assumed, not derived.
- App-state `LtHash` / `AppStateKey.expand` wire compatibility
  is self-consistency only: the rust-bridge behaviour is
  inherited, not re-verified, and the `expand()` split order is
  one of the two inherited formulae (§5.4).
- `CB:ib,,dirty` refetch happens after community accept.
- `GroupListQuery.GroupInfo` has no `linkedParent`: use
  `GroupMetadataQuery.GroupMetadata` (§5.2).
- `ProfileService` bytes overloads take a caller-supplied
  `MediaConn` (§5.5).
- Profile-picture sharp centre-crop geometry is divergent:
  the implementation crops top-left (§8.3).
- `GroupMemberTagUpdate` carries `participantAlt`, but
  wa-java's `MessageKey` does not (Baileys' field is a JS-level
  extension, not a wire field) (§5.1).

### 11.5 Corrections to earlier claims (so you don't repeat them)

- **wa.proto `Message` has 95 top-level fields, not 117.**
  117 is the Baileys WAProto figure. Quote 95 for wa-java and
  117 for Baileys, and do not conflate them.
- **There is no `/m1/` or `/o1/` host logic in Baileys.**
  The newsletter media difference is the path map
  (`/newsletter/newsletter-<type>`) plus `server_thumb_gen=1`
  (§9). Any guide, comment, or issue text claiming an `/m1/`
  host fix is repeating a corrected error.
- **Socket/util coverage counts are provisional.** The final
  counts (159 socket functions + 17 properties; 186 utils;
  34 events) are exact, but the already/partial/missing split
  after Increment 8 is baseline plus documented increment
  deltas, not a fresh item-by-item re-verification. Quote the
  split as provisional with that caveat, exactly as the
  implementation report does.
- **Counts in this guide.** Events: 34 defined, 30/33
  user-facing dispatched from the client, 3 store-only (§2.3).
  Tests at the final increment: 397 total, 393 passing, with
  the 4 failures being the pre-existing sandbox-TCP-blocked
  integration tests — not regressions, and not a reason to
  report the build as green without that qualification.

---

## Appendix: where to look next

- `README.md` in this repo has the per-increment Baileys
  compatibility sections (Increments 1–10c) with the same
  claims, in shorter form. This guide is the developer-facing
  companion to those sections, not a replacement for them.
- The audit deliverables live outside this repo, in the
  `baileys-port` workspace: `FEATURE_INVENTORY.md` (one row per
  source feature), `EVENT_COMPATIBILITY_MATRIX.md` (event-by-event
  payload matrix), and `IMPLEMENTATION_REPORT.md` (increments,
  build results, and the final remaining list). They are the
  source of truth for coverage claims; this guide is the source
  of truth for how to *use* what was built.
- When in doubt about a signature, read the source file named
  in the section: `id.jawa.client.JaWaClient` for the client
  surface, `id.jawa.client.*Service` for the facades,
  `id.jawa.feature.messaging.MessageEncoder` for builders,
  `id.jawa.event.*` for event payloads, and
  `id.jawa.feature.store.InMemoryStore` for the store.
