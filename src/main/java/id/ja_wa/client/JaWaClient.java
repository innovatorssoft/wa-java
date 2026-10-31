package id.ja_wa.client;

import com.google.protobuf.ByteString;
import id.ja_wa.domain.model.AuthCreds;
import id.ja_wa.domain.model.Blocklist;
import id.ja_wa.domain.model.BlocklistItem;
import id.ja_wa.domain.model.BotListInfo;
import id.ja_wa.domain.model.BusinessMessageLinkTarget;
import id.ja_wa.domain.model.BusinessProfile;
import id.ja_wa.domain.model.ChatPresence;
import id.ja_wa.domain.model.CommunitySubGroup;
import id.ja_wa.domain.model.ContactQrTarget;
import id.ja_wa.domain.model.ContactStatus;
import id.ja_wa.domain.model.DeviceInfo;
import id.ja_wa.domain.model.GroupJoinRequest;
import id.ja_wa.domain.model.Jid;
import id.ja_wa.domain.model.NewsletterMessage;
import id.ja_wa.domain.model.NewsletterMetadata;
import id.ja_wa.domain.model.OrderDetails;
import id.ja_wa.domain.model.PrivacySettings;
import id.ja_wa.domain.model.ProfilePictureInfo;
import id.ja_wa.domain.model.Receipt;
import id.ja_wa.domain.model.SignedPreKey;
import id.ja_wa.domain.model.StatusPrivacy;
import id.ja_wa.domain.model.UserInfo;
import id.ja_wa.domain.model.WaConstants;
import id.ja_wa.domain.store.AuthStore;
import id.ja_wa.domain.store.FilePreKeyStorage;
import id.ja_wa.domain.store.FileSenderKeyStorage;
import id.ja_wa.domain.store.FileSessionStorage;
import id.ja_wa.domain.store.InMemorySenderKeyStore;
import id.ja_wa.domain.store.InMemorySignalKeyStore;
import id.ja_wa.domain.store.SignalKeyStore;
import id.ja_wa.domain.store.SqliteAuthStore;
import id.ja_wa.event.*;
import id.ja_wa.feature.appstate.AppPatchBuilder;
import id.ja_wa.feature.appstate.AppPatchEncoder;
import id.ja_wa.feature.appstate.AppPatchTransport;
import id.ja_wa.feature.appstate.AppStateEventMapper;
import id.ja_wa.feature.appstate.AppStateKey;
import id.ja_wa.feature.appstate.AppStateProcessor;
import id.ja_wa.feature.appstate.AppStateSyncQuery;
import id.ja_wa.feature.appstate.AppStateVersionStore;
import id.ja_wa.feature.appstate.FileAppStateKeyStorage;
import id.ja_wa.feature.appstate.FileAppStateVersionStore;
import id.ja_wa.feature.appstate.InMemoryAppStateVersionStore;
import id.ja_wa.feature.appstate.PatchName;
import id.ja_wa.feature.appstate.SyncStateEvolver;
import id.ja_wa.feature.media.MediaConn;
import id.ja_wa.feature.media.MediaCrypto;
import id.ja_wa.feature.media.MediaDownloader;
import id.ja_wa.feature.media.MediaUploader;
import id.ja_wa.feature.community.CommunityQuery;
import id.ja_wa.feature.messaging.CallLinkQuery;
import id.ja_wa.feature.messaging.GroupAction;
import id.ja_wa.feature.messaging.GroupListQuery;
import id.ja_wa.feature.messaging.GroupSender;
import id.ja_wa.feature.messaging.HistorySyncNotificationParser;
import id.ja_wa.feature.messaging.MessageEncoder;
import id.ja_wa.feature.messaging.MessageReceiver;
import id.ja_wa.feature.messaging.MessageSender;
import id.ja_wa.feature.messaging.PeerDataOperations;
import id.ja_wa.feature.messaging.ReachoutTimelock;
import id.ja_wa.feature.messaging.StatusMentionBuilder;
import id.ja_wa.feature.messaging.UsyncQuery;
import id.ja_wa.feature.pairing.ClientPayloadBuilder;
import id.ja_wa.feature.pairing.PairingCodeHandler;
import id.ja_wa.feature.pairing.PairingHandler;
import id.ja_wa.feature.privacy.PrivacyQuery;
import id.ja_wa.feature.signal.JaWaProtocolStore;
import id.ja_wa.feature.signal.PreKeyBundleFetcher;
import id.ja_wa.feature.signal.PreKeyManager;
import id.ja_wa.feature.signal.SessionBootstrap;
import id.ja_wa.feature.store.InMemoryStore;
import id.ja_wa.feature.store.StorePersistence;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryDecoder;
import id.ja_wa.protocol.codec.BinaryEncoder;
import id.ja_wa.protocol.codec.BinaryNode;
import id.ja_wa.protocol.connection.FrameSocket;
import id.ja_wa.protocol.connection.NoiseHandshake;
import id.ja_wa.protocol.connection.NoiseTransport;
import id.ja_wa.protocol.crypto.AesGcm;
import id.ja_wa.protocol.crypto.Bytes;
import id.ja_wa.protocol.crypto.Curve25519;
import id.ja_wa.protocol.crypto.Hkdf;
import id.ja_wa.protocol.crypto.KeyPair25519;
import id.ja_wa.protocol.crypto.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Primary client and facade for interacting with the WhatsApp Web multi-device protocol.
 * <p>
 * Provides high-level domain namespaces for operations:
 * <ul>
 *   <li>{@link #messages()} - Core text, media, interactive messages, reactions, edits, and receipts</li>
 *   <li>{@link #groups()} - Group creation, administration, permissions, and communities</li>
 *   <li>{@link #media()} - Encrypted file upload, download, and stickers</li>
 *   <li>{@link #newsletters()} - Channel broadcasts, followers, and MEX GraphQL queries</li>
 *   <li>{@link #profile()} - Status bio, avatars, business catalogs, and contact QR</li>
 *   <li>{@link #chats()} - Presence, pinning, archiving, muting, and clearing</li>
 *   <li>{@link #privacy()} - Privacy controls, disappearing timers, and blocklist</li>
 * </ul>
 */
public final class JaWaClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JaWaClient.class);

    /**
     * Shared cache mapping Linked Identity (LID) JIDs to standard Phone Number (PN) JIDs.
     */
    public static final ConcurrentHashMap<String, String> LID_TO_PN_MAP =
        new ConcurrentHashMap<>();

    /**
     * Categorization of connection closure or account session invalidation reasons.
     */
    public enum TerminationReason {
        /** Companion device authorization was explicitly revoked from primary phone. */
        REVOKED,
        /** Account phone number was banned by WhatsApp. */
        BANNED,
        /** Connection was replaced by another active session for this client. */
        REPLACED,
        /** Web client protocol version is obsolete and must be updated. */
        VERSION_OBSOLETE,
        /** Network drop or transient disconnection. */
        TRANSIENT,
        /** Unknown or unclassified termination reason. */
        UNKNOWN
    }

    /**
     * Event listener interface for receiving connection state, QR codes, incoming messages, and receipts.
     */
    public interface Listener {
        /**
         * Called when a new pairing QR code string is generated.
         *
         * @param qrStrings Raw QR code payload strings
         */
        default void onQr(List<String> qrStrings) {}

        /**
         * Called upon successful pairing and registration with the primary account.
         *
         * @param jid Authenticated account phone number JID
         * @param pushName Configured display push name
         * @param platform Primary phone OS platform
         */
        default void onPaired(String jid, String pushName, String platform) {}

        /**
         * Called when the encrypted Noise transport handshake finishes and connection is established.
         */
        default void onConnected() {}

        /**
         * Called when an incoming encrypted message is received and successfully decrypted.
         *
         * @param decoded Decoded message details and protobuf payload
         */
        default void onMessage(MessageReceiver.Decoded decoded) {}

        /**
         * Called when a delivery, read, or playback receipt is received.
         *
         * @param receipt Parsed receipt information
         */
        default void onReceipt(Receipt receipt) {}

        /**
         * Called when app state synchronization keys are received.
         *
         * @param count Number of app state keys downloaded
         */
        default void onAppStateKeysReceived(int count) {}

        /**
         * Called when app state mutations are processed (e.g. contact sync, chat history sync).
         *
         * @param name Name of the app state collection patch
         * @param mutations List of mutations applied
         */
        default void onAppStateMutations(PatchName name,
                                         List<AppStateProcessor.Mutation> mutations) {}

        /**
         * Called when the connection or session is terminated.
         *
         * @param reason Termination classification
         * @param detail Additional diagnostic message
         * @param permanent True if session credentials are permanently invalid (e.g. revoked or banned)
         */
        default void onTerminated(TerminationReason reason, String detail, boolean permanent) {}

        /**
         * Called when a server ack stanza is received.
         *
         * @param ack Server ack BinaryNode
         */
        default void onAck(BinaryNode ack) {}

        /**
         * Called for every incoming raw binary protocol stanza.
         *
         * @param node Incoming BinaryNode stanza
         */
        default void onStanza(BinaryNode node) {}

        /**
         * Called when an uncaught protocol or network exception occurs.
         *
         * @param t Cause exception
         */
        default void onError(Throwable t) {}
    }

    private final AuthStore store;
    private SignalKeyStore signalStore = new InMemorySignalKeyStore();
    private InMemorySenderKeyStore senderKeyStore = new InMemorySenderKeyStore();
    private final Path signalDir;
    private FileSessionStorage sessionStorage;
    private FilePreKeyStorage preKeyStorage;
    private FileSenderKeyStorage senderKeyStorage;
    private FileAppStateKeyStorage appStateKeyStorage;
    private AppStateProcessor appStateProcessor;
    /**
     * Increment 5: per-collection app-state-sync version state
     * (Baileys {@code 'app-state-sync-version'}). Defaults to the
     * in-memory store; {@link #connect()} replaces it with a
     * {@link FileAppStateVersionStore} when a durable backend exists
     * (SQLite database, or {@code signalDir}); a client with neither
     * keeps the in-memory store and versions are lost on restart
     * (see {@code FileAppStateVersionStore}'s fallback note).
     */
    private AppStateVersionStore appStateVersionStore = new InMemoryAppStateVersionStore();
    /**
     * Increment 5: IQ sender override for {@link #appPatch}, null in
     * production (meaning {@code this::sendIqAsync}). Set only by the
     * package-private test seam below, so appPatch can be exercised
     * with a fake sender instead of a live server.
     */
    private AppPatchTransport.IqSender appPatchIqSender;
    /**
     * Increment 6: generic stanza-request sender override, null in
     * production. Set only by the package-private test seam below, so
     * {@link #sendIqAsync} and {@link #sendStanzaAsync} can be exercised
     * with a fake sender instead of a live server. It is deliberately
     * separate from {@code appPatchIqSender}: that seam feeds only the
     * appPatch transport, while this one feeds the generic request
     * paths (call-link creation, community V4 accept, MEX queries).
     */
    private AppPatchTransport.IqSender iqSenderForTesting;
    /** Increment 8 test seam: captures peer-mode stanzas instead of {@link #send(BinaryNode)}. */
    private java.util.function.Consumer<BinaryNode> messageSenderForTesting;
    private JaWaProtocolStore protocolStore;
    private PairingCodeHandler pairCodeHandler;
    private ScheduledExecutorService keepalive;
    private Listener listener = new Listener() {};
    private final WaEventBus eventBus = new WaEventBus();
    private final CallEventParser callEventParser = new CallEventParser();
    /**
     * Increment 2 in-memory store (chats, contacts, messages, groups,
     * presences, labels). It is deliberately <em>not</em> bound to this
     * client's event bus: the client dispatches events itself at the points
     * below, and the store is query state. Binding the store to the bus as
     * well would double-emit chats/groups events. Standalone store users
     * may bind it to their own bus instead.
     */
    private final InMemoryStore memoryStore = new InMemoryStore();
    private FrameSocket frame;
    private NoiseHandshake noise;
    private NoiseTransport transport;
    private AuthCreds creds;
    private Thread readerThread;
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private final AtomicBoolean reconnectAfterPair = new AtomicBoolean(false);

    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts =
        new AtomicInteger(0);
    private boolean autoReconnect = true;
    private boolean syncVersion = true;
    private boolean autoReadReceipt = false;
    private boolean autoComposingPresence = false;
    private boolean autoPreKeyUpload = true;
    private boolean autoEphemeral = true;
    private boolean autoIgnoreOfflineMessages = true;
    private int offlineMessageGracePeriodSec = 2;
    private volatile long connectedAtEpochSec = 0;
    private final ConcurrentHashMap<String, Integer>
        chatEphemeralTimers = new ConcurrentHashMap<>();
    private String wsUrl = WaConstants.WS_URL;

    private static final long[] RECONNECT_BACKOFF_MS = {2_000, 4_000, 8_000, 16_000, 30_000, 60_000};

    private final CountDownLatch closeLatch = new CountDownLatch(1);
    private static final int PRE_KEY_UPLOAD_COUNT = 30;

    private final MessagingService messagingService = new MessagingService(this);
    /**
     * Increment 6: placeholder-resend cache for
     * {@link #requestPlaceholderResend}. Baileys keeps this cache on the
     * socket ({@code placeholderResendCache} in
     * {@code lib/Socket/chats.js}); wa-java keeps one manager per
     * client. Increment 7 wires the inbound hooks: the receiver
     * resolves a pending claim when the real message is decoded (see
     * {@code handleInboundMessage}), and the PDO-response branch
     * consumes stored data via
     * {@link #dispatchPlaceholderResendResponses}.
     */
    private final PeerDataOperations.PlaceholderResendManager placeholderResendManager =
        new PeerDataOperations.PlaceholderResendManager();
    /**
     * Increment 10b: the delay {@link #requestPlaceholderResend} (and
     * therefore the decrypt-failure auto-send in
     * {@link #handleDecryptFailurePlaceholder}) waits before sending
     * the PDO request. Production default is the manager's system
     * delay (2 seconds, Baileys' delay); the seam exists so tests can
     * inject an immediate fake delay instead of sleeping or leaving a
     * 2-second delayed task behind, without changing production
     * scheduling. The 8-second claim-expiry scheduling inside
     * {@code requestPlaceholderResend} is unchanged.
     */
    private PeerDataOperations.PlaceholderResendManager.Delay placeholderResendDelay =
        PeerDataOperations.PlaceholderResendManager.Delay.system();
    /**
     * Increment 7: Baileys' cached {@code privacySettings} variable
     * ({@code lib/Socket/chats.js}, read by
     * {@code fetchPrivacySettings}). Only the cached
     * {@link #fetchPrivacySettings(boolean)} form uses this; the
     * pre-existing {@link #getPrivacySettings()} always fetches and
     * never reads or writes it, preserving its original behaviour.
     */
    private volatile PrivacySettings cachedPrivacySettings;

    private final GroupService groupService = new GroupService(this);
    private final MediaService mediaService = new MediaService(this);
    private final NewsletterService newsletterService = new NewsletterService(this);
    private final ProfileService profileService = new ProfileService(this);
    private final ChatService chatService = new ChatService(this);
    private final PrivacyService privacyService = new PrivacyService(this);

    private final ConcurrentHashMap<String, GroupListQuery.GroupInfo>
        groupCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<DeviceInfo>>
        deviceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>
        groupPhashMap = new ConcurrentHashMap<>();
    private final ReentrantLock messageSendLock =
        new ReentrantLock();

    /** Increment 4: history-sync status state machine (Baileys chats.js). */
    private final HistorySyncStatusTracker historySyncStatusTracker;

    /**
     * Increment 4: whether a {@code messaging-history.set} has been
     * dispatched yet on this connection; Baileys' {@code isLatest} is
     * "no previously processed history messages" (and undefined for
     * ON_DEMAND).
     */
    private boolean historySetSeen;

    /**
     * Constructs a JaWaClient instance using the provided credential store.
     *
     * @param store Persistent storage implementation for authentication credentials
     */
    public JaWaClient(AuthStore store) {
        this(store, null);
    }

    /**
     * Constructs a JaWaClient instance with custom credential store and file-based Signal key directory.
     *
     * @param store Persistent storage implementation for authentication credentials
     * @param signalDir File system directory for SQLite or file-based Signal protocol keys, or null for in-memory
     */
    public JaWaClient(AuthStore store, Path signalDir) {
        this.store = store;
        this.signalDir = signalDir;
        this.historySyncStatusTracker = new HistorySyncStatusTracker(
            status -> dispatchEvent(BaileysEventType.MESSAGING_HISTORY_STATUS, status));
    }

    /**
     * Registers an event listener callback for connection events, QR codes, incoming messages, and receipts.
     *
     * @param l Listener implementation
     * @return This client instance for method chaining
     */
    public JaWaClient listener(Listener l) { this.listener = l != null ? l : new Listener() {}; return this; }

    /**
     * Returns the Baileys-shaped event bus for this client. The legacy
     * single {@link Listener} is unchanged; bus events fire alongside it
     * at the same dispatch points.
     *
     * @return this client's event bus
     */
    public WaEventBus eventBus() { return eventBus; }

    /**
     * Returns this client's in-memory store: chats, contacts, messages,
     * groups, presences and labels accumulated from inbound traffic since
     * connection (Increment 2). The store is populated by the client's
     * reader loop; it is not persisted across restarts.
     *
     * @return this client's in-memory store
     */
    public InMemoryStore store() { return memoryStore; }

    /**
     * Saves this client's in-memory store to a file, explicitly and
     * only when called (Increment 5; Baileys' {@code makeInMemoryStore}
     * {@code toJSON} leaves <em>when</em> to save to the caller).
     * Nothing calls this automatically: not on mutation, not on
     * startup, not on shutdown. The write is atomic (sibling
     * {@code .tmp} + move) via {@link StorePersistence}.
     *
     * @param file destination file path; must not be null
     * @throws IOException if the file cannot be written
     */
    public void saveStore(Path file) throws IOException {
        StorePersistence.saveStore(memoryStore, file);
    }

    /**
     * Loads a store snapshot from a file into this client's live
     * in-memory store, explicitly and only when called (Increment 5;
     * Baileys' {@code fromJSON} counterpart). The live store's
     * contents are replaced wholesale
     * ({@link InMemoryStore#replaceWith(InMemoryStore)}); the live
     * store's event-bus binding and message cap are kept. A missing
     * file changes nothing and returns false; an empty or corrupt
     * file replaces the store with an empty one (the store is a
     * rebuildable cache — see {@code StorePersistence}'s policy)
     * and returns true. Nothing calls this automatically.
     *
     * @param file source file path; must not be null
     * @return true when a snapshot was loaded, false when the file
     *         does not exist
     * @throws IOException if the file cannot be read
     */
    public boolean loadStore(Path file) throws IOException {
        InMemoryStore loaded = StorePersistence.loadStore(file);
        if (loaded == null) return false;
        memoryStore.replaceWith(loaded);
        return true;
    }

    /**
     * Registers a Baileys-shaped event listener on this client's bus.
     *
     * @param l listener implementation
     * @return This client instance for method chaining
     */
    public JaWaClient addEventListener(WaEventListener l) { eventBus.addListener(l); return this; }

    /**
     * Unregisters a Baileys-shaped event listener from this client's bus.
     *
     * @param l listener implementation
     * @return This client instance for method chaining
     */
    public JaWaClient removeEventListener(WaEventListener l) { eventBus.removeListener(l); return this; }

    /**
     * Increment 7: Baileys {@code waitForConnectionUpdate}
     * ({@code lib/Socket/socket.js:963}, via
     * {@code bindWaitForConnectionUpdate} in
     * {@code lib/Utils/generics.js}). Waits on this client's event
     * bus for a {@code connection.update} matching
     * {@code predicate}; see {@link ConnectionUpdateWaiter} for the
     * exact semantics, which differ from Baileys in two
     * task-specified ways: the future completes with the matching
     * update (Baileys resolves void), and on timeout it completes
     * with null instead of failing (Baileys rejects on timeout and
     * on close). A {@code close} update is delivered to the
     * predicate like any other update.
     *
     * @param predicate match test; must not be null
     * @param timeoutMs non-negative wait budget in milliseconds
     * @return future completing with the first matching update, or null on timeout
     */
    public CompletableFuture<ConnectionUpdate> waitForConnectionUpdate(
            Predicate<ConnectionUpdate> predicate, long timeoutMs) {
        return ConnectionUpdateWaiter.waitFor(eventBus, predicate, timeoutMs);
    }

    /**
     * Dispatches one bus event, logging (never throwing) on failure so a
     * bus dispatch can never break the reader loop or a legacy callback.
     * WaEventBus already guards each listener individually; this guard
     * covers failures in the dispatch machinery itself.
     */
    private void dispatchEvent(BaileysEventType type, Object payload) {
        try {
            eventBus.dispatch(type, payload);
        } catch (Throwable t) {
            LOG.warn("Failed to dispatch {}", type == null ? "null event" : type.baileysName(), t);
        }
    }

    /**
     * Increment 2: emits {@code lid-mapping.update} for LID→PN mappings
     * carried on an inbound message stanza ({@code sender_pn} /
     * {@code participant_pn}), using exactly the derivation
     * {@link MessageReceiver} applies when it fills
     * {@link #LID_TO_PN_MAP}. Mappings are also recorded in the store.
     * Unchanged re-registrations are still emitted here — unlike the
     * store's change-only {@code upsertLidMapping} — because the stanza
     * itself is the Baileys event trigger.
     */
    private void dispatchLidMappingsFrom(BinaryNode message) {
        String from = message.attr("from");
        String senderPn = message.attr("sender_pn");
        if (from != null && from.endsWith("@lid") && senderPn != null && !senderPn.isBlank()) {
            Jid fromJid = Jid.parse(from);
            Jid pnJid = Jid.parse(senderPn);
            if (fromJid != null && pnJid != null) {
                String lidBare = fromJid.user() + "@lid";
                String pnBare = pnJid.user() + "@" + Jid.SERVER_WHATSAPP;
                memoryStore.upsertLidMapping(lidBare, pnBare);
                dispatchEvent(BaileysEventType.LID_MAPPING_UPDATE, new LidMappingUpdate(lidBare, pnBare));
            }
        }
        String participant = message.attr("participant");
        String participantPn = message.attr("participant_pn");
        if (participant != null && participant.endsWith("@lid") && participantPn != null && !participantPn.isBlank()) {
            Jid partJid = Jid.parse(participant);
            Jid pnJid = Jid.parse(participantPn);
            if (partJid != null && pnJid != null) {
                String lidBare = partJid.user() + "@lid";
                String pnBare = pnJid.user() + "@" + Jid.SERVER_WHATSAPP;
                memoryStore.upsertLidMapping(lidBare, pnBare);
                dispatchEvent(BaileysEventType.LID_MAPPING_UPDATE, new LidMappingUpdate(lidBare, pnBare));
            }
        }
    }

    /**
     * Enables or disables automatic reconnection with exponential backoff upon unexpected disconnection.
     *
     * @param enabled True to automatically reconnect, false to terminate immediately on disconnect
     * @return This client instance for method chaining
     */
    public JaWaClient autoReconnect(boolean enabled) {
        this.autoReconnect = enabled;
        return this;
    }

    /**
     * Enables or disables automatic read receipt transmission upon message decryption.
     *
     * @param enabled True to automatically send read receipts
     * @return This client instance for method chaining
     */
    public JaWaClient autoReadReceipt(boolean enabled) {
        this.autoReadReceipt = enabled;
        return this;
    }

    /**
     * Enables or disables automatic typing presence indicator while encrypting and sending messages.
     *
     * @param enabled True to automatically broadcast composing presence
     * @return This client instance for method chaining
     */
    public JaWaClient autoComposingPresence(boolean enabled) {
        this.autoComposingPresence = enabled;
        return this;
    }

    /**
     * Enables or disables automatic upload of new signed Signal prekeys when server reserves run low.
     *
     * @param enabled True to auto-upload prekeys
     * @return This client instance for method chaining
     */
    public JaWaClient autoPreKeyUpload(boolean enabled) {
        this.autoPreKeyUpload = enabled;
        return this;
    }

    /**
     * Enables or disables automatic synchronization with the latest WhatsApp Web client revision.
     * When enabled and connecting to the official WhatsApp endpoint, JaWa checks for the active
     * web revision dynamically before establishing the WebSocket connection.
     *
     * @param enabled True to automatically synchronize version before connecting
     * @return This client instance for method chaining
     */
    public JaWaClient syncVersion(boolean enabled) {
        this.syncVersion = enabled;
        return this;
    }

    /**
     * Checks if automatic version synchronization is enabled.
     *
     * @return True if sync-version is active
     */
    public boolean syncVersion() {
        return syncVersion;
    }

    /**
     * Fetches the latest WhatsApp Web client revision dynamically from the live service worker.
     *
     * @return Version triplet [primary, secondary, tertiary]
     */
    public static int[] fetchLatestVersion() {
        return WaConstants.fetchLatestVersion();
    }

    /**
     * Checks if automatic reconnection is enabled.
     *
     * @return True if auto-reconnect is active
     */
    public boolean autoReconnect() { return autoReconnect; }

    /**
     * Checks if automatic read receipt transmission is enabled.
     *
     * @return True if auto-read-receipt is active
     */
    public boolean autoReadReceipt() { return autoReadReceipt; }

    /**
     * Checks if automatic typing presence is enabled.
     *
     * @return True if auto-composing-presence is active
     */
    public boolean autoComposingPresence() { return autoComposingPresence; }

    /**
     * Checks if automatic prekey replenishment is enabled.
     *
     * @return True if auto-prekey-upload is active
     */
    public boolean autoPreKeyUpload() { return autoPreKeyUpload; }

    /**
     * Checks if automatic ephemeral wrapping is enabled.
     *
     * @return True if auto-ephemeral is active
     */
    public boolean autoEphemeral() { return autoEphemeral; }

    /**
     * Checks if automatic ephemeral wrapping is enabled.
     *
     * @return True if auto-ephemeral is active
     */
    public boolean isAutoEphemeral() { return autoEphemeral; }

    /**
     * Enables or disables automatic ephemeral message wrapper injection when conversation timers are active.
     *
     * @param enabled True to auto-wrap messages in ephemeral envelopes
     * @return This client instance for method chaining
     */
    public JaWaClient autoEphemeral(boolean enabled) {
        this.autoEphemeral = enabled;
        return this;
    }

    /**
     * Checks if offline message dropping is enabled.
     *
     * @return True if offline messages are filtered
     */
    public boolean autoIgnoreOfflineMessages() { return autoIgnoreOfflineMessages; }

    /**
     * Checks if offline message dropping is enabled.
     *
     * @return True if offline messages are filtered
     */
    public boolean isAutoIgnoreOfflineMessages() { return autoIgnoreOfflineMessages; }

    /**
     * Enables or disables dropping historical messages delivered during initial connection replay.
     *
     * @param enabled True to ignore offline messages
     * @return This client instance for method chaining
     */
    public JaWaClient autoIgnoreOfflineMessages(boolean enabled) {
        this.autoIgnoreOfflineMessages = enabled;
        return this;
    }

    /**
     * Gets the grace period in seconds after connection during which messages are treated as offline replay.
     *
     * @return Grace period in seconds
     */
    public int offlineMessageGracePeriodSec() { return offlineMessageGracePeriodSec; }

    /**
     * Sets the grace period in seconds after connection during which messages are treated as offline replay.
     *
     * @param seconds Grace period duration
     * @return This client instance for method chaining
     */
    public JaWaClient offlineMessageGracePeriodSec(int seconds) {
        this.offlineMessageGracePeriodSec = Math.max(0, seconds);
        return this;
    }

    /**
     * Returns the epoch timestamp seconds when the current connection established.
     *
     * @return Connected epoch seconds, or 0 if not connected
     */
    public long getConnectedAtEpochSec() { return connectedAtEpochSec; }

    /**
     * Returns a map of cached per-chat disappearing message timers.
     *
     * @return Map of chat JID to timer in seconds
     */
    public Map<String, Integer> getChatEphemeralTimers() {
        return chatEphemeralTimers;
    }

    /**
     * Sets or clears the local ephemeral message expiration timer for a chat.
     *
     * @param chatJid Target conversation JID
     * @param timerSeconds Expiration duration in seconds (0 to disable)
     */
    public void setChatEphemeralTimer(String chatJid, int timerSeconds) {
        if (chatJid == null) return;
        if (timerSeconds > 0) {
            chatEphemeralTimers.put(chatJid, timerSeconds);
        } else {
            chatEphemeralTimers.remove(chatJid);
        }
    }

    /**
     * Returns the configured ephemeral message expiration timer in seconds for a chat.
     *
     * @param chatJid Target conversation JID
     * @return Expiration seconds, or 0 if disabled
     */
    public int getChatEphemeralTimer(String chatJid) {
        if (chatJid == null) return 0;
        return chatEphemeralTimers.getOrDefault(chatJid, 0);
    }

    /**
     * Wraps a message payload into an ephemeral container if the conversation timer is active.
     *
     * @param chatJid Target conversation JID
     * @param msg Raw Protobuf message
     * @return Ephemeral-wrapped message, or original message if no timer is active
     */
    public Wa.Message wrapEphemeralIfNeeded(String chatJid, Wa.Message msg) {
        if (!autoEphemeral || msg == null || chatJid == null) {
            return msg;
        }
        if (msg.hasEphemeralMessage() || msg.hasProtocolMessage() || msg.hasReactionMessage()) {
            return msg;
        }
        String key = chatJid;
        Jid parsed = Jid.parse(chatJid);
        if (parsed != null && !chatJid.endsWith("@g.us")) {
            key = parsed.user() + "@" + parsed.server();
        }
        Integer timer = chatEphemeralTimers.get(key);
        if (timer == null && !key.equals(chatJid)) {
            timer = chatEphemeralTimers.get(chatJid);
        }
        if (timer != null && timer > 0) {
            return Wa.Message.newBuilder()
                .setEphemeralMessage(Wa.Message.FutureProofMessage.newBuilder()
                    .setMessage(msg))
                .build();
        }
        return msg;
    }

    /**
     * Overrides the target WhatsApp Web WebSocket endpoint URL.
     *
     * @param url WebSocket endpoint URI string
     * @return This client instance for method chaining
     */
    public JaWaClient wsUrl(String url) {
        this.wsUrl = (url == null || url.isBlank()) ? WaConstants.WS_URL : url;
        return this;
    }

    /**
     * Gets the configured WhatsApp Web WebSocket endpoint URL.
     *
     * @return WebSocket URI string
     */
    public String wsUrl() { return wsUrl; }

    /**
     * Accesses the messaging service namespace for sending text, media, interactive elements, reactions, and receipts.
     *
     * @return MessagingService domain instance
     */
    public MessagingService messages() { return messagingService; }

    /**
     * Accesses the group service namespace for group creation, membership administration, and communities.
     *
     * @return GroupService domain instance
     */
    public GroupService groups() { return groupService; }

    /**
     * Accesses the media service namespace for uploading, encrypted downloading, voice notes, and stickers.
     *
     * @return MediaService domain instance
     */
    public MediaService media() { return mediaService; }

    /**
     * Accesses the newsletter service namespace for channel broadcasts, subscriptions, and MEX queries.
     *
     * @return NewsletterService domain instance
     */
    public NewsletterService newsletters() { return newsletterService; }

    /**
     * Accesses the profile service namespace for status bio, avatars, business catalogs, and contact QR links.
     *
     * @return ProfileService domain instance
     */
    public ProfileService profile() { return profileService; }

    /**
     * Accesses the chat service namespace for presence, archiving, pinning, muting, and clearing history.
     *
     * @return ChatService domain instance
     */
    public ChatService chats() { return chatService; }

    /**
     * Accesses the privacy service namespace for privacy controls, disappearing timers, and blocklists.
     *
     * @return PrivacyService domain instance
     */
    public PrivacyService privacy() { return privacyService; }

    /**
     * Establishes a WebSocket connection to WhatsApp servers and initiates the Noise cryptographic handshake.
     *
     * @throws Exception if network connection or cryptographic handshake fails
     */
    public synchronized void connect() throws Exception {
        if (frame != null) throw new IllegalStateException("already connected");

        if (syncVersion && WaConstants.WS_URL.equals(wsUrl)) {
            try {
                WaConstants.fetchLatestVersion(Duration.ofSeconds(3));
            } catch (Exception t) {
                LOG.debug("Could not fetch latest WA version dynamically, using fallback", t);
            }
        }

        creds = store.load();
        boolean isPairing = (creds == null || creds.account == null);
        if (creds == null) {
            creds = AuthCreds.generate();
            LOG.info("No creds found: generated fresh pair: regId={}", creds.registrationId);
        } else if (isPairing) {
            LOG.info("Found unpaired creds (regId={}): resuming QR pairing", creds.registrationId);
        } else {
            LOG.info("Found paired creds: login mode (jid={})", creds.meJid);
        }

        frame = new FrameSocket(null, wsUrl);
        frame.connect();

        KeyPair25519 ephemeral = Curve25519.generateKeyPair();
        noise = new NoiseHandshake(ephemeral, WaConstants.WA_HEADER);

        Wa.HandshakeMessage clientHello = Wa.HandshakeMessage.newBuilder()
                .setClientHello(Wa.HandshakeMessage.ClientHello.newBuilder()
                        .setEphemeral(ByteString.copyFrom(ephemeral.publicKey()))
                        .build())
                .build();
        frame.send(clientHello.toByteArray());

        byte[] serverHelloBytes = frame.receive(15_000);
        if (serverHelloBytes == null) throw new IllegalStateException("ServerHello timeout");
        Wa.HandshakeMessage serverFrame = Wa.HandshakeMessage.parseFrom(serverHelloBytes);
        Wa.HandshakeMessage.ServerHello serverHello = serverFrame.getServerHello();

        byte[] encStatic = noise.processServerHello(
            serverHello.getEphemeral().toByteArray(),
            serverHello.getStatic().toByteArray(),
            serverHello.getPayload().toByteArray(),
            creds.noiseKey
        );

        Wa.ClientPayload payload = isPairing
                ? ClientPayloadBuilder.register(creds)
                : ClientPayloadBuilder.login(creds);
        byte[] encPayload = noise.encrypt(payload.toByteArray());

        Wa.HandshakeMessage clientFinish = Wa.HandshakeMessage.newBuilder()
                .setClientFinish(Wa.HandshakeMessage.ClientFinish.newBuilder()
                        .setStatic(ByteString.copyFrom(encStatic))
                        .setPayload(ByteString.copyFrom(encPayload))
                        .build())
                .build();
        frame.send(clientFinish.toByteArray());

        transport = noise.finish();
        if (store instanceof SqliteAuthStore sqliteStore && sessionStorage == null) {
            sessionStorage     = new FileSessionStorage(sqliteStore);
            preKeyStorage      = new FilePreKeyStorage(sqliteStore);
            senderKeyStorage   = new FileSenderKeyStorage(sqliteStore);
            appStateKeyStorage = new FileAppStateKeyStorage(sqliteStore);
            appStateVersionStore = new FileAppStateVersionStore(sqliteStore);
            signalStore        = new InMemorySignalKeyStore(preKeyStorage);
            senderKeyStore     = new InMemorySenderKeyStore(senderKeyStorage);
            appStateProcessor  = new AppStateProcessor(appStateKeyStorage);
        } else if (signalDir != null && sessionStorage == null) {
            sessionStorage     = new FileSessionStorage(signalDir.resolve("sessions"));
            preKeyStorage      = new FilePreKeyStorage(signalDir.resolve("prekeys"));
            senderKeyStorage   = new FileSenderKeyStorage(signalDir.resolve("sender-keys"));
            appStateKeyStorage = new FileAppStateKeyStorage(signalDir.resolve("appstate-keys"));
            appStateVersionStore = new FileAppStateVersionStore(signalDir.resolve("appstate-versions"));
            signalStore        = new InMemorySignalKeyStore(preKeyStorage);
            senderKeyStore     = new InMemorySenderKeyStore(senderKeyStorage);
            appStateProcessor  = new AppStateProcessor(appStateKeyStorage);
        }
        protocolStore = new JaWaProtocolStore(creds, sessionStorage);

        if (preKeyStorage != null) {

            preKeyStorage.pruneKeepHighest(600);
            for (var entry : preKeyStorage.snapshot().entrySet()) {
                try {
                    byte[] prefixedPub = Curve25519.prependType(entry.getValue().publicKey());
                    ECKeyPair kp =
                        new ECKeyPair(
                            Curve.decodePoint(prefixedPub, 0),
                            Curve.decodePrivatePoint(entry.getValue().privateKey()));
                    protocolStore.storePreKey(entry.getKey(),
                        new PreKeyRecord(entry.getKey(), kp));
                } catch (InvalidKeyException e) {
                    LOG.warn("Failed to re-mirror persisted pre-key {} into protocolStore", entry.getKey(), e);
                }
            }
            LOG.info("Re-mirrored {} persisted pre-key(s) into protocolStore", preKeyStorage.size());
        }
        LOG.info("Noise handshake complete: steady state {}", isPairing ? "(pairing)" : "(login)");

        readerThread = Thread.ofVirtual().name("jawa-reader").start(this::readLoop);
        startKeepalive();
    }

    private void startKeepalive() {
        if (keepalive != null) return;
        keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().name("jawa-keepalive").unstarted(r);
            t.setDaemon(true);
            return t;
        });

        Runnable tick = new Runnable() {
            @Override public void run() {
                if (closing.get() || frame == null || !frame.isOpen()) return;
                try {
                    String iqId = newIqId();
                    BinaryNode ping = new BinaryNode("iq",
                        Map.of(
                            "to",    Jid.SERVER_WHATSAPP,
                            "type",  "get",
                            "xmlns", "w:p",
                            "id",    iqId
                        ),
                        List.of(new BinaryNode("ping", Map.of(), null)));
                    sendIq(ping, resp -> LOG.trace("keepalive ack id={}", iqId));
                } catch (Throwable t) {
                    LOG.warn("Keepalive send failed", t);
                }

                long delay = 20 + ThreadLocalRandom.current().nextInt(10);
                keepalive.schedule(this, delay, TimeUnit.SECONDS);
            }
        };
        keepalive.schedule(tick, 20, TimeUnit.SECONDS);
    }

    public void join() throws InterruptedException {
        closeLatch.await();
    }

    private void readLoop() {
        PairingHandler pair = new PairingHandler(creds);
        try {
            while (!closing.get() && frame.isOpen()) {
                byte[] enc = frame.receive(60_000);
                if (enc == null) {
                    if (!frame.isOpen()) break;
                    continue;
                }
                byte[] plain = transport.decrypt(enc);
                BinaryNode node = BinaryDecoder.decode(plain);
                LOG.debug("RECV STANZA: {}", node);

                try {
                    if (handleStanza(node, pair)) continue;
                    listener.onStanza(node);
                } catch (Throwable per) {
                    LOG.warn("Error handling stanza {}: continuing", node.tag(), per);
                }
            }
        } catch (Throwable t) {
            if (!closing.get() && !reconnectAfterPair.get() && !terminated.get()) {
                listener.onError(t);
            }
        } finally {
            if (reconnectAfterPair.compareAndSet(true, false)) {
                Thread.ofVirtual().name("jawa-reconnect").start(this::doReconnectPostPair);
            } else if (!closing.get() && !terminated.get() && autoReconnect && creds != null && creds.account != null) {
                Thread.ofVirtual().name("jawa-autoreconnect").start(this::doAutoReconnect);
            } else {
                close();
            }
        }
    }

    private void captureAppStateKeysFrom(MessageReceiver.Decoded decoded) {
        if (appStateKeyStorage == null || decoded.message() == null) return;
        if (!decoded.message().hasProtocolMessage()) return;
        var pm = decoded.message().getProtocolMessage();
        if (!pm.hasAppStateSyncKeyShare()) return;
        int count = 0;
        String lastKeyIdB64 = null;
        for (Wa.Message.AppStateSyncKey k : pm.getAppStateSyncKeyShare().getKeysList()) {
            byte[] id = k.getKeyId().getKeyId().toByteArray();
            byte[] data = k.getKeyData().getKeyData().toByteArray();
            if (data.length != 32) continue;
            appStateKeyStorage.put(new AppStateKey(id, data));
            lastKeyIdB64 = Bytes.toBase64(id);
            count++;
        }
        if (count > 0) {
            LOG.info("Persisted {} app-state key(s) shared by {}", count, decoded.senderJid());
            // Increment 5: Baileys sets creds.myAppStateKeyId to the
            // last shared key id (lib/Utils/process-message.js:361
            // emits creds.update with it) — without this, appPatch
            // could never find "my" key, since nothing else sets the
            // field. Standard base64, matching Baileys'
            // Buffer.toString('base64') and AuthCreds' documented form.
            if (creds != null && lastKeyIdB64 != null) {
                creds.myAppStateKeyId = lastKeyIdB64;
                try { store.save(creds); }
                catch (Exception e) { LOG.warn("save creds (myAppStateKeyId) failed", e); }
            }
            try { listener.onAppStateKeysReceived(count); }
            catch (Throwable t) { LOG.warn("listener.onAppStateKeysReceived threw", t); }
        }
    }

    /**
     * Result of an app-state sync: the decoded mutations (as
     * {@link #requestAppStateSync} returns), plus the version numbers
     * the server actually reported.
     *
     * @param mutations decoded mutations, in server order
     * @param reportedVersion server-reported version for the collection
     *                        (see {@link AppStateSyncQuery.PatchList#reportedVersion()}),
     *                        or null when the response carries no usable version
     * @param hasMorePatches server's {@code has_more_patches} flag
     * @param error true only when the server answered with an error IQ
     *              (mutations are then empty and no version is persisted)
     */
    public record AppStateSyncResult(List<AppStateProcessor.Mutation> mutations,
                                     Long reportedVersion,
                                     boolean hasMorePatches,
                                     boolean error) {}

    public CompletableFuture<List<AppStateProcessor.Mutation>>
            requestAppStateSync(PatchName name, long fromVersion) {
        return requestAppStateSyncWithVersions(name, fromVersion)
            .thenApply(AppStateSyncResult::mutations);
    }

    /**
     * Increment 5: app-state sync that also exposes and persists
     * collection versions — the version plumbing Increment 4 was
     * missing. Behaviour for the returned mutations is unchanged from
     * the old {@link #requestAppStateSync}; the addition is that, on
     * a successful (non-error) sync, the collection's LtHash state is
     * evolved from the decoded mutations
     * ({@link SyncStateEvolver}, mirroring Baileys'
     * {@code decodeSyncdSnapshot}/{@code decodePatches}) and persisted
     * to {@link #appStateVersionStore()} — but only when the evolution
     * is trustworthy (see {@code SyncStateEvolver}'s trust rule: a
     * patch-only sync with no stored or snapshot base, a missing
     * version, or any undecodable record/mutation leaves the stored
     * state untouched and logs a warning instead of persisting a
     * half-evolved state). An error IQ persists nothing. A failed
     * persist (e.g. a SQLite error) is logged, never fails the sync
     * result, because the mutations themselves were decoded fine.
     *
     * <p>Single-request only: unlike Baileys' {@code resyncAppState}
     * ({@code lib/Socket/chats.js:428}), which loops while
     * {@code hasMorePatches} is set, this method makes one request;
     * callers that need a complete sync must loop on
     * {@link AppStateSyncResult#hasMorePatches()} themselves.
     */
    public CompletableFuture<AppStateSyncResult>
            requestAppStateSyncWithVersions(PatchName name, long fromVersion) {
        if (appStateProcessor == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("signalDir not set: app-state sync requires persistent storage"));
        }
        String iqId = newIqId();
        BinaryNode iq = fromVersion < 0
            ? AppStateSyncQuery.buildSnapshotRequest(iqId, name)
            : AppStateSyncQuery.buildPatchRequest(iqId, name, fromVersion);
        LOG.debug("Sent app-state sync IQ id={} name={} from={}", iqId, name.wire, fromVersion);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("App-state sync rejected: {}", resp);
                return new AppStateSyncResult(List.of(), null, false, true);
            }
            AppStateSyncQuery.PatchList list =
                AppStateSyncQuery.parseResponse(resp);
            if (list == null) return new AppStateSyncResult(List.of(), null, false, false);

            List<AppStateProcessor.Mutation> all = new ArrayList<>();
            List<AppStateProcessor.Mutation> snapshotDecoded = null;
            if (list.snapshot() != null) {
                snapshotDecoded = appStateProcessor.decodeSnapshot(list.snapshot(), false);
                all.addAll(snapshotDecoded);
            }
            List<List<AppStateProcessor.Mutation>> perPatchDecoded = new ArrayList<>();
            for (Wa.SyncdPatch p : list.patches()) {
                List<AppStateProcessor.Mutation> decoded = appStateProcessor.decodePatch(p, false);
                perPatchDecoded.add(decoded);
                all.addAll(decoded);
            }
            LOG.info("App-state sync {}: {} snapshot record(s) + {} patch(es) -> {} mutation(s) decoded, reportedVersion={}",
                name.wire,
                list.snapshot() == null ? 0 : list.snapshot().getRecordsCount(),
                list.patches().size(),
                all.size(),
                list.reportedVersion());

            // Persist the evolved version state (Increment 5). A
            // snapshot gives a fresh base even with no stored state;
            // patches evolve from the snapshot state when present,
            // else from the stored state — and from nothing otherwise.
            id.ja_wa.feature.appstate.LtHashState evolved = null;
            if (list.snapshot() != null) {
                evolved = SyncStateEvolver.evolveFromSnapshot(list.snapshot(), snapshotDecoded);
                if (evolved == null) {
                    LOG.warn("App-state sync {}: snapshot state not persisted (missing version or undecodable record)",
                        name.wire);
                }
            }
            if (!list.patches().isEmpty()) {
                id.ja_wa.feature.appstate.LtHashState base = evolved != null
                    ? evolved
                    : appStateVersionStore.load(name.wire);
                if (base == null) {
                    LOG.warn("App-state sync {}: patch state not persisted (no stored or snapshot base state)",
                        name.wire);
                } else {
                    id.ja_wa.feature.appstate.LtHashState after = SyncStateEvolver.evolveFromPatches(
                        base, list.patches(), perPatchDecoded, list.collectionVersion());
                    if (after == null) {
                        LOG.warn("App-state sync {}: patch state not persisted (missing version or undecodable mutation)",
                            name.wire);
                    } else {
                        evolved = after;
                    }
                }
            }
            if (evolved != null && (list.snapshot() != null || !list.patches().isEmpty())) {
                try {
                    appStateVersionStore.save(name.wire, evolved);
                } catch (RuntimeException e) {
                    LOG.warn("App-state sync {}: failed persisting version state: {}", name.wire, e.toString());
                }
            }

            if (!all.isEmpty()) {
                try { listener.onAppStateMutations(name, all); }
                catch (Throwable t) { LOG.warn("listener.onAppStateMutations threw", t); }
                // Increment 3: dispatch typed events mapped from decoded
                // sync actions (labels.edit, labels.association,
                // settings.update, chats.lock). The feature-appstate mapper
                // is the canonical one here: it covers the same
                // settings/lock branches as the event-package
                // AppStateSettingsLockMapper plus labels. History-set/status
                // mapping is deliberately not dispatched here — there is
                // no history-sync notification path in this client yet.
                for (AppStateProcessor.Mutation mutation : all) {
                    for (AppStateEventMapper.MappedEvent mapped
                            : AppStateEventMapper.mapMutation(mutation)) {
                        dispatchEvent(mapped.type(), mapped.payload());
                    }
                }
            }
            return new AppStateSyncResult(List.copyOf(all), list.reportedVersion(),
                list.hasMorePatches(), false);
        });
    }

    /**
     * Increment 5: the JaWaClient {@code appPatch} hook (Baileys
     * {@code lib/Socket/chats.js:712} {@code appPatch}, via
     * {@link AppPatchTransport}). Checks, in order: the patch is
     * non-null; {@code creds.myAppStateKeyId} is set (Baileys throws
     * 'App state key not present!' when absent); the patch type is a
     * known collection ({@link PatchName#fromWire}); the key id is
     * valid standard-base64. The transport then resyncs the
     * collection, encodes with the stored app-state key and the
     * stored version state, sends the {@code w:sync:app:state} IQ,
     * and persists the new version only on success — an error IQ, a
     * missing key, or a send failure leaves the stored version
     * untouched (see {@code AppPatchTransport}).
     *
     * <p>Resync: the adapter calls
     * {@link #requestAppStateSyncWithVersions} from the stored
     * version (a snapshot request when no version is stored, or the
     * stored version is 0 — mirroring Baileys'
     * {@code return_snapshot: !state.version}), which persists the
     * evolved state when trustworthy; the transport then encodes from
     * that refreshed store. When no app-state processor exists (no
     * durable storage configured), resync is a no-op and the
     * transport encodes from whatever the version store holds, as
     * Increment 4 documented. A resync rejected by the server (error
     * IQ) fails the patch, and a resync that reports
     * {@code hasMorePatches} is logged: this hook makes a single
     * resync request and does not loop the way Baileys'
     * {@code resyncAppState} does.
     *
     * <p>Scoped fail-fast (2026-10-27 decision): this method adds
     * no connected check of its own, but when the IQ sender is the
     * real {@link #sendIqAsync} and the client is disconnected,
     * that guard fails the future promptly instead of leaving it
     * pending while {@link #send(BinaryNode)} drops the node — the
     * resync step fails the same way, through the same guard.
     * Callers that supply their own IQ sender must enforce any
     * disconnect behaviour in that sender.
     *
     * <p>Wire caveat, inherited from Increment 4 and restated, not
     * re-verified: {@code AppStateKey.expand}'s split order and the
     * LtHash-vs-rust-bridge behaviour are as Increment 4 left them;
     * round-trip tests prove self-consistency only, not wire
     * compatibility with WhatsApp's server-side LtHash.
     *
     * @param patch patch model from {@link AppPatchBuilder}
     * @return future completing with the encoder result on a successful send
     */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> appPatch(AppPatchBuilder.AppPatch patch) {
        if (patch == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("patch must not be null"));
        }
        if (creds == null || creds.myAppStateKeyId == null || creds.myAppStateKeyId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "App state key not present: myAppStateKeyId is not set"));
        }
        PatchName patchName = PatchName.fromWire(patch.type());
        if (patchName == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "unknown app-state patch type: " + patch.type()));
        }
        byte[] myKeyId;
        try {
            myKeyId = Bytes.fromBase64(creds.myAppStateKeyId);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "myAppStateKeyId is not valid base64: " + creds.myAppStateKeyId, e));
        }
        AppPatchTransport transport = new AppPatchTransport(
            keyId -> appStateKeyStorage == null ? null : appStateKeyStorage.get(keyId),
            this::resyncAppStateForPatch,
            appStateVersionStore,
            appPatchIqSender != null ? appPatchIqSender : this::sendIqAsync,
            this::newIqId);
        return transport.send(patch, myKeyId);
    }

    /** Resync adapter for {@link #appPatch} (Baileys {@code resyncAppState}). */
    private CompletableFuture<Void> resyncAppStateForPatch(String collectionName) {
        PatchName name = PatchName.fromWire(collectionName);
        if (name == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "unknown app-state patch type: " + collectionName));
        }
        if (appStateProcessor == null) {
            LOG.debug("appPatch resync skipped for {}: no app-state processor (no durable storage configured)", collectionName);
            return CompletableFuture.completedFuture(null);
        }
        id.ja_wa.feature.appstate.LtHashState stored = appStateVersionStore.load(collectionName);
        long fromVersion = (stored == null || stored.version() == 0) ? -1 : stored.version();
        return requestAppStateSyncWithVersions(name, fromVersion).thenCompose(result -> {
            if (result.error()) {
                return CompletableFuture.<Void>failedFuture(new IllegalStateException(
                    "app-state resync rejected by server for " + collectionName));
            }
            if (result.hasMorePatches()) {
                LOG.warn("appPatch resync for {} reported has_more_patches: single-request resync only, "
                    + "later patches in the server queue were not fetched", collectionName);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Returns this client's app-state-sync version store (durable
     * when {@link #connect()} found a SQLite store or a
     * {@code signalDir}, in-memory otherwise).
     */
    public AppStateVersionStore appStateVersionStore() { return appStateVersionStore; }

    /** Test seam: creds as {@link #connect()} would have loaded them. */
    void setCredsForTesting(AuthCreds creds) { this.creds = creds; }

    /** Test seam: app-state key storage as {@link #connect()} would have built it. */
    void setAppStateKeyStorageForTesting(FileAppStateKeyStorage storage) { this.appStateKeyStorage = storage; }

    /** Test seam: version store as {@link #connect()} would have wired it (null restores the in-memory default). */
    void setAppStateVersionStoreForTesting(AppStateVersionStore store) {
        this.appStateVersionStore = store != null ? store : new InMemoryAppStateVersionStore();
    }

    /** Test seam: fake generic stanza-request sender for {@link #sendIqAsync} and {@link #sendStanzaAsync} (null restores the real send path). */
    void setIqSenderForTesting(AppPatchTransport.IqSender sender) { this.iqSenderForTesting = sender; }

    /** Increment 8 test seam: protocol store as {@link #connect()} would have built it (null restores live behaviour). */
    void setProtocolStoreForTesting(JaWaProtocolStore store) { this.protocolStore = store; }

    /** Increment 8 test seam: captures peer-mode message stanzas instead of {@link #send(BinaryNode)} (null restores the real send path). */
    void setMessageSenderForTesting(java.util.function.Consumer<BinaryNode> sender) { this.messageSenderForTesting = sender; }

    /**
     * Test seam: runs one inbound stanza through {@code handleStanza}
     * (null pairing handler: only branches that do not touch pairing
     * are exercised, which includes the {@code <call>} correlation
     * branch). Returns what {@code handleStanza} returns.
     */
    boolean handleStanzaForTesting(BinaryNode node) throws Exception { return handleStanza(node, null); }

    /** Test seam: the client's placeholder-resend manager, so tests can claim entries before driving the PDO-response dispatch. */
    PeerDataOperations.PlaceholderResendManager placeholderResendManagerForTesting() {
        return placeholderResendManager;
    }

    /** Increment 10b test seam: delay used by {@link #requestPlaceholderResend} (null restores the system delay). */
    void setPlaceholderResendDelayForTesting(
            PeerDataOperations.PlaceholderResendManager.Delay delay) {
        this.placeholderResendDelay = delay != null
            ? delay : PeerDataOperations.PlaceholderResendManager.Delay.system();
    }

    /** Test seam: fake IQ sender for {@link #appPatch} (null restores {@code sendIqAsync}). */
    void setAppPatchIqSenderForTesting(AppPatchTransport.IqSender sender) { this.appPatchIqSender = sender; }

    private void handleFailure(BinaryNode node) {
        String reason = node.attr("reason", "");
        String location = node.attr("location", "");
        TerminationReason cat;
        boolean permanent;
        switch (reason) {
            case "401" -> { cat = TerminationReason.REVOKED;          permanent = true;  }
            case "405" -> { cat = TerminationReason.VERSION_OBSOLETE; permanent = true;  }
            case "403", "co_block", "banned" -> {
                                cat = TerminationReason.BANNED;        permanent = true;  }
            case "500", "503", "504" -> {
                                cat = TerminationReason.TRANSIENT;     permanent = false; }
            default     -> { cat = TerminationReason.UNKNOWN;          permanent = true;  }
        }
        LOG.warn("Server <failure> reason={} location={} -> {} (permanent={})",
            reason, location, cat, permanent);
        if (permanent) terminated.set(true);
        String detail = "reason=" + reason + (location.isEmpty() ? "" : " location=" + location);
        try { listener.onTerminated(cat, detail, permanent); }
        catch (Throwable t) { LOG.warn("listener.onTerminated threw", t); }
        dispatchEvent(BaileysEventType.CONNECTION_UPDATE, ConnectionUpdate.close(detail));
        try { listener.onError(new IllegalStateException("server rejected session: " + detail)); }
        catch (Throwable t) { LOG.warn("listener.onError threw", t); }
    }

    private void handleStreamError(BinaryNode node) {
        BinaryNode conflict = node.child("conflict");
        if (conflict != null) {
            String type = conflict.attr("type", "");
            LOG.warn("Stream error: conflict type={}: another session for this device is active", type);

            terminated.set(true);
            try { listener.onTerminated(TerminationReason.REPLACED, "conflict type=" + type, true); }
            catch (Throwable t) { LOG.warn("listener.onTerminated threw", t); }
            dispatchEvent(BaileysEventType.CONNECTION_UPDATE,
                ConnectionUpdate.close("conflict type=" + type));
            return;
        }
        LOG.warn("Stream error: {} (treating as transient)", node);
        try { listener.onTerminated(TerminationReason.TRANSIENT, "stream:error " + node, false); }
        catch (Throwable t) { LOG.warn("listener.onTerminated threw", t); }
        dispatchEvent(BaileysEventType.CONNECTION_UPDATE,
            ConnectionUpdate.close("stream:error " + node));
    }

    private void doAutoReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        long sleepMs = RECONNECT_BACKOFF_MS[Math.min(attempt - 1, RECONNECT_BACKOFF_MS.length - 1)];
        LOG.info("Auto-reconnect attempt {} in {}ms", attempt, sleepMs);
        synchronized (this) {
            if (frame != null) { try { frame.close(); } catch (Throwable ignored) {} frame = null; }
            if (keepalive != null) { keepalive.shutdownNow(); keepalive = null; }
            noise = null;
            transport = null;
            pendingIqResults.clear();
        }
        try { Thread.sleep(sleepMs); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        if (closing.get() || terminated.get()) return;
        try {
            connect();
            LOG.info("Auto-reconnect succeeded on attempt {}", attempt);
        } catch (Exception e) {
            LOG.warn("Auto-reconnect attempt {} failed: {}", attempt, e.toString());
            if (!closing.get() && !terminated.get() && autoReconnect) {
                Thread.ofVirtual().name("jawa-autoreconnect").start(this::doAutoReconnect);
            } else {
                listener.onError(e);
                close();
            }
        }
    }

    private void doReconnectPostPair() {
        if (frame != null) { try { frame.close(); } catch (Throwable ignored) {} frame = null; }
        if (keepalive != null) { keepalive.shutdownNow(); keepalive = null; }
        noise = null;
        transport = null;
        pendingIqResults.clear();

        try { Thread.sleep(500); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        if (closing.get()) return;
        try {
            connect();
            LOG.info("Auto-reconnected post-pair → login mode");
        } catch (Exception e) {
            LOG.error("Auto-reconnect failed", e);
            listener.onError(e);
            close();
        }
    }

    private final Map<String, Consumer<BinaryNode>> pendingIqResults
        = new ConcurrentHashMap<>();

    private String newIqId() {
        return Bytes.toHex(Bytes.random(8));
    }

    public String generateMessageId() {
        ByteBuffer bb = ByteBuffer.allocate(8 + 64 + 16);
        bb.putLong(System.currentTimeMillis() / 1000L);
        if (creds != null && creds.meJid != null) {
            Jid j = Jid.parse(creds.meJid);
            if (j != null && j.user() != null) {
                bb.put((j.user() + "@c.us").getBytes(StandardCharsets.UTF_8));
            }
        }
        bb.put(Bytes.random(16));
        bb.flip();
        byte[] input = new byte[bb.remaining()];
        bb.get(input);
        byte[] hash = Sha256.hash(input);
        byte[] prefix = Arrays.copyOfRange(hash, 0, 9);
        return "3EB0" + Bytes.toHex(prefix).toUpperCase();
    }

    /**
     * Increment 8: Baileys {@code generateMessageIDV2}
     * ({@code lib/Utils/generics.js}, after whatsmeow): a 44-byte
     * buffer holding the unix-seconds timestamp (big-endian, 8
     * bytes), the own user id plus {@code "@c.us"} at offset 8,
     * and 16 random bytes at offset 28; SHA-256 of the buffer,
     * first 9 bytes as upper-case hex prefixed with {@code "3EB0"},
     * with {@code "STARFALL"} inserted at position
     * {@code 4 + (hash[0] & 15)}. Used for the community V4
     * synthetic upsert id, exactly where Baileys calls
     * {@code generateMessageIDV2(sock.user?.id)}.
     *
     * @return a V2-format message id
     */
    public String generateMessageIdV2() {
        byte[] data = new byte[44];
        long ts = System.currentTimeMillis() / 1000L;
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (ts & 0xFFL);
            ts >>= 8;
        }
        if (creds != null && creds.meJid != null) {
            Jid j = Jid.parse(creds.meJid);
            if (j != null && j.user() != null && !j.user().isEmpty()) {
                byte[] userBytes = (j.user() + "@c.us").getBytes(StandardCharsets.UTF_8);
                System.arraycopy(userBytes, 0, data, 8, Math.min(userBytes.length, 20));
            }
        }
        byte[] random = Bytes.random(16);
        System.arraycopy(random, 0, data, 28, 16);
        byte[] hash = Sha256.hash(data);
        byte[] prefix = Arrays.copyOfRange(hash, 0, 9);
        String baseId = "3EB0" + Bytes.toHex(prefix).toUpperCase();
        int pos = 4 + (hash[0] & 15);
        return baseId.substring(0, pos) + "STARFALL" + baseId.substring(pos);
    }

    public String sendIq(BinaryNode iq, Consumer<BinaryNode> onResponse) {
        String id = iq.attr("id");
        if (id == null) throw new IllegalArgumentException("iq has no id attribute");
        if (onResponse != null) pendingIqResults.put(id, onResponse);
        send(iq);
        return id;
    }

    /**
     * Sends an IQ stanza and completes with the server's response node.
     *
     * <p>Scoped fail-fast (2026-10-27 decision): when the client is
     * disconnected ({@link #isDisconnected()}), the returned future
     * fails promptly with an {@link IllegalStateException} instead of
     * staying pending while {@link #send(BinaryNode)} drops the node.
     * The test seam ({@code iqSenderForTesting}) takes precedence:
     * when set, its result is returned unchanged, so fake-IQ tests
     * that simulate a connected server are unaffected. No timeout is
     * added here — the separate sendIqAsync-no-timeout gap is out of
     * scope. {@link #send(BinaryNode)} itself is unchanged.
     *
     * @param iq IQ stanza to send; must carry a non-null {@code id} attr
     * @return future completing with the inbound response node, or
     *         failing promptly when disconnected
     */
    public CompletableFuture<BinaryNode> sendIqAsync(BinaryNode iq) {
        if (iqSenderForTesting != null) return iqSenderForTesting.sendIq(iq);
        if (isDisconnected()) {
            return CompletableFuture.failedFuture(disconnectedFailure());
        }
        var f = new CompletableFuture<BinaryNode>();
        sendIq(iq, f::complete);
        return f;
    }

    /**
     * Increment 6: generic stanza request/response, mirroring Baileys'
     * {@code query} ({@code lib/Socket/socket.js:122}), which is
     * tag-agnostic: it waits for any inbound node carrying the request
     * id, not just an {@code iq}. wa-java's previous correlation only
     * consulted the pending map for {@code iq} result/error nodes, so
     * a {@code <call>} stanza could be sent (via {@link #send}) but its
     * response could never complete a future. This method registers the
     * request id in the same pending map {@code sendIq} uses and sends
     * the node; the inbound {@code <call>} branch of
     * {@code handleStanza} now completes a pending entry for that id
     * before treating the stanza as a call event, and {@code iq}
     * responses were already correlated. The response tag is therefore
     * not assumed: Baileys' {@code createCallLink} reads
     * {@code <link_create>} off whatever node arrives with the id.
     *
     * <p>Silent-failure behaviour is deliberately retained here,
     * unlike {@link #sendIqAsync}: a disconnected client drops the
     * node in {@link #send} and the future stays pending, because
     * the pending-correlation pattern this method exists for is
     * driven through {@code handleStanza} on a client with no live
     * transport (see the Increment 6 call-correlation test), and
     * there is no seam-independent way to distinguish that pattern
     * from a disconnected production client. Guarding this method
     * would break that pattern, so it is left unguarded; callers
     * that need a failure on disconnect should use
     * {@link #sendIqAsync} or check connection state first. There
     * is no timeout on this path, matching {@code sendIqAsync};
     * Baileys' own query timeout is a separate gap, not closed
     * here.
     *
     * @param node request stanza; must carry a non-null {@code id} attr
     * @return future completing with the inbound response node
     */
    public CompletableFuture<BinaryNode> sendStanzaAsync(BinaryNode node) {
        if (node == null) throw new IllegalArgumentException("node must not be null");
        if (iqSenderForTesting != null) return iqSenderForTesting.sendIq(node);
        String id = node.attr("id");
        if (id == null) throw new IllegalArgumentException("node has no id attribute");
        var f = new CompletableFuture<BinaryNode>();
        pendingIqResults.put(id, f::complete);
        send(node);
        return f;
    }

    /**
     * Increment 6: creates a call link (Baileys {@code createCallLink},
     * {@code lib/Socket/chats.js:603}). Sends the {@code <call>} stanza
     * built by {@link CallLinkQuery#buildCreate} via
     * {@link #sendStanzaAsync} and returns the created link's token from
     * the response's {@code <link_create>} child. An error-type
     * response fails the future (Baileys' {@code query} asserts the
     * node error-free); a non-error response without a token resolves
     * to null, as Baileys' {@code child?.attrs?.token} does.
     *
     * @param mediaType media type, e.g. {@code video} or {@code audio}
     * @param eventStartTime event start time in epoch seconds, or null for a link with no event
     * @return future resolving to the created link token, or null when the response carries none
     */
    public CompletableFuture<String> createCallLink(String mediaType, Long eventStartTime) {
        BinaryNode node = CallLinkQuery.buildCreate(newIqId(), mediaType, eventStartTime);
        return sendStanzaAsync(node).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("createCallLink rejected: " + resp);
            }
            return CallLinkQuery.parseToken(resp);
        });
    }

    /**
     * Increment 6: accepts a community V4 invite (Baileys
     * {@code communityAcceptInviteV4},
     * {@code lib/Socket/communities.js:347}, wrapped in
     * {@code ev.createBufferedFunction}). Sends the {@code <accept>}
     * community IQ built by
     * {@link CommunityQuery#buildAcceptInviteV4} and, on success, emits
     * the expiry side-effect exactly when Baileys does: only when the
     * caller supplied a full message key (a non-blank
     * {@code messageId}). The invite message is rewritten with
     * {@code inviteExpiration = 0} and {@code inviteCode = ''} (all
     * other fields preserved verbatim, as Baileys' {@code fromObject}
     * copy does) and dispatched as
     * {@code messages.update} with {@code update.message.groupInviteMessage}
     * set to that expired copy — carried here by
     * {@link MessagesUpdate.MessageUpdate#groupInviteMessage()}.
     * When the key is a bare remote JID (no message id), Baileys skips
     * the emission and so does this method.
     *
     * <p>Increment 8: Baileys then upserts a synthetic
     * {@code GROUP_PARTICIPANT_ADD} message (the
     * {@code upsertMessage} call after the emission,
     * {@code lib/Socket/communities.js:347} area), and so does this
     * method, in the same order (expiry emission first, synthetic
     * upsert second, and the synthetic upsert runs even for a
     * bare-JID key, as in Baileys). Baileys' synthetic key is
     * {@code {remoteJid: groupJid, id: generateMessageIDV2(me.id),
     * fromMe: false, participant: inviterRemoteJid}} with
     * {@code messageStubType: GROUP_PARTICIPANT_ADD},
     * {@code messageStubParameters: [JSON.stringify(creds.me)]},
     * {@code participant: inviterRemoteJid} and
     * {@code messageTimestamp: unixTimestampSeconds()}, and no
     * message content. wa-java's {@link MessageReceiver.Decoded}
     * carries no stub-type/stub-parameters/fromMe fields, so the
     * dispatched upsert carries exactly the key-equivalent fields
     * that model can hold — sender = inviter (the participant),
     * group = the community JID, id = the V2 id, timestamp = now
     * in epoch seconds, no message content — and the stub type and
     * the JSON(me) parameter are not carried (documented loss, not
     * invented fields). The synthetic message is also fed to the
     * in-memory store with {@code fromMe = false}, mirroring the
     * inbound path's {@code memoryStore.upsertMessage(decoded)}
     * call; it does not re-fire the legacy {@code onMessage}
     * callback, matching the placeholder-response dispatch-only
     * precedent for synthetic upserts.
     *
     * @param inviterRemoteJid inviter's JID (Baileys' {@code key.remoteJid}, the {@code admin} attr)
     * @param messageId invite message id, or null/blank for a bare-JID key (no emission)
     * @param fromMe Baileys key {@code fromMe} for the emitted update
     * @param participant Baileys key {@code participant}, or null
     * @param inviteMessage the invite message to accept; its group JID, code and expiration build the IQ
     * @return future resolving to the community JID from the response ({@code results.attrs.from}), or null
     */
    public CompletableFuture<String> acceptCommunityInviteV4(String inviterRemoteJid, String messageId,
                                                              boolean fromMe, String participant,
                                                              Wa.Message.GroupInviteMessage inviteMessage) {
        if (inviterRemoteJid == null || inviterRemoteJid.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("inviterRemoteJid must not be blank"));
        }
        if (inviteMessage == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("inviteMessage must not be null"));
        }
        BinaryNode iq = CommunityQuery.buildAcceptInviteV4(newIqId(), inviteMessage.getGroupJid(),
            inviteMessage.getInviteCode(), inviteMessage.getInviteExpiration(), inviterRemoteJid);
        return sendIqAsync(iq).thenApply(resp -> {
            if (messageId != null && !messageId.isBlank()) {
                Wa.Message.GroupInviteMessage expired = CommunityQuery.expireInviteMessage(inviteMessage);
                dispatchEvent(BaileysEventType.MESSAGES_UPDATE, new MessagesUpdate(List.of(
                    new MessagesUpdate.MessageUpdate(messageId, inviterRemoteJid, fromMe, participant,
                        null, null, null, expired))));
            }
            // Increment 9: full GROUP_PARTICIPANT_ADD fidelity (Baileys
            // lib/Socket/communities.js:358-359): the synthetic upsert
            // carries messageStubType GROUP_PARTICIPANT_ADD and
            // messageStubParameters [JSON.stringify(creds.me)].
            MessageReceiver.Decoded synthetic = new MessageReceiver.Decoded(
                inviterRemoteJid, inviteMessage.getGroupJid(), generateMessageIdV2(), null,
                null, null, null, System.currentTimeMillis() / 1000L,
                Wa.WebMessageInfo.StubType.GROUP_PARTICIPANT_ADD,
                List.of(meStubParameterJson(creds)));
            dispatchEvent(BaileysEventType.MESSAGES_UPSERT, new MessageUpsert(List.of(synthetic), "notify"));
            memoryStore.upsertMessage(synthetic, false);
            return resp.attr("from");
        });
    }

    /**
     * Increment 9: serialises the account's {@code me} for a
     * {@code messageStubParameters} entry, mirroring Baileys'
     * {@code JSON.stringify(authState.creds.me)}
     * ({@code lib/Socket/communities.js:359}).
     * <p>
     * Baileys' {@code me} shape: {@code {id, name}} at pairing
     * ({@code lib/Socket/socket.js} pairing-code path) with
     * {@code lid} added later ({@code creds.update} merge). wa-java's
     * {@link AuthCreds} carries exactly those three fields under
     * different names — {@code meJid} (bare JID, matching Baileys'
     * device-less {@code me.id}), {@code pushName} (the account
     * display name, Baileys' {@code me.name} analogue), and
     * {@code meLid} — so the JSON carries {@code id}/{@code name}/
     * {@code lid} in Baileys' key order, omitting any blank field.
     * Field difference, stated not hidden: wa-java has no separate
     * business/profile name distinct from {@code pushName}, so a
     * business account's {@code me.name} may differ from Baileys'.
     * With no usable {@code me} at all (null creds / blank
     * {@code meJid}) the result is {@code "{}"}, matching
     * {@code JSON.stringify} of an empty {@code me} object.
     * Package-private so tests can assert the exact serialisation.
     */
    static String meStubParameterJson(AuthCreds creds) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        String id = creds != null && creds.meJid != null && !creds.meJid.isBlank()
            ? Jid.bare(creds.meJid) : null;
        String name = creds != null && creds.pushName != null && !creds.pushName.isBlank()
            ? creds.pushName : null;
        String lid = creds != null && creds.meLid != null && !creds.meLid.isBlank()
            ? creds.meLid : null;
        for (String[] field : new String[][]{{"id", id}, {"name", name}, {"lid", lid}}) {
            if (field[1] == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(field[0]).append("\":\"").append(jsonEscape(field[1])).append('"');
        }
        return sb.append('}').toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Increment 7: revokes a community V4 invite (Baileys
     * {@code communityRevokeInviteV4},
     * {@code lib/Socket/communities.js:309}). Sends the
     * {@code <revoke><participant jid="..."/>} community IQ built by
     * {@link CommunityQuery#buildRevokeInviteV4} — the builder
     * already existed from Increment 5; this is its facade, mirroring
     * {@link #acceptCommunityInviteV4}'s IQ/response pattern.
     * Baileys returns {@code true} for any non-null result; wa-java
     * returns true when a response node arrived, false only when the
     * response itself is null.
     *
     * @param communityJid community JID
     * @param invitedJid JID of the invited person whose invite is revoked
     * @return future resolving to true on a response, false on a null response
     */
    public CompletableFuture<Boolean> revokeCommunityInviteV4(String communityJid, String invitedJid) {
        if (communityJid == null || communityJid.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("communityJid must not be blank"));
        }
        if (invitedJid == null || invitedJid.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invitedJid must not be blank"));
        }
        BinaryNode iq = CommunityQuery.buildRevokeInviteV4(newIqId(), communityJid, invitedJid);
        return sendIqAsync(iq).thenApply(resp -> resp != null);
    }

    public CompletableFuture<String> requestPairingCode(String phoneNumber, String customCode) {
        if (creds.account != null) {
            throw new IllegalStateException("already paired (creds.account is set): cannot request a pairing code");
        }
        pairCodeHandler = new PairingCodeHandler(creds);
        String iqId = newIqId();
        BinaryNode iq = pairCodeHandler.buildCompanionHello(iqId, phoneNumber, customCode);
        try { store.save(creds); } catch (Exception e) { LOG.warn("save creds failed", e); }
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("companion_hello rejected: " + resp);
            }
            return pairCodeHandler.pairingCode();
        });
    }

    public CompletableFuture<String> sendMessage(String chatJid, Wa.Message msg) {
        if (chatJid.endsWith("@newsletter")) {
            return sendNewsletterMessage(chatJid, msg);
        }
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendMessage(
            MessageReceiver.Decoded target, Wa.Message msg) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        if (!chatJid.endsWith("@g.us")) {
            Jid j = Jid.parse(chatJid);
            if (j != null) {
                chatJid = j.user() + "@" + j.server();
            }
        }
        return sendMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendText(String chatJid, String text) {
        return sendMessage(chatJid, MessageEncoder.text(text));
    }

    public CompletableFuture<String> sendText(
            MessageReceiver.Decoded target, String text) {
        return sendMessage(target, MessageEncoder.text(text));
    }

    public CompletableFuture<String> sendTextWithMentions(
            String chatJid, String text, List<String> mentionedBareJids) {
        Wa.Message msg = MessageEncoder.textWithMentions(text, mentionedBareJids);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendPoll(
            String chatJid, String name, List<String> options, int selectableCount) {
        Wa.Message msg = MessageEncoder.pollMessage(name, options, selectableCount);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    /**
     * Increment 2: sends an album (Baileys {@code album} content). The album
     * declaration message is sent first; its message id becomes the parent
     * key that each member is associated with via
     * {@link MessageEncoder#withAlbumAssociation}, in member order. Members
     * must be image or video messages — Baileys' album validation accepts
     * only image/video items.
     *
     * @param chatJid destination chat JID
     * @param mediaMembers album member media messages, at least two
     * @return future of the sent message ids: album declaration first, then
     *         one id per member, in order
     */
    public CompletableFuture<List<String>> sendAlbum(String chatJid, List<Wa.Message> mediaMembers) {
        if (mediaMembers == null || mediaMembers.size() < 2) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("an album needs at least two media members"));
        }
        int images = 0;
        int videos = 0;
        for (Wa.Message m : mediaMembers) {
            if (m == null) {
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("album members must not be null"));
            }
            if (m.hasImageMessage()) {
                images++;
            } else if (m.hasVideoMessage()) {
                videos++;
            } else {
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("album members must be image or video messages"));
            }
        }
        Wa.Message album = MessageEncoder.albumMessage(images, videos);
        return sendMessage(chatJid, album).thenCompose(albumId -> {
            Wa.MessageKey parentKey = Wa.MessageKey.newBuilder()
                .setRemoteJid(chatJid)
                .setFromMe(true)
                .setId(albumId)
                .build();
            CompletableFuture<List<String>> chain =
                CompletableFuture.completedFuture(new ArrayList<>(List.of(albumId)));
            for (int i = 0; i < mediaMembers.size(); i++) {
                final int index = i;
                final Wa.Message member = MessageEncoder.withAlbumAssociation(
                    mediaMembers.get(i), parentKey, index);
                chain = chain.thenCompose(ids -> sendMessage(chatJid, member).thenApply(id -> {
                    List<String> next = new ArrayList<>(ids);
                    next.add(id);
                    return next;
                }));
            }
            return chain;
        });
    }

    public CompletableFuture<String> sendLocation(
            String chatJid, double latitude, double longitude, String name, String address) {
        Wa.Message msg = MessageEncoder.locationMessage(latitude, longitude, name, address);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendContact(
            String chatJid, String displayName, String vcard) {
        Wa.Message msg = MessageEncoder.contactMessage(displayName, vcard);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendStatusText(
            String text, int backgroundArgb, int fontType) {
        Wa.Message msg = MessageEncoder.statusTextMessage(text, backgroundArgb, fontType);
        return sendDmMessage("status@broadcast", msg);
    }

    public CompletableFuture<String> sendStatusMedia(Wa.Message mediaMsg) {
        return sendDmMessage("status@broadcast", mediaMsg);
    }

    /**
     * Increment 4: sends a status message mentioning an array of JIDs
     * (Baileys {@code sendMessage} array-JID branch, "Lia@Changes
     * 13-03-26 — Add status mentions!", {@code lib/Socket/messages-send.js}).
     * The content is relayed once to {@code status@broadcast} with the
     * expanded participant list (groups replaced by their participants,
     * duplicates dropped) and a
     * {@code <meta><mentioned_users>} node listing the original
     * targets; then one mention message per original target is relayed
     * ({@code statusMentionMessage} / {@code groupStatusMentionMessage}
     * wrapping a protocol message pointing at the status message's key
     * with type 25, plus a {@code <meta is_status_mention>} /
     * {@code <meta is_group_status_mention>} node), with the default
     * 1500ms delay between targets. Group-metadata lookup failures are
     * logged and that group simply contributes no expanded users
     * (Baileys logs the error and continues); the group still appears
     * in {@code mentioned_users} and still gets a mention attempt.
     * This method introduces no silent-failure change to any existing
     * send path; failures of the status relay or of a mention relay
     * fail the returned future.
     *
     * @param targets original array-JID input (users and/or groups)
     * @param content status content message
     * @return future of the status message id
     */
    public CompletableFuture<String> sendStatusMention(List<String> targets, Wa.Message content) {
        return sendStatusMention(targets, content, StatusMentionBuilder.DEFAULT_DELAY_MS);
    }

    /**
     * Increment 4: {@link #sendStatusMention(List, Wa.Message)} with an
     * injectable inter-target delay. Pass 0 in tests to skip sleeping;
     * production callers should use the default-delay overload.
     *
     * @param targets original array-JID input (users and/or groups)
     * @param content status content message
     * @param delayMs delay between per-target mention relays, in milliseconds
     * @return future of the status message id
     */
    public CompletableFuture<String> sendStatusMention(List<String> targets, Wa.Message content,
            long delayMs) {
        if (targets == null || targets.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("status mention targets must not be empty"));
        }
        if (content == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("status mention content must not be null"));
        }
        List<String> distinctGroups = new ArrayList<>();
        for (String target : targets) {
            if (target != null && StatusMentionBuilder.isGroupJid(target)
                    && !distinctGroups.contains(target)) {
                distinctGroups.add(target);
            }
        }
        ConcurrentHashMap<String, List<String>> groupParticipants = new ConcurrentHashMap<>();
        List<CompletableFuture<?>> resolutions = new ArrayList<>();
        for (String groupJid : distinctGroups) {
            GroupListQuery.GroupInfo cached = groupCache.get(groupJid);
            CompletableFuture<GroupListQuery.GroupInfo> infoFuture =
                cached != null ? CompletableFuture.completedFuture(cached) : queryGroupInfo(groupJid);
            resolutions.add(infoFuture.thenAccept(info -> {
                if (info != null) {
                    groupParticipants.put(groupJid, info.participantJids());
                }
            }).exceptionally(ex -> {
                LOG.warn("Status mention: group metadata lookup failed for {} (continuing): {}",
                    groupJid, ex.getMessage());
                return null;
            }));
        }
        return CompletableFuture.allOf(resolutions.toArray(new CompletableFuture<?>[0]))
            .thenCompose(ignored -> {
                List<String> expanded =
                    StatusMentionBuilder.expandMentionTargets(targets, groupParticipants::get);
                Jid myJid = Jid.parse(creds.meJid);
                if (myJid == null) {
                    return CompletableFuture.<String>failedFuture(
                        new IllegalStateException("creds.meJid invalid"));
                }
                String ownBareJid = myJid.user() + "@" + Jid.SERVER_WHATSAPP;
                String statusMsgId = generateMessageId();
                Wa.MessageKey statusKey = Wa.MessageKey.newBuilder()
                    .setRemoteJid(StatusMentionBuilder.STATUS_JID)
                    .setFromMe(true)
                    .setId(statusMsgId)
                    .build();
                StatusMentionBuilder.StatusMentionPlan plan = StatusMentionBuilder.plan(
                    targets, groupParticipants::get, statusKey);
                Wa.Message statusContent =
                    wrapEphemeralIfNeeded(StatusMentionBuilder.STATUS_JID, content);
                LinkedHashSet<String> queryTargets = new LinkedHashSet<>(expanded);
                queryTargets.add(ownBareJid);
                return queryDevices(queryTargets).thenCompose(devicesMap -> {
                    List<String> allDeviceJids = new ArrayList<>();
                    for (var entry : devicesMap.entrySet()) {
                        Jid base = Jid.parse(entry.getKey());
                        if (base == null) continue;
                        for (var d : entry.getValue()) {
                            allDeviceJids.add(d.asJid(base.user(), base.server()).asString());
                        }
                    }
                    return fetchBundlesAndInstallSessions(allDeviceJids).thenCompose(addresses -> {
                        messageSendLock.lock();
                        try {
                            MessageSender.Result result = MessageSender.buildDualIdentityStanza(
                                protocolStore, creds, statusMsgId, StatusMentionBuilder.STATUS_JID,
                                allDeviceJids, allDeviceJids, statusContent,
                                List.of(plan.mentionedUsersMeta()));
                            send(result.stanza());
                            LOG.info("Sent status mention id={} to status@broadcast ({} participant device(s), {} target(s))",
                                statusMsgId, allDeviceJids.size(), plan.mentions().size());
                        } finally {
                            messageSendLock.unlock();
                        }
                        // Per-target mentions, in original-target order
                        // (the plan's map is unordered, so the input order
                        // is replayed here), delaying between targets.
                        LinkedHashSet<String> orderedTargets = new LinkedHashSet<>();
                        for (String target : targets) {
                            if (target != null && !target.isBlank()) {
                                orderedTargets.add(target);
                            }
                        }
                        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                        for (String target : orderedTargets) {
                            StatusMentionBuilder.Mention mention = plan.mentions().get(target);
                            if (mention == null) continue;
                            CompletableFuture<String> sendFuture =
                                StatusMentionBuilder.isGroupJid(target)
                                    ? sendGroupMessage(target, mention.message(),
                                        List.of(mention.metaNode()), false)
                                    : sendDmMessage(target, mention.message(),
                                        List.of(mention.metaNode()), false);
                            chain = chain.thenCompose(v -> sendFuture
                                .thenCompose(id -> delayStage(delayMs)));
                        }
                        return chain.thenApply(v -> statusMsgId);
                    });
                });
            });
    }

    /** Delayed stage for the status-mention loop; a non-positive delay completes immediately. */
    private static CompletableFuture<Void> delayStage(long delayMs) {
        if (delayMs <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {},
            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS));
    }

    /**
     * Sends a text message to a newsletter (channel).
     *
     * <p>Scoped fail-fast: when disconnected, the returned future
     * fails promptly with an {@link IllegalStateException} instead
     * of completing with a message id for a stanza that
     * {@link #send(BinaryNode)} dropped.
     *
     * @param newsletterJid newsletter (channel) JID
     * @param text message text
     * @return future of the message id, failing promptly when disconnected
     */
    public CompletableFuture<String> sendNewsletterText(
            String newsletterJid, String text) {
        if (isDisconnected()) {
            return CompletableFuture.failedFuture(disconnectedFailure());
        }
        String msgId = generateMessageId();
        byte[] msgBytes = MessageEncoder.encode(MessageEncoder.text(text));
        BinaryNode node = new BinaryNode("message",
            Map.of(
                "to", newsletterJid,
                "type", "text",
                "id", msgId
            ),
            List.of(new BinaryNode("plaintext", Map.of(), msgBytes)));
        send(node);
        return CompletableFuture.completedFuture(msgId);
    }

    /**
     * Sends (or, with a blank reaction code, removes) a reaction to a
     * newsletter message.
     *
     * <p>Scoped fail-fast: when disconnected, the returned future
     * fails promptly with an {@link IllegalStateException} instead
     * of completing with a message id for a stanza that
     * {@link #send(BinaryNode)} dropped.
     *
     * @param newsletterJid newsletter (channel) JID
     * @param serverId server id of the message being reacted to
     * @param reactionCode reaction code, or blank to remove the reaction
     * @return future of the message id, failing promptly when disconnected
     */
    public CompletableFuture<String> sendNewsletterReaction(
            String newsletterJid, String serverId, String reactionCode) {
        if (isDisconnected()) {
            return CompletableFuture.failedFuture(disconnectedFailure());
        }
        String msgId = generateMessageId();
        Map<String, String> attrs = new HashMap<>();
        attrs.put("to", newsletterJid);
        attrs.put("type", "reaction");
        attrs.put("server_id", serverId);
        attrs.put("id", msgId);
        if (reactionCode == null || reactionCode.isBlank()) {
            attrs.put("edit", "7");
        }
        BinaryNode reactNode = new BinaryNode("reaction",
            reactionCode != null && !reactionCode.isBlank() ? Map.of("code", reactionCode) : Map.of(),
            null);
        BinaryNode msgNode = new BinaryNode("message", attrs, List.of(reactNode));
        send(msgNode);
        return CompletableFuture.completedFuture(msgId);
    }

    public CompletableFuture<BinaryNode> fetchNewsletterMessages(
            String newsletterJid, int count) {
        String iqId = newIqId();
        BinaryNode node = new BinaryNode("iq",
            Map.of(
                "id", iqId,
                "type", "get",
                "xmlns", "newsletter",
                "to", newsletterJid
            ),
            List.of(new BinaryNode("message_updates", Map.of("count", String.valueOf(count)), null)));
        return sendIqAsync(node);
    }

    public byte[] downloadMedia(Wa.Message msg) {
        return MediaDownloader.download(msg);
    }

    public byte[] downloadMedia(MessageReceiver.Decoded target) {
        return MediaDownloader.download(target);
    }

    public CompletableFuture<Void> acceptTOSNotice(String noticeId, String stage) {
        BinaryNode notice = new BinaryNode("notice", Map.of("id", noticeId, "stage", stage), null);
        BinaryNode iq = new BinaryNode("iq", Map.of(
            "id", generateMessageId(),
            "type", "set",
            "xmlns", "tos",
            "to", Jid.SERVER_WHATSAPP
        ), List.of(notice));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public CompletableFuture<BinaryNode> updateProfileStatus(String statusText) {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "status", "id", iqId),
            List.of(new BinaryNode("status", Map.of(), statusText.getBytes(StandardCharsets.UTF_8))));
        return sendIqAsync(iq);
    }

    public CompletableFuture<String> setStatusMessage(String statusText) {
        String escaped = statusText != null ? statusText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") : "";
        String vars = "{\"input\":{\"text\":\"" + escaped + "\"}}";
        return sendMexQuery("9152604461510864", vars);
    }

    public CompletableFuture<String> fetchProfilePictureUrl(String targetJid) {
        return getProfilePictureInfo(targetJid, true).thenApply(info -> info != null ? info.url() : null);
    }

    public CompletableFuture<BinaryNode> updateGroupPicture(
            String groupJid, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return removeGroupPicture(groupJid);
        }
        String iqId = newIqId();
        BinaryNode picNode = new BinaryNode("picture",
            Map.of("type", "image"),
            imageBytes);
        BinaryNode iq = new BinaryNode("iq",
            Map.of(
                "to", Jid.SERVER_WHATSAPP,
                "target", groupJid,
                "type", "set",
                "xmlns", "w:profile:picture",
                "id", iqId
            ),
            List.of(picNode));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> removeGroupPicture(String groupJid) {
        String iqId = newIqId();
        BinaryNode picNode = new BinaryNode("picture",
            Map.of("type", "delete"),
            null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of(
                "to", Jid.SERVER_WHATSAPP,
                "target", groupJid,
                "type", "set",
                "xmlns", "w:profile:picture",
                "id", iqId
            ),
            List.of(picNode));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> updateProfilePicture(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return removeProfilePicture();
        }
        String iqId = newIqId();
        BinaryNode picNode = new BinaryNode("picture",
            Map.of("type", "image"),
            imageBytes);
        BinaryNode iq = new BinaryNode("iq",
            Map.of(
                "to", Jid.SERVER_WHATSAPP,
                "type", "set",
                "xmlns", "w:profile:picture",
                "id", iqId
            ),
            List.of(picNode));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> removeProfilePicture() {
        String iqId = newIqId();
        BinaryNode picNode = new BinaryNode("picture",
            Map.of("type", "delete"),
            null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of(
                "to", Jid.SERVER_WHATSAPP,
                "type", "set",
                "xmlns", "w:profile:picture",
                "id", iqId
            ),
            List.of(picNode));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> updateBlockStatus(String targetJid, boolean block) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode("item", Map.of("action", block ? "block" : "unblock", "jid", targetJid), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "blocklist", "id", iqId),
            List.of(item));
        // Increment 2: a successful block/unblock is also a blocklist
        // change for event consumers (Baileys emits blocklist.update from
        // account_sync notifications; a local change has no notification,
        // so the client emits it on success).
        return sendIqAsync(iq).thenApply(resp -> {
            dispatchEvent(BaileysEventType.BLOCKLIST_UPDATE,
                new BlocklistUpdate(List.of(targetJid), block ? "add" : "remove"));
            return resp;
        });
    }

    public CompletableFuture<List<String>> fetchBlockList() {
        return getBlocklist().thenApply(bl -> {
            List<String> list = new ArrayList<>();
            for (var item : bl.items()) {
                list.add(item.lid() != null ? item.lid() : item.pn());
            }
            return list;
        });
    }

    public CompletableFuture<Blocklist> getBlocklist(String dhash) {
        String iqId = newIqId();
        List<BinaryNode> content = null;
        if (dhash != null && !dhash.isBlank()) {
            content = List.of(new BinaryNode("item", Map.of("dhash", dhash), null));
        }
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "blocklist", "id", iqId),
            content);
        return sendIqAsync(iq).thenApply(this::parseBlocklistNode);
    }

    public CompletableFuture<Blocklist> getBlocklist() {
        return getBlocklist(null);
    }

    public CompletableFuture<Blocklist> updateBlocklist(String jid, boolean block) {
        String iqId = newIqId();
        String targetJid = jid.contains("@") ? jid : jid + "@s.whatsapp.net";
        Map<String, String> itemAttrs = new HashMap<>();
        itemAttrs.put("jid", targetJid);
        itemAttrs.put("action", block ? "block" : "unblock");
        BinaryNode item = new BinaryNode("item", itemAttrs, null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "blocklist", "id", iqId),
            List.of(item));
        // Increment 2: see updateBlockStatus — emit the blocklist change
        // on success, since no account_sync notification follows a local
        // change.
        return sendIqAsync(iq).thenApply(this::parseBlocklistNode).thenApply(bl -> {
            dispatchEvent(BaileysEventType.BLOCKLIST_UPDATE,
                new BlocklistUpdate(List.of(targetJid), block ? "add" : "remove"));
            return bl;
        });
    }

    private Blocklist parseBlocklistNode(BinaryNode resp) {
        BinaryNode listNode = resp.child("list");
        if (listNode == null) {
            return new Blocklist(null, null, List.of());
        }
        String dhash = listNode.attr("dhash");
        String addressingMode = listNode.attr("addressing_mode");
        List<BlocklistItem> items = new ArrayList<>();
        for (var item : listNode.childrenList()) {
            if ("item".equals(item.tag()) && item.attr("jid") != null) {
                String itemJid = item.attr("jid");
                String pnJid = item.attr("pn_jid");
                boolean active = "true".equalsIgnoreCase(item.attr("active"));
                if (itemJid != null && itemJid.endsWith("@lid") && pnJid != null && pnJid.endsWith("@s.whatsapp.net")) {
                    LID_TO_PN_MAP.put(itemJid, pnJid);
                }
                items.add(new BlocklistItem(itemJid, pnJid, active));
            }
        }
        return new Blocklist(dhash, addressingMode, items);
    }

    public CompletableFuture<List<StatusPrivacy>> getStatusPrivacy() {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "status", "id", iqId),
            List.of(new BinaryNode("privacy", Map.of(), null)));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode privacyNode = resp.child("privacy");
            if (privacyNode == null) return List.of();
            List<StatusPrivacy> list = new ArrayList<>();
            for (BinaryNode child : privacyNode.childrenList()) {
                if (!"list".equals(child.tag())) continue;
                String type = child.attr("type");
                boolean isDefault = "true".equalsIgnoreCase(child.attr("default"));
                List<String> users = new ArrayList<>();
                for (BinaryNode u : child.childrenList()) {
                    if ("user".equals(u.tag()) && u.attr("jid") != null) {
                        users.add(u.attr("jid"));
                    }
                }
                list.add(new StatusPrivacy(type, isDefault, users));
            }
            return list;
        });
    }

    public CompletableFuture<BinaryNode> pinChat(String chatJid, boolean pin) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode("pin", Map.of("jid", chatJid, "action", pin ? "pin" : "unpin"), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "action", "id", iqId),
            List.of(item));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> archiveChat(String chatJid, boolean archive) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode("archive", Map.of("jid", chatJid, "action", archive ? "archive" : "unarchive"), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "action", "id", iqId),
            List.of(item));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> muteChat(String chatJid, long durationSeconds) {
        String iqId = newIqId();
        Map<String, String> attrs = new HashMap<>();
        attrs.put("jid", chatJid);
        if (durationSeconds > 0) {
            attrs.put("type", "set");
            attrs.put("time", String.valueOf(durationSeconds));
        } else {
            attrs.put("type", "delete");
        }
        BinaryNode item = new BinaryNode("mute", attrs, null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "action", "id", iqId),
            List.of(item));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> starMessage(String chatJid, String msgId, boolean star) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode("star", Map.of("jid", chatJid, "id", msgId, "action", star ? "star" : "unstar"), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "action", "id", iqId),
            List.of(item));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> deleteChat(String chatJid) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode("chat", Map.of("jid", chatJid, "action", "delete"), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "action", "id", iqId),
            List.of(item));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> clearChat(String chatJid) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode("chat", Map.of("jid", chatJid, "action", "clear"), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "action", "id", iqId),
            List.of(item));
        return sendIqAsync(iq);
    }

    public CompletableFuture<String>
            sendDmMessage(String toUser, Wa.Message msg) {
        return sendDmMessage(toUser, msg, List.of(), true);
    }

    /**
     * Increment 4: DM send with Baileys {@code relayMessage}
     * {@code additionalNodes} appended to the stanza (see
     * {@link MessageSender#buildDualIdentityStanza(JaWaProtocolStore, AuthCreds, String, String, List, List, Wa.Message, List)}).
     * With an empty node list this behaves exactly like
     * {@link #sendDmMessage(String, Wa.Message)}. The
     * {@code applyEphemeralWrap} flag exists for status-mention
     * relays, which Baileys generates without ephemeral wrapping;
     * normal sends pass true.
     */
    public CompletableFuture<String>
            sendDmMessage(String toUser, Wa.Message msg, List<BinaryNode> additionalNodes,
                    boolean applyEphemeralWrap) {
        return sendDmMessage(toUser, msg, additionalNodes, Map.of(), applyEphemeralWrap);
    }

    /**
     * Increment 6: DM send with Baileys {@code relayMessage}
     * {@code additionalAttributes} merged onto the stanza (see
     * {@link MessageSender#withAdditionalAttributes(BinaryNode, Map)}),
     * in addition to {@code additionalNodes}. With empty attributes
     * this behaves exactly like
     * {@link #sendDmMessage(String, Wa.Message, List, boolean)}.
     * The PDO send ({@link #sendPeerDataOperationMessage}) uses this
     * overload for its {@code category}/{@code push_priority}
     * attributes.
     */
    public CompletableFuture<String>
            sendDmMessage(String toUser, Wa.Message msg, List<BinaryNode> additionalNodes,
                    Map<String, String> additionalAttributes, boolean applyEphemeralWrap) {
        final Wa.Message outboundMsg = applyEphemeralWrap ? wrapEphemeralIfNeeded(toUser, msg) : msg;
        String ownBareJid;
        if (toUser != null && toUser.endsWith("@lid") && creds.meLid != null && !creds.meLid.isBlank()) {
            Jid lidJid = Jid.parse(creds.meLid);
            ownBareJid = lidJid != null ? lidJid.user() + "@lid" : creds.meLid;
        } else {
            Jid myJid = Jid.parse(creds.meJid);
            if (myJid == null) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("creds.meJid invalid"));
            }
            ownBareJid = myJid.user() + "@" + Jid.SERVER_WHATSAPP;
        }
        boolean isSelfSend = toUser != null && toUser.equals(ownBareJid);
        List<String> queryTargets = isSelfSend
            ? List.of(toUser)
            : List.of(toUser, ownBareJid);

        Jid ownJidObj = Jid.parse(ownBareJid);
        String ownUser = ownJidObj != null ? ownJidObj.user() : "";
        Jid targetJidObj = Jid.parse(toUser);

        return queryDevices(queryTargets).thenCompose(devicesMap -> {
            List<String> allDeviceJids = new ArrayList<>();
            for (var entry : devicesMap.entrySet()) {
                Jid base = Jid.parse(entry.getKey());
                if (base == null) continue;
                for (var d : entry.getValue()) {
                    allDeviceJids.add(d.asJid(base.user(), base.server()).asString());
                }
            }

            Map<String, String> finalAttrs = new HashMap<>(additionalAttributes);

            return fetchBundlesAndInstallSessions(allDeviceJids).thenApply(addresses -> {
                String msgId = generateMessageId();
                messageSendLock.lock();
                try {
                    MessageSender.Result result = MessageSender.buildDualIdentityStanza(
                        protocolStore, creds, msgId, toUser,
                        allDeviceJids, allDeviceJids, outboundMsg, additionalNodes);
                    send(MessageSender.withAdditionalAttributes(result.stanza(), finalAttrs));
                    int ownCount = countOwnDevices(allDeviceJids, ownUser);
                    LOG.info("Sent message id={} to={} ({} device(s) total, {} own-companion DSM)",
                        msgId, toUser, allDeviceJids.size(),
                        Math.max(0, ownCount - 1));
                    return msgId;
                } finally {
                    messageSendLock.unlock();
                }
            });
        });
    }

    /**
     * Increment 6: Baileys {@code sendPeerDataOperationMessage}
     * ({@code lib/Socket/messages-send.js}). Relays the given PDO
     * request to the account's own bare JID
     * ({@code jidNormalizedUser(me.id)}; here {@link Jid#bare} of
     * {@code creds.meJid}) with stanza attributes
     * {@code category=peer} / {@code push_priority=high_force} and the
     * {@code <meta appdata='default'/>} additional node, via the
     * Increment 4 additional-nodes DM path plus the Increment 6
     * additional-attributes overload. Protocol messages are exempt
     * from ephemeral wrapping in {@link #wrapEphemeralIfNeeded}, so
     * the PDO payload is never ephemeral-wrapped.
     *
     * <p>Increment 8: the live peer-mode wiring Increment 7 specified
     * is now in place for the companion case. Baileys' peer relay
     * ({@code relayMessage} with {@code category: 'peer'}) skips
     * USync device enumeration, so this method bypasses
     * {@code queryDevices} entirely: it fetches/installs a session
     * for the single own device-0 JID only (the bare own JID, whose
     * Signal address is {@code (user, 0)}), encrypts the PDO message
     * for that device with
     * {@link MessageSender#buildPeerStanza}, reframes with
     * {@link MessageSender#peerFrame} (bare {@code <enc>} first,
     * {@code <device-identity>} second when a pre-key message was
     * produced), and sends with the peer attributes and the
     * {@code <meta appdata='default'/>} node. The DM/group builders
     * are untouched, so DM/group behaviour cannot change through
     * this path.
     * <p>Fallback, stated not hidden: when the client's own device
     * is 0 (a bare {@code creds.meJid}, as the pairing flow leaves
     * it), device 0 <em>is</em> the sender, so there is no distinct
     * device-0 recipient to encrypt for — Baileys' own relay loop
     * skips the exact sender device in that case too — and this
     * method keeps the Increment 6 self-DM path (enumerated own
     * devices, {@code <participants>} framing) instead of sending a
     * stanza encrypted to the sender itself. Likewise, when no
     * protocol store exists yet (never connected), the peer path
     * fails fast rather than silently dropping the stanza the way
     * {@link #send(BinaryNode)} does for a disconnected transport.
     * <p>Scoped fail-fast (2026-10-27 decision): when disconnected
     * and no capture seam is set, the returned future fails promptly
     * with an {@link IllegalStateException} instead of completing
     * with a message id for a stanza {@link #send(BinaryNode)}
     * would drop. The capture seam
     * ({@code messageSenderForTesting}) stands in for the transport
     * in tests, so it bypasses this guard — with the seam set, the
     * stanza is captured as before. The DM/group send paths this
     * method delegates to for the device-0 case are unchanged.
     *
     * @param pdo caller-built PDO request (type and payload owned by the caller)
     * @return future of the sent message id; fails when not
     *         authenticated, mirroring Baileys' {@code HttpError('Not authenticated')}
     */
    public CompletableFuture<String> sendPeerDataOperationMessage(
            Wa.Message.PeerDataOperationRequestMessage pdo) {
        if (pdo == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("pdo must not be null"));
        }
        if (creds == null || creds.meJid == null || creds.meJid.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not authenticated"));
        }
        if (isDisconnected() && messageSenderForTesting == null) {
            return CompletableFuture.failedFuture(disconnectedFailure());
        }
        String ownBareJid = Jid.bare(creds.meJid);
        Wa.Message msg = PeerDataOperations.buildPeerDataOperationMessage(pdo);
        Jid myJid = Jid.parse(creds.meJid);
        if (myJid == null || myJid.device() == 0) {
            // Device-0-is-the-sender: keep the Increment 6 self-DM
            // path (see Javadoc above).
            return sendDmMessage(ownBareJid, msg,
                List.of(PeerDataOperations.peerMetaNode()),
                PeerDataOperations.peerAttributes(), true);
        }
        if (protocolStore == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Not connected: no protocol store"));
        }
        String device0Jid = new Jid(myJid.user(), myJid.server(), 0, 0, myJid.domainType()).asString();
        return fetchBundlesAndInstallSessions(List.of(device0Jid)).thenApply(addresses -> {
            String msgId = generateMessageId();
            messageSendLock.lock();
            try {
                MessageSender.Result result = MessageSender.buildPeerStanza(
                    protocolStore, creds, msgId, ownBareJid, msg,
                    List.of(PeerDataOperations.peerMetaNode()));
                BinaryNode stanza = MessageSender.withAdditionalAttributes(
                    MessageSender.peerFrame(result), PeerDataOperations.peerAttributes());
                if (messageSenderForTesting != null) {
                    messageSenderForTesting.accept(stanza);
                } else {
                    send(stanza);
                }
                LOG.info("Sent peer message id={} to={} (device 0 only)", msgId, ownBareJid);
                return msgId;
            } finally {
                messageSendLock.unlock();
            }
        });
    }

    /**
     * Increment 6: Baileys {@code requestPlaceholderResend}
     * ({@code lib/Socket/messages-recv.js}). Claims the message id in
     * the placeholder-resend cache, waits 2 seconds, re-checks the
     * claim, and — when it survives — sends a
     * {@code PLACEHOLDER_MESSAGE_RESEND} PDO request, scheduling the
     * 8-second expiry for the claim when the send runs.
     *
     * @param messageKey key of the placeholder message (its {@code id} is the cache key)
     * @param msgData original message data preserved for the PDO
     *                response handler, or null to store {@code true}
     * <p>Scoped fail-fast (2026-10-27 decision): the sender stage
     * fails the returned future promptly when disconnected, through
     * the {@link #sendPeerDataOperationMessage} guard (bypassed only
     * when its capture seam stands in for the transport). The claim
     * and 2-second delay are unchanged, and the fire-and-forget
     * auto-send in the decrypt-failure catch still swallows the
     * failure there, as before.
     *
     * @return future of null (resend already requested), {@code "RESOLVED"}
     *         (the real message arrived during the delay), or the sent
     *         PDO message id; failing promptly when disconnected at send time
     */
    public CompletableFuture<String> requestPlaceholderResend(Wa.MessageKey messageKey, Object msgData) {
        if (messageKey == null || messageKey.getId() == null || messageKey.getId().isBlank()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("messageKey with a non-blank id is required"));
        }
        String messageId = messageKey.getId();
        return placeholderResendManager.request(messageId, msgData,
            placeholderResendDelay,
            () -> {
                CompletableFuture.runAsync(() -> {},
                    CompletableFuture.delayedExecutor(
                        PeerDataOperations.PLACEHOLDER_RESEND_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    .thenRun(() -> placeholderResendManager.expire(messageId));
                return sendPeerDataOperationMessage(
                    PeerDataOperations.buildPlaceholderResendRequest(messageKey));
            });
    }

    /**
     * Increment 7 (stretch): consumes one inbound PDO response
     * (called from the receiver's protocol-message branch) and
     * dispatches the resolved placeholder messages as
     * {@code messages.upsert} with type {@code notify}, mirroring
     * Baileys' {@code process-message.js}
     * {@code PEER_DATA_OPERATION_REQUEST_RESPONSE_MESSAGE} case
     * ({@code ev.emit('messages.upsert', {messages: [finalMsg],
     * type: 'notify', requestId: response.stanzaId})}).
     * {@link PeerDataOperations#resolvePlaceholderResendResponses}
     * does the pure work (decode, read stored data, resolve the
     * claim); this method only converts each resolved reply into
     * wa-java's {@link MessageReceiver.Decoded} model and dispatches
     * it. Conversion rule, mirroring Baileys' "apply decoded content
     * onto cached metadata": when the stored data is a
     * {@code Decoded}, its sender/group/encType/text/interactive
     * fields are preserved and only the message content and
     * timestamp come from the phone's reply; otherwise the reply's
     * key supplies sender (participant, else remote JID), group
     * (remote JID when it is a group), message and timestamp, with
     * no text/interactive extraction. Increment 8: the upsert now
     * carries Baileys' {@code requestId} from
     * {@code response.stanzaId} (a blank stanza id becomes null, so
     * the null default for non-PDO upserts is preserved); this is
     * the only PDO-response dispatch path, and the stanza id is
     * available at this call site on the response proto itself, so
     * nothing is lost here. The remaining divergence, stated not
     * hidden: like the edit/revoke derived events above, resolved
     * messages are dispatched only — they are not fed to the
     * in-memory store and do not re-fire the legacy
     * {@code onMessage} callback (Baileys emits only
     * {@code messages.upsert} for them). Package-private so tests in
     * this package can drive it without a Signal session.
     *
     * @param response inbound PDO response message; null dispatches nothing
     * @return the dispatched decoded messages, in response order
     */
    List<MessageReceiver.Decoded> dispatchPlaceholderResendResponses(
            Wa.Message.PeerDataOperationRequestResponseMessage response) {
        List<PeerDataOperations.ResolvedPlaceholder> resolved =
            PeerDataOperations.resolvePlaceholderResendResponses(response, placeholderResendManager);
        List<MessageReceiver.Decoded> out = new ArrayList<>();
        for (PeerDataOperations.ResolvedPlaceholder r : resolved) {
            Wa.WebMessageInfo info = r.info();
            Wa.MessageKey key = info.getKey();
            String msgId = key.getId();
            MessageReceiver.Decoded decoded;
            if (r.storedData() instanceof MessageReceiver.Decoded stored) {
                long ts = info.getMessageTimestamp() > 0 ? info.getMessageTimestamp() : stored.timestamp();
                decoded = new MessageReceiver.Decoded(
                    stored.senderJid(), stored.groupJid(), msgId, stored.encType(),
                    info.hasMessage() ? info.getMessage() : stored.message(),
                    stored.text(), stored.interactive(), ts);
            } else {
                String remoteJid = key.getRemoteJid();
                String participant = key.getParticipant();
                String sender = participant != null && !participant.isEmpty() ? participant : remoteJid;
                String group = remoteJid != null && remoteJid.endsWith("@g.us") ? remoteJid : null;
                decoded = new MessageReceiver.Decoded(
                    sender, group, msgId, null,
                    info.hasMessage() ? info.getMessage() : null,
                    null, null, info.getMessageTimestamp());
            }
            out.add(decoded);
        }
        if (!out.isEmpty()) {
            String requestId = response != null && response.getStanzaId() != null
                && !response.getStanzaId().isBlank() ? response.getStanzaId() : null;
            dispatchEvent(BaileysEventType.MESSAGES_UPSERT, new MessageUpsert(out, "notify", requestId));
        }
        return out;
    }

    /**
     * Increment 6: Baileys {@code updateMemberLabel}
     * ({@code lib/Socket/messages-send.js}). Relays a
     * {@code GROUP_MEMBER_LABEL_CHANGE} protocol message (label
     * truncated to 30 characters, timestamp = current unix seconds)
     * to the caller-supplied JID with the
     * {@code <meta tag_reason='user_update' appdata='member_tag'/>}
     * additional node. Group JIDs go through the group send path
     * (which requires group membership, unlike Baileys' bare relay);
     * other JIDs go through the DM path.
     *
     * @param jid destination JID supplied by the caller (Baileys relays to it verbatim)
     * @param label member label, truncated to 30 characters; null leaves the label field unset
     * @return future of the sent message id
     */
    public CompletableFuture<String> updateMemberLabel(String jid, String label) {
        if (jid == null || jid.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("jid must not be blank"));
        }
        Wa.Message msg = PeerDataOperations.buildMemberLabelMessage(
            label, System.currentTimeMillis() / 1000L);
        BinaryNode meta = PeerDataOperations.memberLabelMetaNode();
        return jid.endsWith("@g.us")
            ? sendGroupMessage(jid, msg, List.of(meta), true)
            : sendDmMessage(jid, msg, List.of(meta), true);
    }

    private static int countOwnDevices(List<String> deviceJids, String ownUser) {
        int n = 0;
        for (String dj : deviceJids) {
            Jid j = Jid.parse(dj);
            if (j != null && ownUser.equals(j.user())) n++;
        }
        return n;
    }

    public CompletableFuture<List<SignalProtocolAddress>>
            fetchBundlesAndInstallSessions(List<String> deviceJids) {
        List<String> missing = new ArrayList<>();
        for (String dj : deviceJids) {
            Jid j = Jid.parse(dj);
            if (j == null) continue;
            SignalProtocolAddress addr = SessionBootstrap.addressFor(j);
            if (!protocolStore.containsSession(addr)) {
                missing.add(dj);
            }
        }
        if (missing.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (missing.size() <= 40) {
            String iqId = newIqId();
            BinaryNode q = PreKeyBundleFetcher.buildFetchStanza(iqId, missing);
            LOG.debug("Sent pre-key fetch IQ id={} for {} devices", iqId, missing.size());
            return sendIqAsync(q).thenApply(resp -> {
                if ("error".equals(resp.attr("type"))) {
                    LOG.warn("Pre-key fetch error: {}", resp);
                    return List.of();
                }
                var bundles = PreKeyBundleFetcher.parseResponse(resp);
                LOG.debug("Parsed {} bundles", bundles.size());
                return SessionBootstrap.installAll(protocolStore, bundles);
            });
        }
        int batchSize = 40;
        List<CompletableFuture<List<SignalProtocolAddress>>> futures = new ArrayList<>();
        for (int i = 0; i < missing.size(); i += batchSize) {
            List<String> chunk = missing.subList(i, Math.min(i + batchSize, missing.size()));
            String iqId = newIqId();
            BinaryNode q = PreKeyBundleFetcher.buildFetchStanza(iqId, chunk);
            futures.add(sendIqAsync(q).thenApply(resp -> {
                if ("error".equals(resp.attr("type"))) {
                    LOG.warn("Pre-key fetch error: {}", resp);
                    return List.of();
                }
                var bundles = PreKeyBundleFetcher.parseResponse(resp);
                return SessionBootstrap.installAll(protocolStore, bundles);
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
            .thenApply(v -> {
                List<SignalProtocolAddress> result = new ArrayList<>();
                for (var f : futures) {
                    result.addAll(f.join());
                }
                return result;
            });
    }

    public CompletableFuture<Void> setDisappearingTimer(String chatJid, int timerSeconds) {
        if (chatJid == null) return CompletableFuture.completedFuture(null);
        setChatEphemeralTimer(chatJid, timerSeconds);
        if (chatJid.endsWith("@g.us")) {
            String iqId = newIqId();
            BinaryNode content = timerSeconds <= 0
                ? new BinaryNode("not_ephemeral", Map.of(), null)
                : new BinaryNode("ephemeral", Map.of("expiration", String.valueOf(timerSeconds)), null);
            BinaryNode iq = new BinaryNode("iq",
                Map.of("to", chatJid, "type", "set", "xmlns", "w:g2", "id", iqId),
                List.of(content));
            return sendIqAsync(iq).thenApply(res -> null);
        } else {
            Wa.Message msg = Wa.Message.newBuilder()
                .setProtocolMessage(Wa.Message.ProtocolMessage.newBuilder()
                    .setType(Wa.Message.ProtocolMessage.Type.EPHEMERAL_SETTING)
                    .setEphemeralExpiration(timerSeconds)
                    .setEphemeralSettingTimestamp(System.currentTimeMillis() / 1000L))
                .build();
            return sendMessage(chatJid, msg).thenApply(res -> null);
        }
    }

    public CompletableFuture<String>
            sendGroupText(String groupJid, String text) {
        return sendGroupMessage(groupJid, MessageEncoder.text(text));
    }

    public CompletableFuture<String>
            sendGroupMessage(String groupJid, Wa.Message msg) {
        return sendGroupMessage(groupJid, msg, List.of(), true);
    }

    /**
     * Increment 4: group send with Baileys {@code relayMessage}
     * {@code additionalNodes} appended to the stanza (see
     * {@link GroupSender#buildStanza(JaWaProtocolStore, SenderKeyStore, AuthCreds, String, String, List, Wa.Message, List)}).
     * With an empty node list this behaves exactly like
     * {@link #sendGroupMessage(String, Wa.Message)}. The
     * {@code applyEphemeralWrap} flag exists for status-mention
     * relays, which Baileys generates without ephemeral wrapping.
     */
    public CompletableFuture<String>
            sendGroupMessage(String groupJid, Wa.Message msg, List<BinaryNode> additionalNodes,
                    boolean applyEphemeralWrap) {
        final Wa.Message outboundMsg = applyEphemeralWrap ? wrapEphemeralIfNeeded(groupJid, msg) : msg;
        GroupListQuery.GroupInfo cached = groupCache.get(groupJid);
        CompletableFuture<GroupListQuery.GroupInfo> targetFuture =
            cached != null
                ? CompletableFuture.completedFuture(cached)
                : queryGroupInfo(groupJid);

        return targetFuture.thenCompose(target -> {
            if (target == null) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("not a member of group: " + groupJid));
            }
            LinkedHashSet<String> bareUsers = new LinkedHashSet<>();
            for (String pj : target.participantJids()) {
                Jid j = Jid.parse(pj);
                if (j == null) continue;
                bareUsers.add(j.user() + "@" + j.server());
            }
            if (bareUsers.isEmpty()) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("group has no participants"));
            }
            return queryDevices(bareUsers).thenCompose(devicesMap -> {
                List<String> allDeviceJids = new ArrayList<>();
                for (var entry : devicesMap.entrySet()) {
                    Jid base = Jid.parse(entry.getKey());
                    if (base == null) continue;
                    for (var d : entry.getValue()) {
                        allDeviceJids.add(d.asJid(base.user(), base.server()).asString());
                    }
                }
                return fetchBundlesAndInstallSessions(allDeviceJids).thenApply(addresses -> {
                    String msgId = generateMessageId();
                    messageSendLock.lock();
                    try {
                        GroupSender.Result result =
                            GroupSender.buildStanza(
                                protocolStore, senderKeyStore, creds, msgId,
                                groupJid, allDeviceJids, outboundMsg, additionalNodes);
                        if (result.phash() != null && !result.phash().isEmpty()) {
                            groupPhashMap.put(groupJid, result.phash());
                        }
                        send(result.stanza());
                        LOG.info("Sent group message id={} to={} ({} participant device(s), SKDM to {}, phash={})",
                            msgId, groupJid, allDeviceJids.size(), result.skdmRecipients(), result.phash());
                        return msgId;
                    } finally {
                        messageSendLock.unlock();
                    }
                });
            });
        });
    }

    /**
     * Sends a structured message to a newsletter (channel).
     *
     * <p>Scoped fail-fast: when disconnected, the returned future
     * fails promptly with an {@link IllegalStateException} instead
     * of completing with a message id for a stanza that
     * {@link #send(BinaryNode)} dropped (the previous behaviour
     * logged "Sent" and returned a successful-looking id).
     *
     * @param newsletterJid newsletter (channel) JID
     * @param msg structured message payload
     * @return future of the message id, failing promptly when disconnected
     */
    public CompletableFuture<String> sendNewsletterMessage(
            String newsletterJid, Wa.Message msg) {
        if (isDisconnected()) {
            return CompletableFuture.failedFuture(disconnectedFailure());
        }
        String msgId = generateMessageId();

        String msgType = "text";
        if (msg.hasImageMessage()) msgType = "image";
        else if (msg.hasVideoMessage()) msgType = "video";
        else if (msg.hasAudioMessage()) msgType = "audio";
        else if (msg.hasDocumentMessage()) msgType = "document";

        BinaryNode plaintextNode = new BinaryNode("plaintext", Map.of(), msg.toByteArray());

        BinaryNode stanza = new BinaryNode("message", Map.of(
            "to", newsletterJid,
            "id", msgId,
            "type", msgType
        ), List.of(plaintextNode));

        send(stanza);
        LOG.info("Sent newsletter message id={} to={} type={}", msgId, newsletterJid, msgType);
        return CompletableFuture.completedFuture(msgId);
    }

    public CompletableFuture<String> sendReaction(
            String chatJid,
            String targetMsgId,
            String targetParticipant,
            boolean fromMe,
            String emoji) {
        long ts = System.currentTimeMillis();
        Wa.Message msg = MessageEncoder.reaction(
            chatJid, targetMsgId, targetParticipant, fromMe, emoji, ts);
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendReaction(
            MessageReceiver.Decoded target, String emoji) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        if (!chatJid.endsWith("@g.us")) {
            Jid j = Jid.parse(chatJid);
            if (j != null) chatJid = j.user() + "@" + j.server();
        }
        Jid myJid = Jid.parse(creds.meJid);
        Jid senderJid = Jid.parse(target.senderJid());
        boolean fromMe = myJid != null && senderJid != null && myJid.user().equals(senderJid.user());
        String targetParticipant = target.groupJid() != null ? target.senderJid() : null;
        return sendReaction(chatJid, target.msgId(), targetParticipant, fromMe, emoji);
    }

    public CompletableFuture<String> sendReply(
            String chatJid,
            String text,
            String quotedMsgId,
            String quotedSender,
            String quotedText) {
        Wa.Message msg = MessageEncoder.reply(
            text, quotedMsgId, quotedSender, quotedText);
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendReply(
            MessageReceiver.Decoded target, String text) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        if (!chatJid.endsWith("@g.us")) {
            Jid j = Jid.parse(chatJid);
            if (j != null) chatJid = j.user() + "@" + j.server();
        }
        String quotedSender = target.groupJid() != null ? target.senderJid() : null;
        String quotedText = target.text() != null ? target.text() : "";
        return sendReply(chatJid, text, target.msgId(), quotedSender, quotedText);
    }

    public CompletableFuture<String> sendListMessage(
            String chatJid,
            String title,
            String body,
            String footer,
            String buttonText,
            List<MessageEncoder.ListSection> sections) {
        Wa.Message msg = MessageEncoder.listMessage(
            title, body, footer, buttonText, sections);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendCtaButtons(
            String chatJid,
            String body,
            String footer,
            List<MessageEncoder.CtaButton> buttons) {
        Wa.Message msg = MessageEncoder.interactiveCtaButtons(body, footer, buttons);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public record CarouselCardInput(
        String title,
        String caption,
        byte[] mediaBytes,
        String mimetype,
        String footer,
        List<MessageEncoder.CtaButton> buttons
    ) {}

    public CompletableFuture<String> sendCarousel(
            String chatJid,
            String body,
            String footer,
            List<CarouselCardInput> inputs) {
        return sendCarousel(chatJid, body, footer, inputs, null);
    }

    public CompletableFuture<String> sendCarousel(
            String chatJid,
            String body,
            String footer,
            List<CarouselCardInput> inputs,
            Wa.ContextInfo quoted) {
        List<CompletableFuture<MessageEncoder.CarouselCard>> futures =
            new ArrayList<>();
        for (CarouselCardInput in : inputs) {
            boolean isVideo = in.mimetype() != null && in.mimetype().startsWith("video/");
            MediaCrypto.MediaType type = isVideo
                ? MediaCrypto.MediaType.VIDEO
                : MediaCrypto.MediaType.IMAGE;
            futures.add(encryptAndUpload(in.mediaBytes(), type).thenApply(u -> {
                if (isVideo) {
                    Wa.Message inner = MessageEncoder.videoMessage(
                        u.upload().url(), u.upload().directPath(),
                        u.mediaKey(),
                        u.enc().fileSha256(), u.enc().fileEncSha256(),
                        in.mediaBytes().length, in.mimetype(),
                        in.caption(), 0, 0, 0);
                    return new MessageEncoder.CarouselCard(
                        in.title(), in.caption(), null, inner.getVideoMessage(),
                        in.footer(), in.buttons());
                }
                Wa.Message inner = MessageEncoder.imageMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    in.mediaBytes().length, in.mimetype(), in.caption());
                return new MessageEncoder.CarouselCard(
                    in.title(), in.caption(), inner.getImageMessage(), null,
                    in.footer(), in.buttons());
            }));
        }
        CompletableFuture<Void> all = CompletableFuture
            .allOf(futures.toArray(new CompletableFuture<?>[0]));
        return all.thenCompose(v -> {
            List<MessageEncoder.CarouselCard> cards = new ArrayList<>();
            for (var f : futures) cards.add(f.join());
            Wa.Message msg = MessageEncoder.interactiveCarousel(body, footer, cards);
            if (quoted != null) {
                msg = msg.toBuilder()
                    .setInteractiveMessage(msg.getInteractiveMessage().toBuilder().setContextInfo(quoted))
                    .build();
            }
            return sendBuiltMessage(chatJid, msg);
        });
    }

    public CompletableFuture<String> sendButtonsMessage(
            String chatJid,
            String body,
            String footer,
            List<MessageEncoder.QuickReplyButton> buttons) {
        Wa.Message msg = MessageEncoder.buttonsMessage(body, footer, buttons);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendEdit(
            String chatJid,
            String targetMsgId,
            String newText) {
        Wa.Message inner = MessageEncoder.text(newText);
        Wa.Message msg = MessageEncoder.edit(
            chatJid, targetMsgId, inner, System.currentTimeMillis());
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendEdit(
            MessageReceiver.Decoded target, String newText) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        if (!chatJid.endsWith("@g.us")) {
            Jid j = Jid.parse(chatJid);
            if (j != null) chatJid = j.user() + "@" + j.server();
        }
        return sendEdit(chatJid, target.msgId(), newText);
    }

    public CompletableFuture<byte[]> downloadMedia(
            String url,
            String directPath,
            byte[] mediaKey,
            byte[] fileEncSha256,
            MediaCrypto.MediaType type) {
        if (url != null && !url.isEmpty()) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return MediaDownloader.downloadByUrl(url, mediaKey, fileEncSha256, type);
                } catch (IOException | InterruptedException e) {
                    throw new CompletionException(e);
                }
            });
        }
        if (directPath == null || directPath.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("both url and directPath are empty"));
        }
        return refreshMediaConn().thenCompose(mc ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    return MediaDownloader.downloadByDirectPath(
                        mc, directPath, mediaKey, fileEncSha256, type);
                } catch (IOException | InterruptedException e) {
                    throw new CompletionException(e);
                }
            }));
    }

    private record MediaUpload(
        byte[] mediaKey,
        MediaCrypto.EncryptedMedia enc,
        MediaUploader.Result upload
    ) {}

    private CompletableFuture<MediaUpload> encryptAndUpload(
            byte[] bytes,
            MediaCrypto.MediaType type) {
        byte[] mediaKey = Bytes.random(32);
        MediaCrypto.EncryptedMedia enc =
            MediaCrypto.encrypt(bytes, mediaKey, type);
        return refreshMediaConn().thenCompose(mc -> {
            try {
                MediaUploader.Result up =
                    MediaUploader.upload(mc, enc, type);
                return CompletableFuture.completedFuture(
                    new MediaUpload(mediaKey, enc, up));
            } catch (IOException | InterruptedException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    private CompletableFuture<String> sendBuiltMessage(
            String chatJid, Wa.Message msg) {
        if (chatJid.endsWith("@newsletter")) {
            return sendNewsletterMessage(chatJid, msg);
        }
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendImage(
            String chatJid,
            byte[] imageBytes,
            String mimetype,
            String caption) {
        return encryptAndUpload(imageBytes, MediaCrypto.MediaType.IMAGE)
            .thenCompose(u -> sendBuiltMessage(chatJid,
                MessageEncoder.imageMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    imageBytes.length, mimetype, caption)));
    }

    public CompletableFuture<String> sendImageViewOnce(
            String chatJid, byte[] imageBytes, String mimetype, String caption) {
        return encryptAndUpload(imageBytes, MediaCrypto.MediaType.IMAGE)
            .thenCompose(u -> {
                Wa.Message inner = MessageEncoder.imageMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    imageBytes.length, mimetype, caption);
                return sendBuiltMessage(chatJid, MessageEncoder.viewOnceWrap(inner));
            });
    }

    public CompletableFuture<String> sendVideoViewOnce(
            String chatJid, byte[] videoBytes, String mimetype, String caption,
            int seconds, int width, int height) {
        return encryptAndUpload(videoBytes, MediaCrypto.MediaType.VIDEO)
            .thenCompose(u -> {
                Wa.Message inner = MessageEncoder.videoMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    videoBytes.length, mimetype, caption, seconds, width, height);
                return sendBuiltMessage(chatJid, MessageEncoder.viewOnceWrap(inner));
            });
    }

    public CompletableFuture<String> sendAudioViewOnce(
            String chatJid, byte[] audioBytes, String mimetype, int seconds, boolean ptt) {
        return encryptAndUpload(audioBytes, MediaCrypto.MediaType.AUDIO)
            .thenCompose(u -> {
                Wa.Message inner = MessageEncoder.audioMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    audioBytes.length, mimetype, seconds, ptt);
                return sendBuiltMessage(chatJid, MessageEncoder.viewOnceExtensionWrap(inner));
            });
    }

    public CompletableFuture<String> sendVideo(
            String chatJid,
            byte[] videoBytes,
            String mimetype,
            String caption,
            int seconds,
            int width,
            int height) {
        return encryptAndUpload(videoBytes, MediaCrypto.MediaType.VIDEO)
            .thenCompose(u -> sendBuiltMessage(chatJid,
                MessageEncoder.videoMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    videoBytes.length, mimetype,
                    caption, seconds, width, height)));
    }

    public CompletableFuture<String> sendAudio(
            String chatJid,
            byte[] audioBytes,
            String mimetype,
            int seconds,
            boolean ptt) {
        return encryptAndUpload(audioBytes, MediaCrypto.MediaType.AUDIO)
            .thenCompose(u -> sendBuiltMessage(chatJid,
                MessageEncoder.audioMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    audioBytes.length, mimetype, seconds, ptt)));
    }

    public CompletableFuture<String> sendDocument(
            String chatJid,
            byte[] documentBytes,
            String mimetype,
            String fileName,
            String title) {
        return encryptAndUpload(documentBytes, MediaCrypto.MediaType.DOCUMENT)
            .thenCompose(u -> sendBuiltMessage(chatJid,
                MessageEncoder.documentMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    documentBytes.length, mimetype, fileName, title)));
    }

    public CompletableFuture<String> sendSticker(
            String chatJid,
            byte[] webpBytes,
            boolean isAnimated) {
        return encryptAndUpload(webpBytes, MediaCrypto.MediaType.IMAGE)
            .thenCompose(u -> sendBuiltMessage(chatJid,
                MessageEncoder.stickerMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    webpBytes.length, isAnimated)));
    }

    public CompletableFuture<String> sendSticker(String chatJid, byte[] webpBytes) {
        return sendSticker(chatJid, webpBytes, false);
    }

    public CompletableFuture<Void> deleteMedia(
            String directPath, byte[] encFileHash, MediaCrypto.MediaType type) {
        return refreshMediaConn().thenAcceptAsync(conn -> {
            try {
                MediaUploader.delete(conn, directPath, encFileHash, type);
            } catch (Exception e) {
                LOG.warn("Failed deleting media: {}", e.toString());
            }
        });
    }

    public CompletableFuture<String> sendRevoke(
            String chatJid,
            String targetMsgId,
            String targetParticipant,
            boolean fromMe) {
        Wa.Message msg = MessageEncoder.revoke(
            chatJid, targetMsgId, targetParticipant, fromMe);
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendRevoke(
            MessageReceiver.Decoded target) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        if (!chatJid.endsWith("@g.us")) {
            Jid j = Jid.parse(chatJid);
            if (j != null) chatJid = j.user() + "@" + j.server();
        }
        Jid myJid = Jid.parse(creds.meJid);
        Jid senderJid = Jid.parse(target.senderJid());
        boolean fromMe = myJid != null && senderJid != null && myJid.user().equals(senderJid.user());
        String targetParticipant = target.groupJid() != null ? target.senderJid() : null;
        return sendRevoke(chatJid, target.msgId(), targetParticipant, fromMe);
    }

    private volatile MediaConn mediaConnCache;
    private final Object mediaConnLock = new Object();

    public CompletableFuture<MediaConn> refreshMediaConn() {
        synchronized (mediaConnLock) {
            MediaConn cached = mediaConnCache;
            if (cached != null && !cached.isExpired(Instant.now())) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        String iqId = newIqId();
        BinaryNode iq = MediaConn.buildQuery(iqId);
        LOG.debug("Sent mediaConn IQ id={}", iqId);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("mediaConn IQ rejected: {}", resp);
                throw new IllegalStateException("mediaConn IQ rejected");
            }
            MediaConn fresh = MediaConn.parseResponse(resp);
            if (fresh == null) {
                throw new IllegalStateException("malformed mediaConn response: " + resp);
            }
            synchronized (mediaConnLock) {
                mediaConnCache = fresh;
            }
            LOG.debug("Got mediaConn: {} host(s), ttl={}s", fresh.hosts().size(), fresh.ttlSeconds());
            return fresh;
        });
    }

    public CompletableFuture<String> createGroup(
            String name, List<String> participantBareJids) {
        String iqId = newIqId();
        String createKey = newIqId().toUpperCase();
        BinaryNode iq = GroupAction.buildCreate(
            iqId, name, participantBareJids, createKey);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("createGroup rejected: " + resp);
            }
            BinaryNode group = resp.child("group");
            if (group == null) throw new IllegalStateException("createGroup response missing <group>: " + resp);
            String jid = group.attr("id");
            if (jid != null && !jid.contains("@")) jid = jid + "@" + Jid.SERVER_GROUP;
            return jid;
        });
    }

    public CompletableFuture<Void> leaveGroup(String groupJid) {
        String iqId = newIqId();
        BinaryNode iq = GroupAction.buildLeave(iqId, groupJid);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("leaveGroup rejected: " + resp);
            }
        });
    }

    public CompletableFuture<Void> updateGroupParticipants(
            String groupJid,
            GroupAction.ParticipantChange action,
            List<String> participantBareJids) {
        String iqId = newIqId();
        BinaryNode iq = GroupAction.buildParticipantChange(
            iqId, groupJid, action, participantBareJids);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException(action + " rejected: " + resp);
            }
        });
    }

    public CompletableFuture<Void> setGroupSubject(String groupJid, String name) {
        String iqId = newIqId();
        BinaryNode iq = GroupAction.buildSetSubject(iqId, groupJid, name);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("setGroupSubject rejected: " + resp);
            }
        });
    }

    public CompletableFuture<Void> setGroupDescription(
            String groupJid, String body, String previousId) {
        String iqId = newIqId();
        String newId = newIqId().toUpperCase();
        BinaryNode iq = GroupAction.buildSetDescription(
            iqId, groupJid, body, previousId, newId);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("setGroupDescription rejected: " + resp);
            }
        });
    }

    public CompletableFuture<String> getGroupInviteLink(String groupJid, boolean reset) {
        String iqId = newIqId();
        BinaryNode iq = GroupAction.buildInviteLink(iqId, groupJid, reset);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("getGroupInviteLink rejected: " + resp);
            }
            BinaryNode invite = resp.child("invite");
            if (invite == null || invite.attr("code") == null) {
                throw new IllegalStateException("No invite code found in response: " + resp);
            }
            return "https://chat.whatsapp.com/" + invite.attr("code");
        });
    }

    public CompletableFuture<String> getGroupInviteLink(String groupJid) {
        return getGroupInviteLink(groupJid, false);
    }

    public CompletableFuture<String> revokeGroupInviteLink(String groupJid) {
        return getGroupInviteLink(groupJid, true);
    }

    public CompletableFuture<BinaryNode> getGroupInfoFromLink(String code) {
        String cleanCode = cleanInviteCode(code);
        String iqId = newIqId();
        BinaryNode iq = GroupAction.buildLinkInfo(iqId, cleanCode);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("getGroupInfoFromLink rejected: " + resp);
            }
            return resp.child("group");
        });
    }

    public CompletableFuture<String> joinGroupWithLink(String code) {
        String cleanCode = cleanInviteCode(code);
        String iqId = newIqId();
        BinaryNode iq = GroupAction.buildJoinWithLink(iqId, cleanCode);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("joinGroupWithLink rejected: " + resp);
            }
            BinaryNode req = resp.child("membership_approval_request");
            if (req != null && req.attr("jid") != null) {
                return req.attr("jid");
            }
            BinaryNode group = resp.child("group");
            if (group != null && group.attr("jid") != null) {
                return group.attr("jid");
            }
            throw new IllegalStateException("Neither group nor approval request returned in response: " + resp);
        });
    }

    public CompletableFuture<Void> setGroupLocked(String groupJid, boolean locked) {
        String iqId = newIqId();
        BinaryNode iq = GroupAction.buildSetLocked(iqId, groupJid, locked);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("setGroupLocked rejected: " + resp);
            }
        });
    }

    public CompletableFuture<Void> setGroupAnnounce(String groupJid, boolean announce) {
        String iqId = newIqId();
        BinaryNode iq = GroupAction.buildSetAnnounce(iqId, groupJid, announce);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("setGroupAnnounce rejected: " + resp);
            }
        });
    }

    private static String cleanInviteCode(String code) {
        if (code == null) return "";
        String s = code.trim();
        if (s.startsWith("https://chat.whatsapp.com/")) {
            return s.substring("https://chat.whatsapp.com/".length());
        }
        if (s.startsWith("http://chat.whatsapp.com/")) {
            return s.substring("http://chat.whatsapp.com/".length());
        }
        if (s.startsWith("chat.whatsapp.com/")) {
            return s.substring("chat.whatsapp.com/".length());
        }
        return s;
    }

    public CompletableFuture<List<GroupListQuery.GroupInfo>>
            queryJoinedGroups() {
        return queryJoinedGroups(false);
    }

    public CompletableFuture<List<GroupListQuery.GroupInfo>>
            queryJoinedGroups(boolean forceRefresh) {
        if (!forceRefresh && !groupCache.isEmpty()) {
            return CompletableFuture.completedFuture(
                new ArrayList<>(groupCache.values()));
        }
        String iqId = newIqId();
        BinaryNode iq = GroupListQuery.buildQuery(iqId);
        LOG.debug("Sent joined-groups query id={}", iqId);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("Group list query rejected: {}", resp);
                return List.of();
            }
            var list = GroupListQuery.parseResponse(resp);
            for (var g : list) {
                groupCache.put(g.jid(), g);
                if (g.isEphemeral() && g.ephemeralExpiration() > 0) {
                    chatEphemeralTimers.put(g.jid(), g.ephemeralExpiration());
                } else {
                    chatEphemeralTimers.remove(g.jid());
                }
            }
            return list;
        });
    }

    public CompletableFuture<GroupListQuery.GroupInfo>
            queryGroupInfo(String groupJid) {
        String iqId = newIqId();
        BinaryNode iq = GroupListQuery.buildGroupInfoQuery(iqId, groupJid);
        LOG.debug("Sent group info query id={} to={}", iqId, groupJid);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("Group info query rejected for {}: {}", groupJid, resp);
                return null;
            }
            BinaryNode groupNode = resp.child("group");
            if (groupNode == null) return null;
            GroupListQuery.GroupInfo info =
                GroupListQuery.parseSingleGroup(groupNode);
            if (info != null) {
                groupCache.put(info.jid(), info);
                if (info.isEphemeral() && info.ephemeralExpiration() > 0) {
                    chatEphemeralTimers.put(info.jid(), info.ephemeralExpiration());
                }
            }
            return info;
        });
    }

    public CompletableFuture<List<SignalProtocolAddress>>
            bootstrapSessions(String targetUser) {
        return queryDevices(List.of(targetUser))
            .thenCompose(devicesMap -> {
                var devices = devicesMap.getOrDefault(targetUser, List.of());
                if (devices.isEmpty()) {
                    List<SignalProtocolAddress> empty = List.of();
                    return CompletableFuture.completedFuture(empty);
                }
                Jid base = Jid.parse(targetUser);
                String user = base.user();
                String server = base.server();
                List<String> deviceJids = new ArrayList<>(devices.size());
                for (var d : devices) deviceJids.add(d.asJid(user, server).asString());
                return fetchBundlesAndInstallSessions(deviceJids);
            });
    }

    public CompletableFuture<Map<String, List<DeviceInfo>>>
            queryDevices(Collection<String> targetJids) {
        Map<String, List<DeviceInfo>> result = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String tj : targetJids) {
            var cached = deviceCache.get(tj);
            if (cached != null) {
                result.put(tj, cached);
            } else {
                missing.add(tj);
            }
        }
        if (missing.isEmpty()) {
            return CompletableFuture.completedFuture(result);
        }
        String iqId = newIqId();
        String sid = "jawa-" + Bytes.toHex(Bytes.random(4));
        BinaryNode q = UsyncQuery.buildDeviceListQuery(iqId, sid, missing);
        LOG.debug("Sent USync device-list query id={} sid={} for {}", iqId, sid, missing);
        return sendIqAsync(q).thenApply(resp -> {
            LOG.debug("USync response: {}", resp);
            var parsed = UsyncQuery.parseDeviceListResult(resp);
            for (var entry : parsed.entrySet()) {
                deviceCache.put(entry.getKey(), entry.getValue());
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        });
    }

    private void sendActivePassive() {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            Map.of(
                "to",    Jid.SERVER_WHATSAPP,
                "xmlns", "passive",
                "type",  "set",
                "id",    iqId
            ),
            List.of(new BinaryNode("active", Map.of(), null)));
        sendIq(iq, resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("active/passive IQ rejected: {}", resp);
            } else {
                LOG.debug("Server acknowledged active mode (id={})", iqId);
            }
        });
    }

    private void sendPresenceAvailable() {
        String name = (creds != null && creds.pushName != null && !creds.pushName.isBlank())
            ? creds.pushName
            : "JaWa";
        try {
            send(new BinaryNode("presence", Map.of(
                "type", "available",
                "name", name
            ), null));
            LOG.debug("Sent presence available (name=\"{}\")", name);
        } catch (RuntimeException e) {
            LOG.warn("Failed to send presence available", e);
        }
    }

    private void uploadPreKeys() {
        var preKeys = PreKeyManager.generate(creds, signalStore, PRE_KEY_UPLOAD_COUNT);
        if (preKeys.isEmpty()) {
            LOG.debug("No new pre-keys to upload");
            return;
        }

        for (var entry : preKeys.entrySet()) {
            try {
                byte[] prefixedPub = Curve25519.prependType(entry.getValue().publicKey());
                ECKeyPair kp =
                    new ECKeyPair(
                        Curve.decodePoint(prefixedPub, 0),
                        Curve.decodePrivatePoint(entry.getValue().privateKey()));
                protocolStore.storePreKey(entry.getKey(),
                    new PreKeyRecord(entry.getKey(), kp));
            } catch (InvalidKeyException e) {
                LOG.warn("Failed to seed pre-key {} into protocolStore", entry.getKey(), e);
            }
        }
        String iqId = newIqId();
        BinaryNode iq = PreKeyManager.buildUploadStanza(iqId, creds, preKeys);
        sendIq(iq, response -> {
            if ("error".equals(response.attr("type"))) {
                LOG.warn("Pre-key upload rejected: {}", response);
                return;
            }
            PreKeyManager.markUploaded(creds, preKeys);
            try { store.save(creds); } catch (Exception e) { LOG.warn("Failed to save creds after pre-key upload", e); }
            LOG.info("Uploaded {} pre-keys (ids {}..{})",
                preKeys.size(),
                preKeys.keySet().iterator().next(),
                preKeys.keySet().stream().reduce((a, b) -> b).get());
        });
        LOG.debug("Sent pre-key upload IQ id={} ({} keys)", iqId, preKeys.size());
    }

    private boolean handleStanza(BinaryNode node, PairingHandler pair) throws Exception {
        if ("stream:error".equals(node.tag())) {
            String code = node.attr("code");
            if ("515".equals(code)) {

                LOG.info("Server requested restart (code=515): expected after pairing; reconnect to enter steady state");
            } else {
                handleStreamError(node);
            }
            return true;
        }
        if ("xmlstreamend".equals(node.tag())) {
            LOG.debug("Server sent stream end");
            return true;
        }
        if ("ib".equals(node.tag())) {
            BinaryNode offlinePreview = node.child("offline_preview");
            if (offlinePreview != null) {
                LOG.info("Server offline sync preview: count={} message={}",
                    offlinePreview.attr("count"), offlinePreview.attr("message"));
            }
            BinaryNode offline = node.child("offline");
            if (offline != null) {
                LOG.info("Server offline sync completed: count={}", offline.attr("count"));
            }
            return true;
        }
        if ("success".equals(node.tag())) {
            connectedAtEpochSec = System.currentTimeMillis() / 1000L;
            LOG.info("Login successful: meJid={} lid={} platform={}",
                node.attr("jid", creds.meJid),
                node.attr("lid", creds.meLid),
                node.attr("platform", creds.platform));
            if (creds.meLid != null && creds.meJid != null) {
                Jid myLid = Jid.parse(creds.meLid);
                Jid myJid = Jid.parse(creds.meJid);
                if (myLid != null && myJid != null) {
                    String bareLid = myLid.user() + "@lid";
                    String barePn = myJid.user() + "@" + Jid.SERVER_WHATSAPP;
                    LID_TO_PN_MAP.put(bareLid, barePn);
                    LID_TO_PN_MAP.put(barePn, bareLid);
                }
            }
            reconnectAttempts.set(0);
            sendActivePassive();
            sendPresenceAvailable();
            listener.onConnected();
            dispatchEvent(BaileysEventType.CONNECTION_UPDATE, ConnectionUpdate.open());
            // Increment 4: Baileys resets the history-sync status flags
            // on connection open (chats.js connection.update handler).
            historySyncStatusTracker.reset();
            uploadPreKeys();
            return true;
        }
        if ("failure".equals(node.tag())) {
            handleFailure(node);
            return true;
        }

        if ("notification".equals(node.tag())
                && node.child("link_code_companion_reg") != null
                && pairCodeHandler != null) {
            try {
                BinaryNode finish = pairCodeHandler.buildCompanionFinish(newIqId(), node);
                send(finish);
                LOG.debug("Sent companion_finish IQ");
            } catch (Throwable t) {
                LOG.warn("Failed to handle link_code notification", t);
            }
        }

        if ("ack".equals(node.tag())) {
            String ackId = node.attr("id");
            String ackClass = node.attr("class");
            String ackType = node.attr("type");
            String ackError = node.attr("error");
            if (ackError != null || "error".equals(ackType)) {
                LOG.error("SERVER ACK ERROR: id={} class={} type={} error={}", ackId, ackClass, ackType, ackError);
            } else {
                LOG.info("SERVER ACK: id={} class={} type={}", ackId, ackClass, ackType);
            }
            String from = node.attr("from");
            String phash = node.attr("phash");
            if (from != null && phash != null && !phash.isEmpty()) {
                String existing = groupPhashMap.get(from);
                if (existing != null && !existing.equals(phash)) {
                    LOG.warn("Participant list hash mismatch for {} (local={} server={}) - invalidating group cache",
                        from, existing, phash);
                    groupCache.remove(from);
                    groupPhashMap.remove(from);
                }
            }
            try {
                listener.onAck(node);
            } catch (Throwable t) {
                LOG.warn("Failed to dispatch onAck", t);
            }
            return true;
        }

        if ("notification".equals(node.tag()) || "receipt".equals(node.tag())) {
            LOG.info("SERVER {}: id={} type={} from={}", node.tag().toUpperCase(), node.attr("id"), node.attr("type"), node.attr("from"));
            try { sendAck(node); }
            catch (Throwable t) { LOG.warn("Failed to ack {}", node.tag(), t); }
            if ("notification".equals(node.tag())) {
                String type = node.attr("type");
                String from = node.attr("from");
                if ("devices".equals(type) && from != null) {
                    deviceCache.remove(from);
                    Jid j = Jid.parse(from);
                    if (j != null) {
                        deviceCache.remove(j.user() + "@" + j.server());
                    }
                    LOG.debug("Invalidated deviceCache for {}", from);
                } else if ("w:g2".equals(node.attr("xmlns")) && from != null && from.endsWith("@g.us")) {
                    groupCache.remove(from);
                    groupPhashMap.remove(from);
                    BinaryNode ephChild = node.child("ephemeral");
                    if (ephChild != null) {
                        try {
                            int exp = Integer.parseInt(ephChild.attr("expiration"));
                            if (exp > 0) {
                                chatEphemeralTimers.put(from, exp);
                            } else {
                                chatEphemeralTimers.remove(from);
                            }
                        } catch (Exception ignored) {}
                    } else if (node.child("not_ephemeral") != null) {
                        chatEphemeralTimers.remove(from);
                    }
                    LOG.debug("Invalidated groupCache for {}", from);
                }
                // Increment 2: Baileys event dispatch from notification
                // types. Each parser mirrors a Baileys processNotification
                // case (see the parser classes for sources and differences).
                // Group notifications (w:gp2) are distinct from the w:g2
                // xmlns cache-invalidation branch above.
                if ("w:gp2".equals(type) && from != null) {
                    List<BinaryNode> gpChildren = node.childrenList();
                    if (!gpChildren.isEmpty()) {
                        BinaryNode first = gpChildren.get(0);
                        if ("ephemeral".equals(first.tag())) {
                            try {
                                int exp = Integer.parseInt(first.attr("expiration"));
                                if (exp > 0) {
                                    chatEphemeralTimers.put(from, exp);
                                } else {
                                    chatEphemeralTimers.remove(from);
                                }
                            } catch (Exception ignored) {}
                        } else if ("not_ephemeral".equals(first.tag())) {
                            chatEphemeralTimers.remove(from);
                        } else {
                            groupCache.remove(from);
                            groupPhashMap.remove(from);
                        }
                    }
                    for (EventDispatch dispatch : GroupNotificationParser.parse(node)) {
                        if (dispatch.type() == BaileysEventType.GROUPS_UPSERT) {
                            GroupsUpsert upsert = (GroupsUpsert) dispatch.payload();
                            for (GroupsUpsert.GroupInfo g : upsert.groups()) {
                                memoryStore.upsertGroup(g);
                            }
                        } else if (dispatch.type() == BaileysEventType.GROUP_PARTICIPANTS_UPDATE) {
                            GroupParticipantsUpdate upd = (GroupParticipantsUpdate) dispatch.payload();
                            memoryStore.updateGroupParticipants(upd.id(), upd.participants(), upd.action());
                        } else if (dispatch.type() == BaileysEventType.CHATS_UPSERT) {
                            ChatsUpsert upsert = (ChatsUpsert) dispatch.payload();
                            for (String chatId : upsert.chatIds()) {
                                memoryStore.ensureChat(chatId, 0L);
                            }
                        }
                        dispatchEvent(dispatch.type(), dispatch.payload());
                    }
                } else if ("newsletter".equals(type)) {
                    for (EventDispatch dispatch : NewsletterNotificationParser.parse(node)) {
                        dispatchEvent(dispatch.type(), dispatch.payload());
                    }
                } else if ("mediaretry".equals(type)) {
                    MessagesMediaUpdate mediaUpdate = MediaRetryParser.parse(node);
                    if (mediaUpdate != null) {
                        dispatchEvent(BaileysEventType.MESSAGES_MEDIA_UPDATE, mediaUpdate);
                    }
                } else if ("picture".equals(type)) {
                    ContactsUpdate pictureUpdate = PictureNotificationParser.parse(node);
                    if (pictureUpdate != null) {
                        for (ContactsUpdate.ContactUpdate u : pictureUpdate.updates()) {
                            memoryStore.updateContactImgUrl(u.id(), u.imgUrl());
                        }
                        dispatchEvent(BaileysEventType.CONTACTS_UPDATE, pictureUpdate);
                    }
                } else if ("account_sync".equals(type)) {
                    for (BlocklistUpdate blocklistUpdate : BlocklistParser.parse(node)) {
                        dispatchEvent(BaileysEventType.BLOCKLIST_UPDATE, blocklistUpdate);
                    }
                } else if ("mex".equals(type)) {
                    // Increment 4: Baileys handleMexNotification
                    // (lib/Socket/messages-recv.js). Lid mappings from
                    // mex are recorded in the store and the LID->PN
                    // routing map, mirroring Baileys' signal-repository
                    // storage of linked-profiles mappings.
                    for (EventDispatch dispatch : MexNotificationParser.parse(node)) {
                        if (dispatch.type() == BaileysEventType.LID_MAPPING_UPDATE) {
                            LidMappingUpdate mapping = (LidMappingUpdate) dispatch.payload();
                            memoryStore.upsertLidMapping(mapping.lid(), mapping.pn());
                            LID_TO_PN_MAP.put(mapping.lid(), mapping.pn());
                        }
                        dispatchEvent(dispatch.type(), dispatch.payload());
                    }
                }
            } else if ("receipt".equals(node.tag())) {
                try {
                    Receipt parsed = Receipt.parse(node);
                    if (parsed != null) {
                        listener.onReceipt(parsed);
                        try {
                            eventBus.dispatch(BaileysEventType.MESSAGE_RECEIPT_UPDATE,
                                new MessageReceiptUpdate(parsed));
                        } catch (Throwable t) {
                            LOG.warn("Failed to dispatch message-receipt.update", t);
                        }
                    }
                } catch (Throwable t) {
                    LOG.warn("Failed to dispatch onReceipt", t);
                }
            }
            return false;
        }

        if ("message".equals(node.tag())) {
            handleInboundMessage(node);
            return true;
        }

        if ("presence".equals(node.tag())) {
            // New in Increment 1. No ack is sent for presence stanzas, and
            // returning false keeps the legacy onStanza fallthrough firing
            // for existing consumers.
            try {
                PresenceUpdate presence = PresenceEventParser.parse(node);
                if (presence != null) {
                    memoryStore.upsertPresence(presence);
                    dispatchEvent(BaileysEventType.PRESENCE_UPDATE, presence);
                }
            } catch (Throwable t) {
                LOG.warn("Failed to dispatch presence.update", t);
            }
            return false;
        }

        if ("call".equals(node.tag())) {
            // Increment 6: a <call> stanza whose id matches a pending
            // stanza request (see sendStanzaAsync / createCallLink) is
            // that request's response, not a call event: complete it
            // and consume the stanza, exactly as the iq branch does
            // for iq result/error nodes. Baileys' query() correlates
            // by id regardless of tag, so this is the missing half of
            // that correlation in wa-java.
            String callId = node.attr("id");
            var pending = callId == null ? null : pendingIqResults.remove(callId);
            if (pending != null) { pending.accept(node); return true; }
            // New in Increment 1. No ack is sent for call stanzas (a
            // documented difference from Baileys, which acks after handling),
            // and returning false keeps the legacy onStanza fallthrough firing.
            try {
                CallEvent call = callEventParser.parse(node);
                if (call != null) {
                    dispatchEvent(BaileysEventType.CALL, call);
                }
            } catch (Throwable t) {
                LOG.warn("Failed to dispatch call", t);
            }
            return false;
        }

        if (!"iq".equals(node.tag())) return false;

        String type = node.attr("type");
        if ("result".equals(type) || "error".equals(type)) {
            String id = node.attr("id");
            var cb = id == null ? null : pendingIqResults.remove(id);
            if (cb != null) { cb.accept(node); return true; }
        }

        BinaryNode pairDevice  = node.child("pair-device");
        BinaryNode pairSuccess = node.child("pair-success");

        if (pairDevice != null) {
            List<String> qrs = pair.qrRefsFrom(pairDevice);
            send(pair.ackPairDevice(node.attr("id")));
            LOG.info("Got {} QR refs", qrs.size());
            listener.onQr(qrs);
            for (String qr : qrs) {
                dispatchEvent(BaileysEventType.CONNECTION_UPDATE, ConnectionUpdate.qr(qr));
            }
            return true;
        }
        if (pairSuccess != null) {
            BinaryNode reply = pair.handlePairSuccess(node);
            send(reply);
            store.save(creds);
            listener.onPaired(creds.meJid, creds.pushName, creds.platform);
            dispatchEvent(BaileysEventType.CREDS_UPDATE,
                CredsUpdate.paired(creds.meJid, creds.pushName));
            dispatchEvent(BaileysEventType.CONNECTION_UPDATE,
                ConnectionUpdate.isNewLogin(true));

            reconnectAfterPair.set(true);
            return true;
        }

        if ("get".equals(node.attr("type"))
                && "urn:xmpp:ping".equals(node.attr("xmlns"))) {
            String pid = node.attr("id");
            if (pid != null) {
                send(new BinaryNode("iq",
                    Map.of("to", Jid.SERVER_WHATSAPP,
                                     "type", "result",
                                     "id", pid),
                    null));
            } else {
                LOG.warn("Ping IQ has no id; cannot ack: {}", node);
            }
            return true;
        }
        return false;
    }

    /**
     * Whether the client has no live transport/frame — the exact
     * condition {@link #send(BinaryNode)} uses to drop a node.
     * Scoped fail-fast guards reuse this condition so they agree
     * with {@code send()} about what "disconnected" means.
     */
    private boolean isDisconnected() {
        return transport == null || frame == null;
    }

    /** Failure used by scoped fail-fast guards for a disconnected client. */
    private static IllegalStateException disconnectedFailure() {
        return new IllegalStateException("Not connected: transport/frame is not connected");
    }

    public void send(BinaryNode node) {
        if (transport == null || frame == null) {
            LOG.warn("Cannot send node while transport/frame is not connected");
            return;
        }
        LOG.debug("SEND STANZA: {}", node);
        byte[] plain = BinaryEncoder.encode(node);
        byte[] enc = transport.encrypt(plain);
        frame.send(enc);
    }

    private void handleInboundMessage(BinaryNode message) {
        try {
            MessageReceiver.Decoded decoded =
                MessageReceiver.decode(message, protocolStore, senderKeyStore);
            // Increment 7 (stretch): the receiver-side placeholder
            // resolve. Baileys' requestPlaceholderResend re-checks
            // its cache after the 2-second delay and returns
            // 'RESOLVED' on a miss; the miss is produced by the
            // cache delete in the PDO-response handler, and wa-java
            // mirrors that below — but a real message can also
            // arrive directly (decryptable on first receipt), in
            // which case nothing in Baileys' recv path deletes the
            // claim either, so the delay re-check is the only
            // guard there. wa-java goes one step further, safely:
            // a successfully decoded message whose id is pending
            // resolves the claim immediately, so a pending
            // requestPlaceholderResend observes RESOLVED instead of
            // sending a now-pointless PDO request. This changes no
            // dispatch: the message continues down the normal path
            // below. Remaining gap, stated not hidden: wa-java has
            // no ciphertext-stub placeholder upsert (Baileys'
            // processPlaceholderMsg) — a message that fails to
            // decrypt is logged with a retry receipt in the catch
            // below, never upserted as a placeholder, so
            // Increment 10 narrows this: the catch below now upserts a
            // CIPHERTEXT stub and claims the id for a
            // NO_MESSAGE_FOUND failure only (see
            // handleDecryptFailurePlaceholder); every other failure
            // kind still takes the old path (ack + retry receipt,
            // no upsert, no claim). Increment 10b completes it: the
            // catch's helper now also auto-requests the placeholder
            // resend through requestPlaceholderResend (claim + delay
            // + PDO send), so it is no longer manual-only for an
            // eligible NO_MESSAGE_FOUND failure.
            if (placeholderResendManager.isPending(decoded.msgId())) {
                placeholderResendManager.resolve(decoded.msgId());
            }
            if (decoded.message() != null) {
                if (decoded.message().hasProtocolMessage()) {
                    var proto = decoded.message().getProtocolMessage();
                    if (proto.getType() == Wa.Message.ProtocolMessage.Type.EPHEMERAL_SETTING) {
                        String chat = decoded.groupJid() != null ? decoded.groupJid() : decoded.senderJid();
                        int exp = proto.getEphemeralExpiration();
                        if (chat != null) {
                            setChatEphemeralTimer(chat, exp);
                        }
                    }
                } else {
                    int exp = extractExpiration(decoded.message());
                    if (exp > 0) {
                        String chat = decoded.groupJid() != null ? decoded.groupJid() : decoded.senderJid();
                        if (chat != null) {
                            setChatEphemeralTimer(chat, exp);
                        }
                    }
                }
            }
            captureAppStateKeysFrom(decoded);
            sendAck(message);
            sendDeliveryReceipt(message);

            if (autoIgnoreOfflineMessages && decoded.timestamp() > 0 && connectedAtEpochSec > 0
                    && decoded.timestamp() < (connectedAtEpochSec - offlineMessageGracePeriodSec)) {
                LOG.info("Ignored offline message id={} (sent at {}, connected at {})",
                    decoded.msgId(), decoded.timestamp(), connectedAtEpochSec);
                return;
            }

            try { listener.onMessage(decoded); }
            catch (Throwable t) { LOG.warn("listener.onMessage threw", t); }
            try {
                eventBus.dispatch(BaileysEventType.MESSAGES_UPSERT,
                    new MessageUpsert(List.of(decoded), "notify"));
            } catch (Throwable t) {
                LOG.warn("Failed to dispatch messages.upsert", t);
            }
            // Increment 2: LID→PN mappings, store feeding, and derived
            // events for message content Baileys classifies separately
            // from plain messages (reactions, edits, revokes, member tags).
            dispatchLidMappingsFrom(message);
            String storeChatId = decoded.groupJid() != null ? decoded.groupJid() : decoded.senderJid();
            if (storeChatId != null) {
                boolean chatWasNew = memoryStore.getChat(storeChatId) == null;
                memoryStore.upsertMessage(decoded);
                dispatchEvent(chatWasNew ? BaileysEventType.CHATS_UPSERT : BaileysEventType.CHATS_UPDATE,
                    chatWasNew ? new ChatsUpsert(List.of(storeChatId))
                        : new ChatsUpdate(List.of(storeChatId)));
            }
            if (decoded.message() != null) {
                if (decoded.message().hasReactionMessage()) {
                    var reaction = decoded.message().getReactionMessage();
                    var key = reaction.getKey();
                    String reactionChat = key.getRemoteJid() != null && !key.getRemoteJid().isEmpty()
                        ? key.getRemoteJid() : storeChatId;
                    memoryStore.applyReaction(reactionChat, key.getId(), key.getFromMe(),
                        key.getParticipant().isEmpty() ? null : key.getParticipant(),
                        reaction.getText(), decoded.senderJid());
                    dispatchEvent(BaileysEventType.MESSAGES_REACTION, new MessagesReaction(List.of(
                        new MessagesReaction.MessageReaction(key.getId(), reactionChat, key.getFromMe(),
                            key.getParticipant().isEmpty() ? null : key.getParticipant(),
                            reaction.getText(), decoded.senderJid()))));
                }
                if (decoded.message().hasProtocolMessage()) {
                    var protocol = decoded.message().getProtocolMessage();
                    var protoKey = protocol.getKey();
                    var protoType = protocol.getType();
                    if (protoType == Wa.Message.ProtocolMessage.Type.MESSAGE_EDIT
                            || protoType == Wa.Message.ProtocolMessage.Type.REVOKE) {
                        // Key-only update: the Increment 1 MessagesUpdate
                        // payload does not model edited content or a
                        // revoke marker, so only the target key and
                        // timestamp are carried (see its Javadoc).
                        long tsSec = protocol.getTimestampMs() > 0
                            ? protocol.getTimestampMs() / 1000 : decoded.timestamp();
                        dispatchEvent(BaileysEventType.MESSAGES_UPDATE, new MessagesUpdate(List.of(
                            new MessagesUpdate.MessageUpdate(protoKey.getId(), protoKey.getRemoteJid(),
                                protoKey.getFromMe(),
                                protoKey.getParticipant().isEmpty() ? null : protoKey.getParticipant(),
                                null, tsSec, null))));
                    } else if (protoType == Wa.Message.ProtocolMessage.Type.GROUP_MEMBER_LABEL_CHANGE
                            && protocol.hasMemberLabel()) {
                        var label = protocol.getMemberLabel();
                        long tsSec = protocol.getTimestampMs() > 0
                            ? protocol.getTimestampMs() / 1000 : decoded.timestamp();
                        dispatchEvent(BaileysEventType.GROUP_MEMBER_TAG_UPDATE,
                            new GroupMemberTagUpdate(storeChatId, label.getLabel(),
                                protoKey.getParticipant().isEmpty() ? decoded.senderJid() : protoKey.getParticipant(),
                                null, tsSec));
                    } else if (protoType == Wa.Message.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_RESPONSE_MESSAGE
                            && protocol.hasPeerDataOperationRequestResponseMessage()) {
                        // Increment 7 (stretch): inbound PDO-response
                        // handling, mirroring Baileys'
                        // process-message.js PEER_DATA_OPERATION_REQUEST_RESPONSE_MESSAGE
                        // case. Resolved placeholder messages are
                        // dispatched as messages.upsert, type notify.
                        dispatchPlaceholderResendResponses(
                            protocol.getPeerDataOperationRequestResponseMessage());
                    }
                }
                // Increment 4: history-sync notifications ride on inbound
                // protocol messages (Baileys process-message.js /
                // chats.js). This runs after the offline-ignore early
                // return above, so wa-java's existing offline filtering
                // still applies to history traffic.
                Wa.Message.HistorySyncNotification historyNotif =
                    HistorySyncNotificationParser.extractNotification(decoded.message());
                if (historyNotif != null) {
                    handleHistorySyncNotification(decoded, historyNotif);
                }
            }
        } catch (MessageReceiver.DecryptException de) {
            LOG.warn("Decrypt failed for message id={} from={}: {}",
                message.attr("id"), message.attr("from"), de.getMessage());
            // Increment 10: the ack + retry receipt above are
            // unchanged and run exactly once for every failure kind
            // (Baileys sends no retry receipt for NO_MESSAGE_FOUND —
            // ack only — but wa-java's catch has always retry-receipted
            // here, and the task forbids silently changing send-path
            // behaviour, so the receipt is kept, not removed or
            // duplicated). Only the placeholder step below is new,
            // and only for NO_MESSAGE_FOUND.
            sendAck(message);
            sendRetryReceipt(message);
            handleDecryptFailurePlaceholder(message, de);
        }
    }

    /**
     * Increment 10: the placeholder step factored out of
     * {@link #handleInboundMessage}'s decrypt-failure catch, so it is
     * unit-testable without a socket (see Increment10Test, which
     * drives it both directly and through
     * {@link #handleStanzaForTesting}, i.e. through the real catch).
     * <p>
     * Baileys shape (verified in
     * {@code lib/Socket/messages-recv.js:1301-1360} and
     * {@code lib/Utils/decode-wa-message.js:300-306}): a decrypt
     * failure is upserted as a CIPHERTEXT stub with
     * {@code messageStubParameters = [error message]} — for
     * NO_MESSAGE_FOUND that error message is exactly
     * {@code 'Message absent from node'} — as
     * {@code messages.upsert} type {@code notify}, and the id is
     * claimed so a subsequent {@code requestPlaceholderResend}
     * observes the claim (already-requested) instead of sending a
     * duplicate PDO request. Eligibility (kind, {@code <unavailable>}
     * exclusions, peer category, 14-day cap) is decided by
     * {@link PeerDataOperations#isPlaceholderResendEligible}; this
     * method only builds the stub from the stanza attrs (sender/group
     * derivation mirrors {@code MessageReceiver.decode}), claims, and
     * dispatches + stores it, following the Increment 9 V4 synthetic
     * pattern (dispatch + store, no legacy {@code onMessage} call, as
     * with the PDO-response dispatch).
     * <p>
     * Increment 10b: this method now also auto-requests the resend,
     * completing Baileys' branch — after dispatching and storing the
     * stub, it invokes {@link #requestPlaceholderResend} with the
     * stub as the stored data, reusing that method's claim + delay +
     * PDO send (and its 8-second expiry scheduling) verbatim, with
     * no duplicated claim/delay/send logic here. The claim inside
     * {@code requestPlaceholderResend} happens synchronously, before
     * the delay, so by the time this method returns the id is
     * claimed and a subsequent manual {@code requestPlaceholderResend}
     * observes already-claimed (null) rather than sending a
     * duplicate. The request is fire-and-forget, exactly as Baileys
     * floats its {@code requestPlaceholderResend(...).then(...).catch(...)}
     * without awaiting it: a failure (notably the fast
     * "Not authenticated" failure when creds are null, or a
     * disconnected transport's silent drop in {@link #send}) is
     * logged and swallowed, never thrown out of the catch block.
     * <p>
     * Increment 10c: Baileys' success handler is now mirrored too —
     * on PDO-send success only, this method dispatches a
     * {@code messages.update} carrying
     * {@code [NO_MESSAGE_FOUND_ERROR_TEXT, requestId]} for the same
     * key as the stub upsert, exactly as Baileys'
     * {@code requestPlaceholderResend(...).then(requestId => ...)}
     * does ({@code lib/Socket/messages-recv.js:1345-1354}). The
     * guard mirrors Baileys' {@code if (requestId &&
     * requestId !== 'RESOLVED')}: an already-claimed (null) or
     * RESOLVED outcome emits nothing, and a failure emits nothing
     * (it stays logged-and-swallowed as before). The handler lives
     * only on this catch's auto-send future — the manual
     * {@code requestPlaceholderResend} path is unchanged and emits
     * no such update.
     * <p>
     * The upsert is not gated on claim success: Baileys upserts the
     * stub via fall-through even when {@code requestPlaceholderResend}
     * reports already-requested, so a duplicate arrival still upserts
     * while the cache claim stays single — and, by the same token, a
     * duplicate arrival sends no second PDO request.
     * <p>
     * Disconnected transport, documented not changed: the PDO send
     * goes through the existing send path
     * ({@link #sendPeerDataOperationMessage} /
     * {@link #send(BinaryNode)}), whose existing silent-drop
     * behaviour for a disconnected transport applies unchanged — the
     * future may then stay pending (DM path) or the stanza may be
     * dropped (peer path via the test seam / {@code send}), and no
     * retry is added here.
     *
     * @param message the original inbound stanza (attrs supply id /
     *                from / participant / t / category; its direct
     *                {@code <unavailable>} child supplies the type)
     * @param failure the decrypt failure; only NO_MESSAGE_FOUND can
     *                produce a stub
     * @return the upserted stub Decoded, or null when ineligible or
     *         when no stub can be built (blank id, no sender)
     */
    MessageReceiver.Decoded handleDecryptFailurePlaceholder(
            BinaryNode message, MessageReceiver.DecryptException failure) {
        if (message == null || failure == null) return null;
        String msgId = message.attr("id");
        if (msgId == null || msgId.isBlank()) return null;
        String from = message.attr("from");
        String participant = message.attr("participant");
        String senderJid = participant != null ? participant : from;
        if (senderJid == null || senderJid.isBlank()) return null;
        String groupJid = participant != null && from != null && !from.equals(participant)
            ? from : null;
        long timestamp = 0L;
        String tAttr = message.attr("t");
        if (tAttr != null && !tAttr.isEmpty()) {
            try {
                timestamp = Long.parseLong(tAttr);
            } catch (NumberFormatException ignored) {}
        }
        BinaryNode unavailable = message.child("unavailable");
        String unavailableType = unavailable == null ? null : unavailable.attr("type");
        if (!PeerDataOperations.isPlaceholderResendEligible(failure.kind(), unavailableType,
                message.attr("category"), timestamp, System.currentTimeMillis() / 1000L)) {
            return null;
        }
        String errorMessage = failure.getMessage() != null ? failure.getMessage()
            : MessageReceiver.NO_MESSAGE_FOUND_ERROR_TEXT;
        MessageReceiver.Decoded stub = new MessageReceiver.Decoded(
            senderJid, groupJid, msgId, null, null, null, null, timestamp,
            Wa.WebMessageInfo.StubType.CIPHERTEXT, List.of(errorMessage));
        // Upsert + store first, ungated on the claim (see Javadoc):
        // a duplicate arrival still upserts even though its resend
        // request is already claimed.
        dispatchEvent(BaileysEventType.MESSAGES_UPSERT, new MessageUpsert(List.of(stub), "notify"));
        String storeChatId = groupJid != null ? groupJid : senderJid;
        memoryStore.upsertMessage(stub);
        LOG.debug("Upserted decrypt-failure placeholder id={} chat={} (stub=CIPHERTEXT)",
            msgId, storeChatId);
        // Increment 10b auto-send: reuse requestPlaceholderResend
        // (claim + delay + PDO send) with the stub as the stored data
        // Baileys' PDO-response handler applies a reply onto. The
        // claim happens synchronously inside that call, before the
        // delay, so a subsequent manual request observes it. The key
        // mirrors Baileys' cleanKey (remoteJid = chat, participant =
        // group sender when present, fromMe = false for an inbound
        // stanza — the helper's inputs carry no fromMe flag). The
        // future is never awaited and its failure is swallowed here,
        // so a failed PDO attempt (e.g. no creds, disconnected
        // transport) cannot throw out of the decrypt-failure catch.
        Wa.MessageKey.Builder keyBuilder = Wa.MessageKey.newBuilder()
            .setId(msgId)
            .setRemoteJid(storeChatId)
            .setFromMe(false);
        if (participant != null && !participant.isEmpty()) {
            keyBuilder.setParticipant(participant);
        }
        requestPlaceholderResend(keyBuilder.build(), stub)
            // Increment 10c: Baileys' .then(requestId => ...) handler
            // (lib/Socket/messages-recv.js:1345-1354), success only.
            // The future resolves to null (already claimed),
            // "RESOLVED" (real message arrived during the delay), or
            // the sent PDO message id — that id is the requestId, and
            // only it is emitted. Failures never reach this handler;
            // they fall through to the exceptionally below, unchanged.
            .thenAccept(requestId -> {
                if (requestId == null || requestId.isBlank() || "RESOLVED".equals(requestId)) {
                    return;
                }
                dispatchEvent(BaileysEventType.MESSAGES_UPDATE, new MessagesUpdate(List.of(
                    new MessagesUpdate.MessageUpdate(msgId, storeChatId, false,
                        participant != null && !participant.isEmpty() ? participant : null,
                        null, null, null, null,
                        List.of(MessageReceiver.NO_MESSAGE_FOUND_ERROR_TEXT, requestId)))));
            })
            .exceptionally(ex -> {
                LOG.warn("Failed to request placeholder resend for id={}: {}", msgId, ex.getMessage());
                return null;
            });
        return stub;
    }

    /**
     * Increment 4: handles one inbound history-sync notification.
     * Mirrors Baileys' split between {@code chats.js} (the status
     * machine, fed from the notification's sync type and progress) and
     * {@code process-message.js} (the {@code messaging-history.set}
     * emission, fed from the decoded sync). Gates, in Baileys' order:
     * history notifications are a self-only protocol type (dropped
     * when not from our own account, per the whatsmeow guard Baileys
     * mirrors), and the sync type must pass
     * {@link HistorySyncNotificationParser#shouldProcessHistoryMessage}
     * (processable and not FULL under Baileys' default config).
     * <p>
     * Increment 5: both payload paths are implemented. A notification
     * with {@code initialHistBootstrapInlinePayload} decodes inline;
     * a payload-less notification falls back to Baileys' media
     * download of the history file
     * ({@code downloadHistory} in {@code lib/Utils/history.js},
     * {@code HistorySyncNotificationParser.downloadHistory} here).
     * The status machine still runs for every processable
     * notification, as in Baileys; only a notification whose payload
     * neither decodes inline nor downloads produces no
     * {@code messaging-history.set}, and is logged.
     */
    private void handleHistorySyncNotification(MessageReceiver.Decoded decoded,
            Wa.Message.HistorySyncNotification notif) {
        if (!isSelfSender(decoded.senderJid())) {
            LOG.warn("Dropping history-sync notification from non-self sender {}", decoded.senderJid());
            return;
        }
        if (!notif.hasSyncType()
                || !HistorySyncNotificationParser.shouldProcessHistoryMessage(notif.getSyncType())) {
            return;
        }
        historySyncStatusTracker.onHistoryMessage(notif.getSyncType().name(),
            notif.hasProgress() ? notif.getProgress() : null);
        Wa.HistorySync sync = HistorySyncNotificationParser.decodeInline(notif);
        if (sync == null) {
            sync = HistorySyncNotificationParser.downloadHistory(notif);
        }
        if (sync == null) {
            LOG.info("History-sync notification id={} has no decodable inline payload "
                + "and its non-inline history download failed, "
                + "no messaging-history.set is emitted", decoded.msgId());
            return;
        }
        Boolean isLatest = notif.getSyncType() == Wa.Message.HistorySyncType.ON_DEMAND
            ? null : !historySetSeen;
        MessagingHistorySet set =
            HistorySyncNotificationParser.toHistorySet(sync, notif, isLatest);
        if (set == null) {
            return;
        }
        for (LidMappingUpdate mapping : HistorySyncNotificationParser.lidPnMappings(sync)) {
            memoryStore.upsertLidMapping(mapping.lid(), mapping.pn());
            LID_TO_PN_MAP.put(mapping.lid(), mapping.pn());
            dispatchEvent(BaileysEventType.LID_MAPPING_UPDATE, mapping);
        }
        dispatchEvent(BaileysEventType.MESSAGING_HISTORY_SET, set);
        historySetSeen = true;
    }

    /** True when the sender is our own account (PN or LID user match). */
    private boolean isSelfSender(String senderJid) {
        Jid sender = senderJid == null ? null : Jid.parse(senderJid);
        if (sender == null) {
            return false;
        }
        for (String own : new String[] { creds.meJid, creds.meLid }) {
            if (own == null || own.isBlank()) continue;
            Jid ownJid = Jid.parse(own);
            if (ownJid != null && ownJid.user().equals(sender.user())) {
                return true;
            }
        }
        return false;
    }

    private static int extractExpiration(Wa.Message msg) {
        if (msg == null) return 0;
        if (msg.hasExtendedTextMessage() && msg.getExtendedTextMessage().hasContextInfo()) {
            return msg.getExtendedTextMessage().getContextInfo().getExpiration();
        }
        if (msg.hasImageMessage() && msg.getImageMessage().hasContextInfo()) {
            return msg.getImageMessage().getContextInfo().getExpiration();
        }
        if (msg.hasVideoMessage() && msg.getVideoMessage().hasContextInfo()) {
            return msg.getVideoMessage().getContextInfo().getExpiration();
        }
        if (msg.hasDocumentMessage() && msg.getDocumentMessage().hasContextInfo()) {
            return msg.getDocumentMessage().getContextInfo().getExpiration();
        }
        if (msg.hasAudioMessage() && msg.getAudioMessage().hasContextInfo()) {
            return msg.getAudioMessage().getContextInfo().getExpiration();
        }
        if (msg.hasStickerMessage() && msg.getStickerMessage().hasContextInfo()) {
            return msg.getStickerMessage().getContextInfo().getExpiration();
        }
        return 0;
    }

    private void sendAck(BinaryNode original) {
        String id = original.attr("id");
        String from = original.attr("from");
        if (id == null || from == null) return;
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("class", original.tag());
        attrs.put("id", id);
        attrs.put("to", from);
        String participant = original.attr("participant");
        if (participant != null) attrs.put("participant", participant);
        String recipient = original.attr("recipient");
        if (recipient != null) attrs.put("recipient", recipient);

        if (!"message".equals(original.tag())) {
            String type = original.attr("type");
            if (type != null) attrs.put("type", type);
        }
        send(new BinaryNode("ack", attrs, null));
    }

    private void sendDeliveryReceipt(BinaryNode message) {
        sendReceipt(message, null);
    }

    private final ConcurrentMap<String, Integer> messageRetries
        = new ConcurrentHashMap<>();
    private static final int MAX_RETRY_COUNT = 5;

    private void sendRetryReceipt(BinaryNode message) {
        String id = message.attr("id");
        String from = message.attr("from");
        if (id == null || from == null) return;

        // Increment 10b crash guard (pre-existing, predates Increment
        // 10): on an unpaired client creds is null, and the
        // registration node below dereferenced
        // creds.registrationId, throwing NPE out of the
        // decrypt-failure catch and risking the reader loop. With no
        // creds there is no registration id to send, so skip the
        // receipt (without consuming a retry count) and log, and let
        // the caller continue its catch flow. Receipt behaviour when
        // creds exist is unchanged.
        if (creds == null) {
            LOG.warn("Skipping retry receipt id={}: no creds (unpaired client)", id);
            return;
        }

        int retryCount = messageRetries.merge(id, 1, Integer::sum);
        if (retryCount > MAX_RETRY_COUNT) {
            LOG.warn("Giving up retry-receipt for id={} (count={} > {})", id, retryCount, MAX_RETRY_COUNT);
            return;
        }

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("id", id);
        attrs.put("to", from);
        attrs.put("type", "retry");
        String participant = message.attr("participant");
        if (participant != null) attrs.put("participant", participant);
        String recipient = message.attr("recipient");
        if (recipient != null) attrs.put("recipient", recipient);

        BinaryNode retry = new BinaryNode("retry", Map.of(
            "count", Integer.toString(retryCount),
            "id",    id,
            "t",     message.attr("t", "0"),
            "v",     "1"
        ), null);
        BinaryNode registration = new BinaryNode("registration", Map.of(),
            encodeUintBE(creds.registrationId, 4));

        send(new BinaryNode("receipt", attrs, List.of(retry, registration)));
        LOG.debug("Sent retry receipt id={} count={}", id, retryCount);
    }

    private static byte[] encodeUintBE(int value, int width) {
        byte[] out = new byte[width];
        for (int i = 0; i < width; i++) out[width - 1 - i] = (byte) ((value >>> (8 * i)) & 0xFF);
        return out;
    }

    public void sendReadReceipt(String chatJid, String msgId, String senderJid) {
        sendStateReceipt(chatJid, msgId, senderJid, "read");
    }

    public void sendReadReceipt(MessageReceiver.Decoded target) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        String senderJid = target.groupJid() != null ? target.senderJid() : null;
        sendReadReceipt(chatJid, target.msgId(), senderJid);
    }

    public void sendPlayedReceipt(String chatJid, String msgId, String senderJid) {
        sendStateReceipt(chatJid, msgId, senderJid, "played");
    }

    public void sendPlayedReceipt(MessageReceiver.Decoded target) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        String senderJid = target.groupJid() != null ? target.senderJid() : null;
        sendPlayedReceipt(chatJid, target.msgId(), senderJid);
    }

    public void sendReadReceiptBatch(String chatJid, List<String> msgIds, String senderJid) {
        sendStateReceiptBatch(chatJid, msgIds, senderJid, "read");
    }

    public void sendReadSelfReceipt(String chatJid, String msgId) {
        sendStateReceipt(chatJid, msgId, null, "read-self");
    }

    public void sendPlayedSelfReceipt(String chatJid, String msgId) {
        sendStateReceipt(chatJid, msgId, null, "played-self");
    }

    public CompletableFuture<Void> sendMediaRetryReceipt(
            String messageId, String chatJid, String senderJid,
            boolean isFromMe, boolean isGroup, byte[] mediaKey) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (creds == null || creds.meJid == null) {
                    throw new IllegalStateException("Not logged in");
                }
                // Increment 9: node construction moved verbatim to
                // MediaRetryRequest.build (same attrs, same crypto);
                // only the construction site changed.
                BinaryNode receiptStanza = MediaRetryRequest.build(
                    messageId, chatJid, isFromMe, isGroup ? senderJid : null,
                    mediaKey, Jid.bare(creds.meJid));
                send(receiptStanza);
            } catch (Exception e) {
                LOG.error("Failed to send media retry receipt: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Void> sendMediaRetryReceipt(
            MessageReceiver.Decoded target, byte[] mediaKey) {
        boolean isGroup = target.groupJid() != null && !target.groupJid().isBlank();
        String chatJid = isGroup ? target.groupJid() : target.senderJid();
        String ownJid = creds != null && creds.meJid != null ? Jid.bare(creds.meJid) : "";
        boolean isFromMe = !ownJid.isBlank() && ownJid.equals(Jid.bare(target.senderJid()));
        return sendMediaRetryReceipt(target.msgId(), chatJid, target.senderJid(), isFromMe, isGroup, mediaKey);
    }

    /** Default media-update wait budget for {@link #updateMediaMessage} convenience overloads. */
    public static final long DEFAULT_MEDIA_UPDATE_TIMEOUT_MS = 30_000L;

    /**
     * Increment 9: Baileys {@code updateMediaMessage}
     * ({@code lib/Socket/messages-send.js:1070}): asks the phone to
     * re-upload a message's media and returns the re-uploaded
     * location. Baileys' flow is: {@code assertMediaContent} (media
     * key from the message), {@code encryptMediaRetryRequest}
     * (receipt node, sent), wait for {@code messages.media-update}
     * matching the message id, {@code decryptMediaRetryData} on the
     * update's encrypted media, then mutate the message content's
     * {@code directPath}/{@code url} in place and emit
     * {@code messages.update}.
     * <p>
     * Portable part implemented here: the retry-request send (via
     * {@link #sendMediaRetryReceipt}, i.e. {@link MediaRetryRequest}),
     * the media-update correlation (via {@link MediaUpdateWaiter} on
     * the existing Increment 2 dispatch — no dispatch change), and
     * the decrypt + {@code SUCCESS} check (via
     * {@link #decryptMediaRetryNotification}). The waiter is
     * registered before the request is sent, so an update arriving
     * between send and wait cannot be missed.
     * <p>
     * Divergences, stated not hidden:
     * <ul>
     *   <li>Wa messages are immutable protos, so wa-java cannot
     *       mutate the caller's content in place: the returned
     *       {@link Wa.MediaRetryNotification} carries the new
     *       {@code directPath} (and {@code result}); the caller
     *       rebuilds its content from it. No {@code messages.update}
     *       is emitted for the same reason — Baileys' emission
     *       carries the mutated message, which has no wa-java
     *       equivalent here.</li>
     *   <li>The caller must supply the media: either a
     *       {@link Wa.Message} carrying a media key (this overload
     *       extracts it via {@link MediaRetryRequest#mediaKeyOf}),
     *       or the media key bytes directly (the bytes overload).
     *       wa-java cannot re-upload original media bytes it does
     *       not hold, and it never re-uploads stale bytes — the
     *       phone performs the re-upload.</li>
     *   <li>Timeout: Baileys waits indefinitely for the media
     *       update; wa-java completes with {@code null} on timeout,
     *       following the Increment 7 {@code ConnectionUpdateWaiter}
     *       pattern. A disconnected transport is not an error here:
     *       {@link #send(BinaryNode)} logs and drops the request
     *       (pre-existing silent-failure behaviour, unchanged), so
     *       the call resolves {@code null} when the timeout elapses.</li>
     *   <li>Error mapping: Baileys throws {@code HttpError} with the
     *       media-retry status code; wa-java fails the future with
     *       {@link IllegalStateException} carrying the same
     *       information (error code, non-{@code SUCCESS} result, or
     *       missing ciphertext).</li>
     * </ul>
     *
     * @param messageId message key id; must not be blank
     * @param remoteJid message key remote JID (chat JID)
     * @param fromMe message key fromMe
     * @param participant group participant, or null for DMs
     * @param message message whose media key is used; a message
     *                without a media key fails the future with
     *                {@link IllegalArgumentException}
     * @param timeoutMs non-negative media-update wait budget in milliseconds
     * @return future of the phone's {@code MediaRetryNotification},
     *         or null on timeout; failed on send failure, error
     *         code, non-{@code SUCCESS} result, or missing ciphertext
     */
    public CompletableFuture<Wa.MediaRetryNotification> updateMediaMessage(
            String messageId, String remoteJid, boolean fromMe, String participant,
            Wa.Message message, long timeoutMs) {
        byte[] mediaKey = MediaRetryRequest.mediaKeyOf(message);
        if (mediaKey == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                "message carries no media key (not an image/video/audio/document/sticker message)"));
        }
        return updateMediaMessage(messageId, remoteJid, fromMe, participant, mediaKey, timeoutMs);
    }

    /**
     * Increment 9: {@link #updateMediaMessage(String, String, boolean, String, Wa.Message, long)}
     * with the media key supplied directly, for callers that hold it
     * outside the message proto.
     *
     * @param messageId message key id; must not be blank
     * @param remoteJid message key remote JID (chat JID)
     * @param fromMe message key fromMe
     * @param participant group participant, or null for DMs
     * @param mediaKey 32-byte media key; null/empty fails the future
     * @param timeoutMs non-negative media-update wait budget in milliseconds
     * @return future of the phone's {@code MediaRetryNotification}, or null on timeout
     */
    public CompletableFuture<Wa.MediaRetryNotification> updateMediaMessage(
            String messageId, String remoteJid, boolean fromMe, String participant,
            byte[] mediaKey, long timeoutMs) {
        if (messageId == null || messageId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("messageId must not be blank"));
        }
        if (mediaKey == null || mediaKey.length == 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("mediaKey must not be empty"));
        }
        if (timeoutMs < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("timeoutMs must not be negative"));
        }
        boolean isGroup = remoteJid != null && remoteJid.endsWith("@g.us");
        CompletableFuture<MessagesMediaUpdate.MediaUpdate> waiter =
            MediaUpdateWaiter.waitFor(eventBus, messageId, timeoutMs);
        CompletableFuture<Void> sent = sendMediaRetryReceipt(
            messageId, remoteJid, participant, fromMe, isGroup, mediaKey);
        // A failed send must not leave the waiter subscribed until its timeout.
        sent.whenComplete((value, error) -> {
            if (error != null) waiter.cancel(false);
        });
        return sent.thenCompose(ignored -> waiter).thenApply(update -> {
            if (update == null) return null;
            if (update.errorCode() != null) {
                throw new IllegalStateException(
                    "Failed to re-upload media (error code " + update.errorCode() + ")");
            }
            if (update.ciphertext() == null || update.iv() == null) {
                throw new IllegalStateException(
                    "Failed to re-upload media (missing ciphertext)");
            }
            Wa.MediaRetryNotification notification = decryptMediaRetryNotification(
                update.ciphertext(), update.iv(), messageId, mediaKey);
            if (notification.getResult() != Wa.MediaRetryNotification.ResultType.SUCCESS) {
                throw new IllegalStateException(
                    "Media re-upload failed by device (" + notification.getResult() + ")");
            }
            return notification;
        });
    }

    /**
     * Increment 9: {@link #updateMediaMessage(String, String, boolean, String, Wa.Message, long)}
     * with the default {@link #DEFAULT_MEDIA_UPDATE_TIMEOUT_MS} wait budget.
     */
    public CompletableFuture<Wa.MediaRetryNotification> updateMediaMessage(
            String messageId, String remoteJid, boolean fromMe, String participant,
            Wa.Message message) {
        return updateMediaMessage(messageId, remoteJid, fromMe, participant, message,
            DEFAULT_MEDIA_UPDATE_TIMEOUT_MS);
    }

    /**
     * Increment 9: {@link #updateMediaMessage(String, String, boolean, String, byte[], long)}
     * with the default {@link #DEFAULT_MEDIA_UPDATE_TIMEOUT_MS} wait budget.
     */
    public CompletableFuture<Wa.MediaRetryNotification> updateMediaMessage(
            String messageId, String remoteJid, boolean fromMe, String participant,
            byte[] mediaKey) {
        return updateMediaMessage(messageId, remoteJid, fromMe, participant, mediaKey,
            DEFAULT_MEDIA_UPDATE_TIMEOUT_MS);
    }

    public static Wa.MediaRetryNotification decryptMediaRetryNotification(
            byte[] ciphertext, byte[] iv, String messageId, byte[] mediaKey) {
        byte[] retryKey = Hkdf.derive(
            mediaKey, null, "WhatsApp Media Retry Notification".getBytes(StandardCharsets.UTF_8), 32);
        byte[] plaintext = AesGcm.decrypt(
            retryKey, iv, ciphertext, messageId.getBytes(StandardCharsets.UTF_8));
        try {
            return Wa.MediaRetryNotification.parseFrom(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse MediaRetryNotification", e);
        }
    }

    private void sendStateReceipt(String chatJid, String msgId, String senderJid, String type) {
        sendStateReceiptBatch(chatJid, List.of(msgId), senderJid, type);
    }

    private void sendStateReceiptBatch(String chatJid, List<String> msgIds,
                                       String senderJid, String type) {
        if (msgIds.isEmpty()) return;
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("id", msgIds.get(0));
        attrs.put("type", type);
        attrs.put("to", chatJid);
        attrs.put("t", Long.toString(System.currentTimeMillis() / 1000));
        if (senderJid != null && !senderJid.isBlank() && chatJid.endsWith("@g.us")) {
            attrs.put("participant", senderJid);
        }
        BinaryNode receipt;
        if (msgIds.size() == 1) {
            receipt = new BinaryNode("receipt", attrs, null);
        } else {
            List<BinaryNode> items = new ArrayList<>();
            for (int i = 1; i < msgIds.size(); i++) {
                items.add(BinaryNode.of("item", Map.of("id", msgIds.get(i))));
            }
            BinaryNode list = new BinaryNode("list", Map.of(), items);
            receipt = new BinaryNode("receipt", attrs, List.of(list));
        }
        send(receipt);
    }

    private void sendReceipt(BinaryNode message, String type) {
        String id = message.attr("id");
        String from = message.attr("from");
        if (id == null || from == null) return;
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("id", id);
        attrs.put("to", from);
        if (type != null) attrs.put("type", type);
        String participant = message.attr("participant");
        if (participant != null) attrs.put("participant", participant);
        String recipient = message.attr("recipient");
        if (recipient != null) attrs.put("recipient", recipient);
        send(new BinaryNode("receipt", attrs, null));
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        connectedAtEpochSec = 0;
        if (keepalive != null) { keepalive.shutdownNow(); keepalive = null; }
        if (frame != null) frame.close();
        closeLatch.countDown();
    }

    public CompletableFuture<Map<String, ContactStatus>> isOnWhatsApp(
            Collection<String> phones) {
        String iqId = newIqId();
        String sid = newIqId();
        BinaryNode q = UsyncQuery.buildContactQuery(iqId, sid, phones);
        LOG.debug("Sent contact check USync IQ id={} for {} phones", iqId, phones.size());
        return sendIqAsync(q).thenApply(UsyncQuery::parseContactResult);
    }

    /**
     * Resolves a WhatsApp LID JID (e.g. "1234567890@lid") to a Phone Number JID (e.g. "62812345678@s.whatsapp.net").
     * Returns cached resolution if available, or initiates an asynchronous USync contact query to WhatsApp.
     */
    public CompletableFuture<String> resolveLidToPn(String lidJid) {
        if (lidJid == null || lidJid.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        String bareLid = lidJid.contains(":")
            ? lidJid.substring(0, lidJid.indexOf(':')) + (lidJid.contains("@") ? lidJid.substring(lidJid.indexOf('@')) : "@lid")
            : (lidJid.contains("@") ? lidJid : lidJid + "@lid");

        if (!bareLid.endsWith("@lid")) {
            return CompletableFuture.completedFuture(bareLid);
        }

        String cached = LID_TO_PN_MAP.get(bareLid);
        if (cached != null && !cached.isBlank()) {
            return CompletableFuture.completedFuture(cached);
        }

        return isOnWhatsApp(List.of(bareLid)).thenApply(map -> {
            String mapped = LID_TO_PN_MAP.get(bareLid);
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
            ContactStatus status = map.get(bareLid);
            if (status != null && status.jid() != null && status.jid().endsWith("@" + Jid.SERVER_WHATSAPP)) {
                LID_TO_PN_MAP.put(bareLid, status.jid());
                // Increment 2: a newly learned mapping is a lid-mapping
                // event, as in Baileys' sync-action/mex mapping paths.
                memoryStore.upsertLidMapping(bareLid, status.jid());
                dispatchEvent(BaileysEventType.LID_MAPPING_UPDATE,
                    new LidMappingUpdate(bareLid, status.jid()));
                return status.jid();
            }
            return null;
        }).exceptionally(ex -> {
            LOG.warn("Failed to resolve LID {} to PN: {}", bareLid, ex.getMessage());
            return null;
        });
    }

    /**
     * Synchronously looks up a cached Phone Number JID for a given LID JID.
     */
    public static String getCachedPnForLid(String lidJid) {
        if (lidJid == null || lidJid.isBlank()) return null;
        String bareLid = lidJid.contains(":")
            ? lidJid.substring(0, lidJid.indexOf(':')) + (lidJid.contains("@") ? lidJid.substring(lidJid.indexOf('@')) : "@lid")
            : (lidJid.contains("@") ? lidJid : lidJid + "@lid");
        return LID_TO_PN_MAP.get(bareLid);
    }

    public void sendChatPresence(String chatJid, ChatPresence state) {
        BinaryNode child;
        if (state == ChatPresence.COMPOSING) {
            child = new BinaryNode("composing", Map.of("media", "text"), null);
        } else if (state == ChatPresence.RECORDING) {
            child = new BinaryNode("composing", Map.of("media", "audio"), null);
        } else {
            child = new BinaryNode("paused", Map.of(), null);
        }
        BinaryNode stanza = new BinaryNode("chatstate", Map.of("to", chatJid), List.of(child));
        send(stanza);
        LOG.debug("Sent chat presence state={} to={}", state, chatJid);
    }

    public void sendPresence(boolean available) {
        BinaryNode stanza = new BinaryNode("presence", Map.of(
            "type", available ? "available" : "unavailable"
        ), null);
        send(stanza);
        LOG.debug("Sent presence type={}", available ? "available" : "unavailable");
    }

    public void subscribePresence(String jid) {
        if (jid == null) return;
        BinaryNode stanza = new BinaryNode("presence", Map.of(
            "type", "subscribe",
            "to", jid
        ), null);
        send(stanza);
        LOG.debug("Sent presence subscribe to={}", jid);
    }

    public static BinaryNode buildProfilePictureQuery(String iqId, String targetJid, boolean preview) {
        String normalizedJid = targetJid;
        if (normalizedJid == null || normalizedJid.isBlank()) {
            throw new IllegalArgumentException("targetJid cannot be empty");
        }
        if (normalizedJid.contains("@")) {
            String bare = Jid.bare(normalizedJid);
            if (bare != null) normalizedJid = bare;
        } else {
            String digits = normalizedJid.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                normalizedJid = digits + "@" + Jid.SERVER_WHATSAPP;
            } else {
                throw new IllegalArgumentException("targetJid must contain digits or valid JID format");
            }
        }

        String type = preview ? "preview" : "image";
        return new BinaryNode("iq",
            Map.of(
                "to", Jid.SERVER_WHATSAPP,
                "target", normalizedJid,
                "type", "get",
                "xmlns", "w:profile:picture",
                "id", iqId
            ),
            List.of(new BinaryNode("picture", Map.of("type", type, "query", "url"), null)));
    }

    public static ProfilePictureInfo parseProfilePictureResponse(BinaryNode resp) {
        if (resp == null || "error".equals(resp.attr("type"))) {
            return null;
        }
        BinaryNode pic = resp.child("picture");
        if (pic == null) return null;
        String status = pic.attr("status");
        if ("204".equals(status) || "304".equals(status)) {
            return null;
        }
        String id = pic.attr("id");
        String url = pic.attr("url");
        String picType = pic.attr("type");
        String directPath = pic.attr("direct_path");
        if (url == null || url.isBlank()) {
            return null;
        }
        return new ProfilePictureInfo(id, url, picType, directPath);
    }

    public CompletableFuture<ProfilePictureInfo> getProfilePictureInfo(String targetJid, boolean preview) {
        String resolvedJid = targetJid;
        if (resolvedJid == null || resolvedJid.isBlank()) {
            if (creds != null && creds.meJid != null) {
                resolvedJid = creds.meJid;
            } else {
                return CompletableFuture.completedFuture(null);
            }
        }
        try {
            String iqId = newIqId();
            BinaryNode iq = buildProfilePictureQuery(iqId, resolvedJid, preview);
            return sendIqAsync(iq)
                .thenApply(JaWaClient::parseProfilePictureResponse)
                .exceptionally(ex -> {
                    LOG.warn("Failed to get profile picture info for {}: {}", targetJid, ex.getMessage());
                    return null;
                });
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    public CompletableFuture<ProfilePictureInfo> getProfilePictureInfo(String targetJid) {
        return getProfilePictureInfo(targetJid, false);
    }

    public CompletableFuture<List<BotListInfo>> getBotList() {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "bot", "id", iqId),
            List.of(new BinaryNode("bot", Map.of("v", "2"), null)));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode bot = resp.child("bot");
            if (bot == null) return List.of();
            List<BotListInfo> list = new ArrayList<>();
            for (BinaryNode section : bot.childrenList()) {
                if (!"section".equals(section.tag())) continue;
                for (BinaryNode b : section.childrenList()) {
                    if ("bot".equals(b.tag())) {
                        String personaId = b.attr("persona_id");
                        String jid = b.attr("jid");
                        if (personaId != null || jid != null) {
                            list.add(new BotListInfo(personaId, jid));
                        }
                    }
                }
            }
            return list;
        });
    }

    public CompletableFuture<PrivacySettings> getPrivacySettings() {
        BinaryNode iq = PrivacyQuery.buildFetch(newIqId());
        return sendIqAsync(iq).thenApply(resp -> {
            Map<String, String> map = PrivacyQuery.parseFetch(resp);
            return new PrivacySettings(
                map.getOrDefault("groupadd", ""),
                map.getOrDefault("last", ""),
                map.getOrDefault("status", ""),
                map.getOrDefault("profile", ""),
                map.getOrDefault("readreceipts", ""),
                map.getOrDefault("online", ""),
                map.getOrDefault("calladd", ""),
                map
            );
        });
    }

    /**
     * Increment 7: Baileys {@code fetchPrivacySettings}
     * ({@code lib/Socket/chats.js:64}). Like Baileys, this caches:
     * with {@code force = false} a previously fetched result is
     * returned without a network round trip; {@code force = true}
     * always refetches and replaces the cache. The pre-existing
     * {@link #getPrivacySettings()} is the same IQ without the cache
     * and is unchanged. Baileys-faithful quirk, documented not
     * fixed: the typed {@code update*Privacy} methods below do not
     * invalidate this cache (Baileys' {@code privacyQuery} does not
     * touch its {@code privacySettings} variable either), so after
     * an update callers that need fresh values must fetch with
     * {@code force = true}.
     *
     * @param force true to refetch even when a cached result exists
     * @return future resolving to the privacy settings
     */
    public CompletableFuture<PrivacySettings> fetchPrivacySettings(boolean force) {
        PrivacySettings cached = cachedPrivacySettings;
        if (cached != null && !force) {
            return CompletableFuture.completedFuture(cached);
        }
        return getPrivacySettings().thenApply(fetched -> {
            cachedPrivacySettings = fetched;
            return fetched;
        });
    }

    /** Increment 7: {@link #fetchPrivacySettings(boolean)} with Baileys' default ({@code force = false}). */
    public CompletableFuture<PrivacySettings> fetchPrivacySettings() {
        return fetchPrivacySettings(false);
    }

    /**
     * Increment 7: Baileys {@code updateMessagesPrivacy}
     * ({@code lib/Socket/chats.js:102}): {@code privacyQuery} with
     * category {@code messages}. Thin facade over
     * {@link #setPrivacySetting}; the value is passed through
     * verbatim ({@code all}, {@code contacts},
     * {@code contact_blacklist}, {@code none}).
     */
    public CompletableFuture<Void> updateMessagesPrivacy(String value) {
        return setPrivacySetting(PrivacyQuery.CATEGORY_MESSAGES, value);
    }

    /** Increment 7: Baileys {@code updateCallPrivacy}: category {@code calladd}. See {@link #updateMessagesPrivacy}. */
    public CompletableFuture<Void> updateCallPrivacy(String value) {
        return setPrivacySetting(PrivacyQuery.CATEGORY_CALL_ADD, value);
    }

    /** Increment 7: Baileys {@code updateLastSeenPrivacy}: category {@code last}. See {@link #updateMessagesPrivacy}. */
    public CompletableFuture<Void> updateLastSeenPrivacy(String value) {
        return setPrivacySetting(PrivacyQuery.CATEGORY_LAST_SEEN, value);
    }

    /** Increment 7: Baileys {@code updateOnlinePrivacy}: category {@code online}. See {@link #updateMessagesPrivacy}. */
    public CompletableFuture<Void> updateOnlinePrivacy(String value) {
        return setPrivacySetting(PrivacyQuery.CATEGORY_ONLINE, value);
    }

    /** Increment 7: Baileys {@code updateProfilePicturePrivacy}: category {@code profile}. See {@link #updateMessagesPrivacy}. */
    public CompletableFuture<Void> updateProfilePicturePrivacy(String value) {
        return setPrivacySetting(PrivacyQuery.CATEGORY_PROFILE_PICTURE, value);
    }

    /** Increment 7: Baileys {@code updateStatusPrivacy}: category {@code status}. See {@link #updateMessagesPrivacy}. */
    public CompletableFuture<Void> updateStatusPrivacy(String value) {
        return setPrivacySetting(PrivacyQuery.CATEGORY_STATUS, value);
    }

    /** Increment 7: Baileys {@code updateReadReceiptsPrivacy}: category {@code readreceipts}. See {@link #updateMessagesPrivacy}. */
    public CompletableFuture<Void> updateReadReceiptsPrivacy(String value) {
        return setPrivacySetting(PrivacyQuery.CATEGORY_READ_RECEIPTS, value);
    }

    /**
     * Increment 7: Baileys {@code updateGroupsAddPrivacy}
     * ({@code lib/Socket/chats.js:123}): {@code privacyQuery} with
     * category {@code groupadd} (the same key the pre-existing
     * {@code PrivacySettings.groupAdd} field reads). See
     * {@link #updateMessagesPrivacy}.
     */
    public CompletableFuture<Void> updateGroupsAddPrivacy(String value) {
        return setPrivacySetting(PrivacyQuery.CATEGORY_GROUP_ADD, value);
    }

    public CompletableFuture<Void> setPrivacySetting(String category, String value) {
        String iqId = newIqId();
        BinaryNode catNode = new BinaryNode("category", Map.of(
            "name", category,
            "value", value
        ), null);
        BinaryNode privacyNode = new BinaryNode("privacy", Map.of(), List.of(catNode));
        BinaryNode iq = new BinaryNode("iq", Map.of(
            "id", iqId,
            "to", Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "privacy"
        ), List.of(privacyNode));
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("Failed to set privacy setting: " + resp);
            }
        });
    }

    public CompletableFuture<Void> setDefaultDisappearingTimer(int seconds) {
        // Increment 7: shape factored into PrivacyQuery (identical IQ).
        BinaryNode iq = PrivacyQuery.buildUpdateDefaultDisappearingMode(newIqId(), seconds);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("Failed to set default disappearing timer: " + resp);
            }
        });
    }

    /**
     * Increment 7: Baileys {@code updateDefaultDisappearingMode}
     * ({@code lib/Socket/chats.js:126}). Baileys' name for the
     * pre-existing {@link #setDefaultDisappearingTimer(int)}: the
     * same {@code xmlns=disappearing_mode} set IQ, with the duration
     * as a decimal string. This method delegates to it unchanged;
     * the older wa-java name is kept, not renamed.
     *
     * @param durationSeconds default disappearing duration in seconds (0 disables)
     * @return future completing when the setting is saved
     */
    public CompletableFuture<Void> updateDefaultDisappearingMode(int durationSeconds) {
        return setDefaultDisappearingTimer(durationSeconds);
    }

    /**
     * Increment 7: Baileys {@code cleanDirtyBits}
     * ({@code lib/Socket/chats.js:400}): an {@code iq} to
     * {@code s.whatsapp.net}, type {@code set},
     * {@code xmlns=urn:xmpp:whatsapp:dirty}, carrying
     * {@code <clean type [timestamp]>}. Baileys sends it with
     * {@code sendNode} (fire-and-forget, no response wait); wa-java
     * routes it through {@code sendIqAsync} instead, so the future
     * completes when the server's result IQ arrives — the only
     * divergence, and it keeps this method testable through the
     * client's IQ seam like every other IQ method here. The
     * timestamp attr is omitted when {@code fromTimestamp} is null
     * <em>or</em> 0, mirroring Baileys' JS truthiness check
     * ({@code fromTimestamp ? ... : null}).
     *
     * <p>Scoped fail-fast: this method routes through
     * {@link #sendIqAsync}, so a disconnected client fails the
     * future promptly instead of dropping the stanza in
     * {@link #send} and leaving the future pending.
     *
     * @param type dirty-bit type, e.g. {@code account_sync}
     * @param fromTimestamp lower-bound timestamp, or null/0 to omit it
     * @return future completing when the server answers
     */
    public CompletableFuture<Void> cleanDirtyBits(String type, Long fromTimestamp) {
        if (type == null || type.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("type must not be blank"));
        }
        Map<String, String> cleanAttrs = new HashMap<>();
        cleanAttrs.put("type", type);
        if (fromTimestamp != null && fromTimestamp != 0L) {
            cleanAttrs.put("timestamp", String.valueOf(fromTimestamp));
        }
        BinaryNode clean = new BinaryNode("clean", cleanAttrs, null);
        BinaryNode iq = new BinaryNode("iq", Map.of(
            "id", newIqId(),
            "to", Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "urn:xmpp:whatsapp:dirty"
        ), List.of(clean));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public CompletableFuture<String> getContactQrLink(boolean revoke) {
        String iqId = newIqId();
        BinaryNode qrNode = new BinaryNode("qr", Map.of(
            "type", "contact",
            "action", revoke ? "revoke" : "get"
        ), null);
        BinaryNode iq = new BinaryNode("iq", Map.of(
            "id", iqId,
            "type", "set",
            "xmlns", "w:qr"
        ), List.of(qrNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode qr = resp.child("qr");
            if (qr == null) throw new IllegalStateException("no qr node in response: " + resp);
            String code = qr.attr("code");
            if (code == null) throw new IllegalStateException("no code in qr response");
            return "https://wa.me/qr/" + code;
        });
    }

    public CompletableFuture<ContactQrTarget> resolveContactQrLink(String codeOrUrl) {
        String code = codeOrUrl;
        if (code.contains("/")) code = code.substring(code.lastIndexOf('/') + 1);
        if (code.contains("?")) code = code.substring(0, code.indexOf('?'));
        String iqId = newIqId();
        BinaryNode qrNode = new BinaryNode("qr", Map.of(
            "code", code
        ), null);
        BinaryNode iq = new BinaryNode("iq", Map.of(
            "id", iqId,
            "type", "get",
            "xmlns", "w:qr"
        ), List.of(qrNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode qr = resp.child("qr");
            if (qr == null) throw new IllegalStateException("contact qr link not found: " + resp);
            return new ContactQrTarget(
                qr.attr("jid"),
                qr.attr("notify"),
                qr.attr("type")
            );
        });
    }

    public CompletableFuture<Map<String, UserInfo>>
            getUserInfo(Collection<String> targetJids) {
        String iqId = newIqId();
        String sid = newIqId();
        BinaryNode q = UsyncQuery.buildUserInfoQuery(iqId, sid, targetJids);
        LOG.debug("Sent user-info query id={} for {} jids", iqId, targetJids.size());
        return sendIqAsync(q).thenApply(resp -> {
            var map = UsyncQuery.parseUserInfoResult(resp);
            for (var entry : map.entrySet()) {
                if (entry.getValue().lid() != null && !entry.getValue().lid().isEmpty()) {
                    LID_TO_PN_MAP.put(entry.getValue().lid(), entry.getKey());
                }
            }
            return map;
        });
    }

    public CompletableFuture<UserInfo>
            getUserInfo(String targetJid) {
        return getUserInfo(List.of(targetJid))
            .thenApply(map -> map.get(targetJid));
    }

    /**
     * Increment 6: Baileys {@code executeUSyncQuery}
     * ({@code lib/Socket/socket.js}), made public. Executes a
     * caller-supplied arbitrary USync query — the fixed builders
     * behind {@link #isOnWhatsApp} and {@link #getUserInfo} cannot
     * express an arbitrary protocol set — and returns the parsed
     * result. The query model owns the protocol elements and user
     * nodes (see {@link UsyncQuery.GenericQuery}); per-protocol
     * field parsing is the caller's job over
     * {@link UsyncQuery.GenericResult#userNodes()}. No socket
     * internals are exposed: only the query model goes in and the
     * parsed result comes out.
     *
     * @param query caller-supplied USync query; must carry at least one
     *              protocol element (Baileys throws otherwise)
     * @return future of the parsed result, or null for a non-
     *         {@code result} IQ (Baileys returns {@code undefined} there)
     */
    public CompletableFuture<UsyncQuery.GenericResult> executeUSyncQuery(UsyncQuery.GenericQuery query) {
        if (query == null || query.queryElements().isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("USyncQuery must have at least one protocol"));
        }
        String iqId = newIqId();
        String sid = newIqId();
        BinaryNode iq = UsyncQuery.buildGenericQuery(iqId, sid, query);
        LOG.debug("Sent generic USync IQ id={} sid={} for {} protocol(s)", iqId, sid, query.queryElements().size());
        return sendIqAsync(iq).thenApply(UsyncQuery::parseGenericResult);
    }

    /**
     * Increment 7: Baileys {@code fetchDisappearingDuration}
     * ({@code lib/Socket/chats.js:185}): a USync query with the
     * disappearing-mode protocol for each JID, executed through the
     * Increment 6 {@link #executeUSyncQuery} hook (no new transport:
     * the query model is
     * {@link UsyncQuery#buildDisappearingModeGenericQuery} and the
     * per-user parsing is
     * {@link UsyncQuery#parseDisappearingModeResult}). Baileys
     * returns a list of {@code {id, disappearing_mode: {duration,
     * setAt}}} entries, including entries whose protocol field is
     * absent; wa-java returns a JID-keyed map and omits users with
     * no (or an unparseable) {@code disappearing_mode} child — see
     * that parser's Javadoc. A non-{@code result} IQ yields an empty
     * map, matching {@code executeUSyncQuery}'s null-result handling
     * folded into a map.
     *
     * @param jids JIDs to query; blank entries are skipped
     * @return future resolving to a JID-keyed map of disappearing durations
     */
    public CompletableFuture<Map<String, UsyncQuery.DisappearingDuration>> fetchDisappearingDuration(List<String> jids) {
        UsyncQuery.GenericQuery query = UsyncQuery.buildDisappearingModeGenericQuery(jids);
        return executeUSyncQuery(query).thenApply(result ->
            result == null ? Map.of() : UsyncQuery.parseDisappearingModeResult(result));
    }

    /** Increment 7: varargs form of {@link #fetchDisappearingDuration(List)}. */
    public CompletableFuture<Map<String, UsyncQuery.DisappearingDuration>> fetchDisappearingDuration(String... jids) {
        return fetchDisappearingDuration(jids == null ? List.of() : List.of(jids));
    }

    public CompletableFuture<BusinessProfile> getBusinessProfile(String jid) {
        String iqId = newIqId();
        String targetJid = jid.contains("@") ? jid : jid + "@s.whatsapp.net";
        BinaryNode profileNode = new BinaryNode("profile", Map.of("jid", targetJid), null);
        BinaryNode bizNode = new BinaryNode("business_profile", Map.of("v", "244"), List.of(profileNode));
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "w:biz", "id", iqId),
            List.of(bizNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode bp = resp.child("business_profile");
            if (bp == null) {
                return null;
            }
            BinaryNode prof = bp.child("profile");
            if (prof == null) {
                return null;
            }
            String address = prof.child("address") != null ? prof.child("address").textContent() : null;
            String email = prof.child("email") != null ? prof.child("email").textContent() : null;
            String description = prof.child("description") != null ? prof.child("description").textContent() : null;
            List<String> websites = new ArrayList<>();
            for (BinaryNode w : prof.children("website")) {
                if (w.textContent() != null && !w.textContent().isEmpty()) {
                    websites.add(w.textContent());
                }
            }
            List<BusinessProfile.Category> categories = new ArrayList<>();
            BinaryNode catNode = prof.child("categories");
            if (catNode != null) {
                for (BinaryNode c : catNode.children("category")) {
                    categories.add(new BusinessProfile.Category(c.attr("id"), c.textContent()));
                }
            }
            BinaryNode bhNode = prof.child("business_hours");
            String timezone = bhNode != null ? bhNode.attr("timezone") : null;
            List<BusinessProfile.BusinessHourConfig> hours = new ArrayList<>();
            if (bhNode != null) {
                for (BinaryNode h : bhNode.children("business_hours_config")) {
                    hours.add(new BusinessProfile.BusinessHourConfig(
                        h.attr("day_of_week"),
                        h.attr("mode"),
                        h.attr("open_time"),
                        h.attr("close_time")
                    ));
                }
            }
            Map<String, String> options = new HashMap<>();
            BinaryNode poNode = prof.child("profile_options");
            if (poNode != null) {
                for (BinaryNode opt : poNode.childrenList()) {
                    if (opt.textContent() != null) {
                        options.put(opt.tag(), opt.textContent());
                    }
                }
            }
            return new BusinessProfile(
                prof.attr("jid"),
                address,
                email,
                description,
                websites,
                categories,
                timezone,
                hours,
                options
            );
        });
    }

    public CompletableFuture<BusinessMessageLinkTarget> resolveBusinessMessageLink(String codeOrUrl) {
        String code = codeOrUrl;
        if (code.contains("/")) code = code.substring(code.lastIndexOf('/') + 1);
        if (code.contains("?")) code = code.substring(0, code.indexOf('?'));
        String iqId = newIqId();
        BinaryNode qrNode = new BinaryNode("qr", Map.of("code", code), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("type", "get", "xmlns", "w:qr", "id", iqId),
            List.of(qrNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode child = resp.child("qr");
            if (child == null) {
                return null;
            }
            String jid = child.attr("jid");
            String pushName = child.attr("notify");
            String message = child.child("message") != null ? child.child("message").textContent() : null;
            BinaryNode biz = child.child("business");
            boolean isSigned = false;
            String verifiedName = null;
            String verifiedLevel = null;
            if (biz != null) {
                isSigned = "true".equalsIgnoreCase(biz.attr("is_signed"));
                verifiedName = biz.attr("verified_name");
                verifiedLevel = biz.attr("verified_level");
            }
            return new BusinessMessageLinkTarget(
                jid,
                pushName,
                message,
                isSigned,
                verifiedName,
                verifiedLevel
            );
        });
    }

    public CompletableFuture<String> getStatus(String targetJid) {
        String iqId = newIqId();
        Map<String, String> attrs = new HashMap<>();
        if (targetJid != null && !targetJid.isBlank()) {
            attrs.put("jid", targetJid.contains("@") ? targetJid : targetJid + "@s.whatsapp.net");
        }
        BinaryNode statusNode = new BinaryNode("status", attrs, null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "status", "id", iqId),
            List.of(statusNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode s = resp.child("status");
            return s != null ? s.textContent() : null;
        });
    }

    public CompletableFuture<Void> setGroupJoinApprovalMode(String groupJid, boolean approvalRequired) {
        String iqId = newIqId();
        BinaryNode groupJoin = new BinaryNode("group_join", Map.of("state", approvalRequired ? "on" : "off"), null);
        BinaryNode approvalNode = new BinaryNode("membership_approval_mode", Map.of(), List.of(groupJoin));
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", groupJid, "type", "set", "xmlns", "w:g2", "id", iqId),
            List.of(approvalNode));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public CompletableFuture<Void> setGroupMemberAddMode(String groupJid, boolean adminOnly) {
        String iqId = newIqId();
        String modeStr = adminOnly ? "admin_add" : "all_member_add";
        BinaryNode modeNode = new BinaryNode("member_add_mode", Map.of(), modeStr.getBytes(StandardCharsets.UTF_8));
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", groupJid, "type", "set", "xmlns", "w:g2", "id", iqId),
            List.of(modeNode));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public CompletableFuture<List<GroupJoinRequest>> getGroupRequestParticipants(String groupJid) {
        String iqId = newIqId();
        BinaryNode reqNode = new BinaryNode("membership_approval_requests", Map.of(), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", groupJid, "type", "get", "xmlns", "w:g2", "id", iqId),
            List.of(reqNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode reqs = resp.child("membership_approval_requests");
            List<GroupJoinRequest> list = new ArrayList<>();
            if (reqs != null) {
                for (BinaryNode item : reqs.children("membership_approval_request")) {
                    String jid = item.attr("jid");
                    long time = 0L;
                    try {
                        String tStr = item.attr("request_time");
                        if (tStr != null && !tStr.isEmpty()) {
                            time = Long.parseLong(tStr);
                        }
                    } catch (Exception ignored) {}
                    if (jid != null) {
                        list.add(new GroupJoinRequest(jid, time));
                    }
                }
            }
            return list;
        });
    }

    public CompletableFuture<Void> updateGroupRequestParticipants(
            String groupJid, List<String> userJids, boolean approve) {
        String iqId = newIqId();
        String action = approve ? "approve" : "reject";
        List<BinaryNode> participants = new ArrayList<>();
        for (String jid : userJids) {
            participants.add(new BinaryNode("participant", Map.of("jid", jid), null));
        }
        BinaryNode actionNode = new BinaryNode(action, Map.of(), participants);
        BinaryNode reqAction = new BinaryNode("membership_requests_action", Map.of(), List.of(actionNode));
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", groupJid, "type", "set", "xmlns", "w:g2", "id", iqId),
            List.of(reqAction));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public CompletableFuture<List<CommunitySubGroup>> getSubGroups(String communityJid) {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", communityJid, "type", "get", "xmlns", "w:g2", "id", iqId),
            List.of(new BinaryNode("sub_groups", Map.of(), null)));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode subGroupsNode = resp.child("sub_groups");
            if (subGroupsNode == null) return List.of();
            List<CommunitySubGroup> result = new ArrayList<>();
            for (BinaryNode child : subGroupsNode.childrenList()) {
                if (!"group".equals(child.tag())) continue;
                String jid = child.attr("jid");
                if (jid == null) {
                    String gId = child.attr("id");
                    if (gId != null) jid = gId + "@" + Jid.SERVER_GROUP;
                }
                String subject = child.attr("subject");
                long nameSetAt = 0L;
                String st = child.attr("s_t");
                if (st != null) {
                    try { nameSetAt = Long.parseLong(st); } catch (NumberFormatException ignored) {}
                }
                boolean defaultSub = child.child("default_sub_group") != null;
                result.add(new CommunitySubGroup(jid, subject, nameSetAt, defaultSub));
            }
            return result;
        });
    }

    public CompletableFuture<List<String>> getLinkedGroupsParticipants(String communityJid) {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", communityJid, "type", "get", "xmlns", "w:g2", "id", iqId),
            List.of(new BinaryNode("linked_groups_participants", Map.of(), null)));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode node = resp.child("linked_groups_participants");
            if (node == null) return List.of();
            List<String> list = new ArrayList<>();
            for (BinaryNode child : node.childrenList()) {
                if ("participant".equals(child.tag()) && child.attr("jid") != null) {
                    list.add(child.attr("jid"));
                }
            }
            return list;
        });
    }

    public CompletableFuture<Void> linkGroup(String communityJid, String childGroupJid) {
        String iqId = newIqId();
        BinaryNode groupNode = new BinaryNode("group", Map.of("jid", childGroupJid), null);
        BinaryNode linkNode = new BinaryNode("link", Map.of("link_type", "sub_group"), List.of(groupNode));
        BinaryNode linksNode = new BinaryNode("links", Map.of(), List.of(linkNode));
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", communityJid, "type", "set", "xmlns", "w:g2", "id", iqId),
            List.of(linksNode));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public CompletableFuture<Void> unlinkGroup(String communityJid, String childGroupJid) {
        String iqId = newIqId();
        BinaryNode groupNode = new BinaryNode("group", Map.of("jid", childGroupJid), null);
        BinaryNode unlinkNode = new BinaryNode("unlink", Map.of("unlink_type", "sub_group"), List.of(groupNode));
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", communityJid, "type", "set", "xmlns", "w:g2", "id", iqId),
            List.of(unlinkNode));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public CompletableFuture<BinaryNode> getGroupInfoFromInvite(String groupJid, String inviterJid, String code, long expiration) {
        String iqId = newIqId();
        BinaryNode addReq = new BinaryNode("add_request", Map.of("code", code, "expiration", String.valueOf(expiration), "admin", inviterJid), null);
        BinaryNode query = new BinaryNode("query", Map.of(), List.of(addReq));
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", groupJid, "type", "get", "xmlns", "w:g2", "id", iqId),
            List.of(query));
        return sendIqAsync(iq);
    }

    public CompletableFuture<Void> joinGroupWithInvite(String groupJid, String inviterJid, String code, long expiration) {
        String iqId = newIqId();
        BinaryNode acceptNode = new BinaryNode("accept", Map.of("code", code, "expiration", String.valueOf(expiration), "admin", inviterJid), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", groupJid, "type", "set", "xmlns", "w:g2", "id", iqId),
            List.of(acceptNode));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public CompletableFuture<Void> rejectCall(String callFrom, String callId) {
        if (creds == null || creds.meJid == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not logged in"));
        }
        String ownJid = Jid.bare(creds.meJid);
        String cleanFrom = Jid.bare(callFrom);
        BinaryNode rejectNode = new BinaryNode("reject",
            Map.of("call-id", callId, "call-creator", cleanFrom, "count", "0"),
            null);
        BinaryNode callNode = new BinaryNode("call",
            Map.of("id", generateMessageId(), "from", ownJid, "to", cleanFrom),
            List.of(rejectNode));
        send(callNode);
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Long> subscribeNewsletterLiveUpdates(String newsletterJid) {
        String iqId = newIqId();
        BinaryNode liveNode = new BinaryNode("live_updates", Map.of(), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", newsletterJid, "type", "set", "xmlns", "newsletter", "id", iqId),
            List.of(liveNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode child = resp.child("live_updates");
            if (child != null) {
                String dStr = child.attr("duration");
                if (dStr != null) {
                    try {
                        return Long.parseLong(dStr);
                    } catch (Exception ignored) {}
                }
            }
            return 0L;
        });
    }

    public CompletableFuture<Void> markNewsletterViewed(String newsletterJid, List<Long> serverIds) {
        List<BinaryNode> items = new ArrayList<>();
        for (Long id : serverIds) {
            items.add(new BinaryNode("item", Map.of("server_id", String.valueOf(id)), null));
        }
        BinaryNode listNode = new BinaryNode("list", Map.of(), items);
        BinaryNode receiptNode = new BinaryNode("receipt",
            Map.of("to", newsletterJid, "type", "view", "id", generateMessageId()),
            List.of(listNode));
        send(receiptNode);
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<List<NewsletterMessage>>
            getNewsletterMessages(String newsletterJid, int count, Long beforeServerId) {
        String iqId = newIqId();
        Map<String, String> attrs = new HashMap<>();
        attrs.put("type", "jid");
        attrs.put("jid", newsletterJid);
        if (count > 0) {
            attrs.put("count", String.valueOf(count));
        }
        if (beforeServerId != null && beforeServerId > 0) {
            attrs.put("before", String.valueOf(beforeServerId));
        }
        BinaryNode q = new BinaryNode("messages", attrs, null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "newsletter", "id", iqId),
            List.of(q));
        return sendIqAsync(iq).thenApply(resp -> {
            List<NewsletterMessage> list = new ArrayList<>();
            BinaryNode msgsNode = resp.child("messages");
            if (msgsNode == null) return list;
            for (BinaryNode msgNode : msgsNode.children("message")) {
                long serverId = 0L;
                try {
                    String s = msgNode.attr("server_id");
                    if (s != null) serverId = Long.parseLong(s);
                } catch (Exception ignored) {}
                String msgId = msgNode.attr("id");
                String type = msgNode.attr("type");
                long timestamp = 0L;
                try {
                    String t = msgNode.attr("t");
                    if (t != null) timestamp = Long.parseLong(t);
                } catch (Exception ignored) {}
                int viewsCount = 0;
                BinaryNode viewsNode = msgNode.child("views_count");
                if (viewsNode != null) {
                    try {
                        String c = viewsNode.attr("count");
                        if (c != null) viewsCount = Integer.parseInt(c);
                    } catch (Exception ignored) {}
                }
                Map<String, Integer> reactions = new HashMap<>();
                BinaryNode reactsNode = msgNode.child("reactions");
                if (reactsNode != null) {
                    for (BinaryNode r : reactsNode.children("reaction")) {
                        String code = r.attr("code");
                        int rCount = 0;
                        try {
                            String c = r.attr("count");
                            if (c != null) rCount = Integer.parseInt(c);
                        } catch (Exception ignored) {}
                        if (code != null) reactions.put(code, rCount);
                    }
                }
                Wa.Message protoMsg = null;
                BinaryNode pt = msgNode.child("plaintext");
                if (pt != null && pt.bytesContent() != null) {
                    try {
                        protoMsg = Wa.Message.parseFrom(pt.bytesContent());
                    } catch (Exception ignored) {}
                }
                list.add(new NewsletterMessage(
                    serverId, msgId, type, timestamp, viewsCount, reactions, protoMsg
                ));
            }
            return list;
        });
    }

    public void disconnect() {
        close();
    }

    public CompletableFuture<Void> logout() {
        if (creds == null || creds.meJid == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not logged in"));
        }
        String iqId = newIqId();
        BinaryNode rmDevice = new BinaryNode("remove-companion-device", Map.of(
            "jid", creds.meJid,
            "reason", "user_initiated"
        ), null);
        BinaryNode iq = new BinaryNode("iq", Map.of(
            "to", Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "md",
            "id", iqId
        ), List.of(rmDevice));
        return sendIqAsync(iq).handle((resp, err) -> {
            try {
                store.clear();
            } catch (Exception ignored) {}
            close();
            return null;
        });
    }

    public CompletableFuture<Void> updatePushName(String newPushName) {
        if (creds == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not logged in"));
        }
        creds.pushName = newPushName;
        try {
            store.save(creds);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        send(new BinaryNode("presence", Map.of(
            "type", "available",
            "name", newPushName
        ), null));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Increment 7: Baileys {@code updateProfileName}
     * ({@code lib/Socket/chats.js:285}): {@code chatModify({pushNameSetting: name}, '')},
     * i.e. the {@code setting_pushName} app-state patch built by
     * {@link AppPatchBuilder#buildPushNameSetting} and sent through
     * {@link #appPatch} (Increment 5 hook). This is the path that
     * actually propagates the new profile name to the server and to
     * other devices via app-state sync.
     *
     * <p>Relationship to {@link #updatePushName}, stated not hidden:
     * the two methods are <em>not</em> interchangeable and neither
     * replaces the other. {@code updatePushName} is the pre-existing
     * presence-based path — it updates the local credential and
     * broadcasts a {@code <presence name>} stanza, but sends no
     * app-state patch, so it does not change the server-side profile
     * name the way Baileys' {@code updateProfileName} does. This
     * method does the app-state patch but does not update the local
     * credential or broadcast presence. Callers that want both
     * effects call both methods; neither method calls the other, so
     * no existing caller changes behaviour.
     *
     * @param name new profile (push) name; must not be null (Baileys
     *             passes it through verbatim, so an empty string is
     *             allowed here)
     * @return future completing with the encoder result on a successful send;
     *         fails when no app-state key id is configured (see {@link #appPatch})
     */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> updateProfileName(String name) {
        if (name == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("name must not be null"));
        }
        return appPatch(AppPatchBuilder.buildPushNameSetting(name));
    }

    /**
     * Increment 7: Baileys {@code updateDisableLinkPreviewsPrivacy}
     * ({@code lib/Socket/chats.js:828}): {@code chatModify({disableLinkPreviews:
     * {isPreviewsDisabled}}, '')}, i.e. the
     * {@code setting_disableLinkPreviews} app-state patch built by
     * {@link AppPatchBuilder#buildDisableLinkPreviews} and sent
     * through {@link #appPatch}. Despite the name it is not a
     * privacy IQ (Baileys sends no {@code xmlns=privacy} stanza for
     * it); {@code PrivacyService} exposes a delegate for
     * discoverability with that caveat in its Javadoc.
     *
     * @param isPreviewsDisabled true to disable link previews
     * @return future completing with the encoder result on a successful send
     */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> updateDisableLinkPreviewsPrivacy(boolean isPreviewsDisabled) {
        return appPatch(AppPatchBuilder.buildDisableLinkPreviews(isPreviewsDisabled));
    }

    public CompletableFuture<String> sendMexQuery(String queryId, String jsonVariables) {
        String iqId = newIqId();
        String payload = "{\"variables\":" + (jsonVariables != null && !jsonVariables.isBlank() ? jsonVariables : "{}") + "}";
        BinaryNode queryNode = new BinaryNode("query",
            Map.of("query_id", queryId),
            payload.getBytes(StandardCharsets.UTF_8));
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "w:mex", "id", iqId),
            List.of(queryNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode res = resp.child("result");
            return res != null ? res.textContent() : null;
        });
    }

    /**
     * Increment 6: Baileys {@code fetchAccountReachoutTimelock}
     * ({@code lib/Socket/socket.js}). Sends the MEX query with id
     * {@code 23983697327930364} (Baileys
     * {@code QueryIds.REACHOUT_TIMELOCK}, {@code lib/Types/Mex.js})
     * and empty variables, parses the
     * {@code xwa2_fetch_account_reachout_timelock} result with
     * {@link ReachoutTimelock}, and emits a partial
     * {@code connection.update} carrying only
     * {@code reachoutTimeLock} — exactly Baileys' emit. When the
     * payload is absent or carries GraphQL errors, the future fails
     * and nothing is emitted (Baileys throws before its emit).
     * Fetch-path parsing differs from the notification path: no
     * now+60s expiry default and no enforcement-type validation (see
     * {@link ReachoutTimelock}).
     *
     * @return future of the parsed timelock value
     */
    public CompletableFuture<ConnectionUpdate.ReachoutTimeLock> fetchAccountReachoutTimelock() {
        return sendMexQuery(ReachoutTimelock.QUERY_ID, ReachoutTimelock.VARIABLES_JSON)
            .thenApply(json -> {
                ConnectionUpdate.ReachoutTimeLock lock = ReachoutTimelock.parse(json);
                if (lock == null) {
                    throw new IllegalStateException(
                        "fetch_account_reachout_timelock: unexpected MEX response structure");
                }
                dispatchEvent(BaileysEventType.CONNECTION_UPDATE,
                    new ConnectionUpdate(null, null, null, null, null, null, null, lock));
                return lock;
            });
    }

    public CompletableFuture<Void> followNewsletter(String newsletterJid) {
        String vars = "{\"newsletter_id\":\"" + newsletterJid + "\"}";
        return sendMexQuery("9926858900719341", vars).thenApply(resp -> null);
    }

    public CompletableFuture<Void> unfollowNewsletter(String newsletterJid) {
        String vars = "{\"newsletter_id\":\"" + newsletterJid + "\"}";
        return sendMexQuery("6392786840836363", vars).thenApply(resp -> null);
    }

    public CompletableFuture<Void> toggleMuteNewsletter(String newsletterJid, boolean mute) {
        String queryId = mute ? "6274038279359549" : "6068417879924485";
        String vars = "{\"newsletter_id\":\"" + newsletterJid + "\"}";
        return sendMexQuery(queryId, vars).thenApply(resp -> null);
    }

    public CompletableFuture<NewsletterMetadata> getNewsletterInfo(String newsletterJid) {
        String vars = "{\"fetch_creation_time\":true,\"fetch_full_image\":true,\"fetch_viewer_metadata\":true,\"input\":{\"key\":\"" + newsletterJid + "\",\"type\":\"JID\"}}";
        return sendMexQuery("6563316087068696", vars).thenApply(json -> parseNewsletterJson(json, newsletterJid));
    }

    public CompletableFuture<NewsletterMetadata> getNewsletterInfoWithInvite(String inviteCodeOrUrl) {
        String code = inviteCodeOrUrl;
        if (code.contains("/")) code = code.substring(code.lastIndexOf('/') + 1);
        if (code.contains("?")) code = code.substring(0, code.indexOf('?'));
        String vars = "{\"fetch_creation_time\":true,\"fetch_full_image\":true,\"fetch_viewer_metadata\":false,\"input\":{\"key\":\"" + code + "\",\"type\":\"INVITE\"}}";
        return sendMexQuery("6563316087068696", vars).thenApply(json -> parseNewsletterJson(json, null));
    }

    private static NewsletterMetadata parseNewsletterJson(String json, String fallbackJid) {
        if (json == null || json.isBlank()) return null;
        String id = extractJsonField(json, "id");
        if (id == null) id = fallbackJid;
        String name = extractNestedJsonField(json, "name", "text");
        String desc = extractNestedJsonField(json, "description", "text");
        String invite = extractJsonField(json, "invite");
        String picUrl = extractNestedJsonField(json, "picture", "direct_path");
        if (picUrl == null) picUrl = extractNestedJsonField(json, "preview", "direct_path");
        String role = extractJsonField(json, "role");
        String muteState = extractJsonField(json, "mute");
        boolean muted = "ON".equalsIgnoreCase(muteState);

        long creationTime = 0L;
        String ct = extractJsonField(json, "creation_time");
        if (ct != null) {
            try { creationTime = Long.parseLong(ct.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
        }

        int subCount = 0;
        String sc = extractJsonField(json, "subscribers_count");
        if (sc != null) {
            try { subCount = Integer.parseInt(sc.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
        }

        return new NewsletterMetadata(
            id, name, desc, invite, creationTime, subCount, role, muted, picUrl
        );
    }

    private static String extractJsonField(String json, String fieldName) {
        if (json == null) return null;
        String needle = "\"" + fieldName + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return end > start ? json.substring(start + 1, end) : null;
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') end++;
        return json.substring(start, end).trim();
    }

    private static String extractNestedJsonField(String json, String parentField, String childField) {
        if (json == null) return null;
        String needle = "\"" + parentField + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int brace = json.indexOf('{', idx + needle.length());
        if (brace < 0 || brace > idx + needle.length() + 10) return null;
        int closeBrace = json.indexOf('}', brace);
        if (closeBrace < 0) return null;
        String block = json.substring(brace, closeBrace + 1);
        return extractJsonField(block, childField);
    }

    public CompletableFuture<OrderDetails> getOrderDetails(String orderId, String tokenBase64) {
        String iqId = newIqId();
        BinaryNode widthNode = new BinaryNode("width", Map.of(), "100".getBytes(StandardCharsets.UTF_8));
        BinaryNode heightNode = new BinaryNode("height", Map.of(), "100".getBytes(StandardCharsets.UTF_8));
        BinaryNode dimsNode = new BinaryNode("image_dimensions", Map.of(), List.of(widthNode, heightNode));
        BinaryNode tokenNode = new BinaryNode("token", Map.of(), tokenBase64.getBytes(StandardCharsets.UTF_8));
        BinaryNode orderNode = new BinaryNode("order",
            Map.of("op", "get", "id", orderId),
            List.of(dimsNode, tokenNode));
        BinaryNode iq = new BinaryNode("iq",
            Map.of(
                "to", Jid.SERVER_WHATSAPP,
                "type", "get",
                "xmlns", "fb:thrift_iq",
                "smax_id", "5",
                "id", iqId
            ),
            List.of(orderNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode ord = resp.child("order");
            if (ord == null) return null;
            String id = ord.attr("id");
            long createdAt = 0L;
            try {
                String ts = ord.attr("creation_ts");
                if (ts != null) createdAt = Long.parseLong(ts);
            } catch (Exception ignored) {}

            String catalogId = null;
            BinaryNode catNode = ord.child("catalog");
            if (catNode != null) {
                BinaryNode catIdNode = catNode.child("id");
                if (catIdNode != null) catalogId = catIdNode.textContent();
            }

            OrderDetails.OrderPrice price = null;
            BinaryNode priceNode = ord.child("price");
            if (priceNode != null) {
                long subtotal = 0L;
                long total = 0L;
                try {
                    BinaryNode sub = priceNode.child("subtotal");
                    if (sub != null && sub.textContent() != null) subtotal = Long.parseLong(sub.textContent());
                } catch (Exception ignored) {}
                try {
                    BinaryNode tot = priceNode.child("total");
                    if (tot != null && tot.textContent() != null) total = Long.parseLong(tot.textContent());
                } catch (Exception ignored) {}
                String currency = priceNode.child("currency") != null ? priceNode.child("currency").textContent() : null;
                String status = priceNode.child("price_status") != null ? priceNode.child("price_status").textContent() : null;
                price = new OrderDetails.OrderPrice(subtotal, total, currency, status);
            }

            List<OrderDetails.OrderProduct> products = new ArrayList<>();
            for (BinaryNode pNode : ord.children("product")) {
                String pId = pNode.child("id") != null ? pNode.child("id").textContent() : null;
                String pName = pNode.child("name") != null ? pNode.child("name").textContent() : null;
                long pPrice = 0L;
                try {
                    BinaryNode pr = pNode.child("price");
                    if (pr != null && pr.textContent() != null) pPrice = Long.parseLong(pr.textContent());
                } catch (Exception ignored) {}
                String pCurrency = pNode.child("currency") != null ? pNode.child("currency").textContent() : null;
                int pQuantity = 1;
                try {
                    BinaryNode qty = pNode.child("quantity");
                    if (qty != null && qty.textContent() != null) pQuantity = Integer.parseInt(qty.textContent());
                } catch (Exception ignored) {}
                String imgId = null;
                String imgUrl = null;
                BinaryNode imgNode = pNode.child("image");
                if (imgNode != null) {
                    if (imgNode.child("id") != null) imgId = imgNode.child("id").textContent();
                    if (imgNode.child("url") != null) imgUrl = imgNode.child("url").textContent();
                }
                products.add(new OrderDetails.OrderProduct(
                    pId, pName, pPrice, pCurrency, pQuantity, imgId, imgUrl
                ));
            }

            return new OrderDetails(id, createdAt, catalogId, price, products);
        });
    }

    public AuthCreds creds() { return creds; }
}
