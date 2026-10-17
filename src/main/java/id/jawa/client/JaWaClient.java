package id.jawa.client;

import com.google.protobuf.ByteString;
import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.model.Blocklist;
import id.jawa.domain.model.BlocklistItem;
import id.jawa.domain.model.BotListInfo;
import id.jawa.domain.model.BusinessMessageLinkTarget;
import id.jawa.domain.model.BusinessProfile;
import id.jawa.domain.model.ChatPresence;
import id.jawa.domain.model.CommunitySubGroup;
import id.jawa.domain.model.ContactQrTarget;
import id.jawa.domain.model.ContactStatus;
import id.jawa.domain.model.DeviceInfo;
import id.jawa.domain.model.GroupJoinRequest;
import id.jawa.domain.model.Jid;
import id.jawa.domain.model.NewsletterMessage;
import id.jawa.domain.model.NewsletterMetadata;
import id.jawa.domain.model.OrderDetails;
import id.jawa.domain.model.PrivacySettings;
import id.jawa.domain.model.ProfilePictureInfo;
import id.jawa.domain.model.Receipt;
import id.jawa.domain.model.SignedPreKey;
import id.jawa.domain.model.StatusPrivacy;
import id.jawa.domain.model.UserInfo;
import id.jawa.domain.model.WaConstants;
import id.jawa.domain.store.AuthStore;
import id.jawa.domain.store.FilePreKeyStorage;
import id.jawa.domain.store.FileSenderKeyStorage;
import id.jawa.domain.store.FileSessionStorage;
import id.jawa.domain.store.InMemorySenderKeyStore;
import id.jawa.domain.store.InMemorySignalKeyStore;
import id.jawa.domain.store.SignalKeyStore;
import id.jawa.domain.store.SqliteAuthStore;
import id.jawa.feature.appstate.AppStateKey;
import id.jawa.feature.appstate.AppStateProcessor;
import id.jawa.feature.appstate.AppStateSyncQuery;
import id.jawa.feature.appstate.FileAppStateKeyStorage;
import id.jawa.feature.appstate.PatchName;
import id.jawa.feature.media.MediaConn;
import id.jawa.feature.media.MediaCrypto;
import id.jawa.feature.media.MediaDownloader;
import id.jawa.feature.media.MediaUploader;
import id.jawa.feature.messaging.GroupAction;
import id.jawa.feature.messaging.GroupListQuery;
import id.jawa.feature.messaging.GroupSender;
import id.jawa.feature.messaging.MessageEncoder;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.feature.messaging.MessageSender;
import id.jawa.feature.messaging.UsyncQuery;
import id.jawa.feature.pairing.ClientPayloadBuilder;
import id.jawa.feature.pairing.PairingCodeHandler;
import id.jawa.feature.pairing.PairingHandler;
import id.jawa.feature.signal.JaWaProtocolStore;
import id.jawa.feature.signal.PreKeyBundleFetcher;
import id.jawa.feature.signal.PreKeyManager;
import id.jawa.feature.signal.SessionBootstrap;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryDecoder;
import id.jawa.protocol.codec.BinaryEncoder;
import id.jawa.protocol.codec.BinaryNode;
import id.jawa.protocol.connection.FrameSocket;
import id.jawa.protocol.connection.NoiseHandshake;
import id.jawa.protocol.connection.NoiseTransport;
import id.jawa.protocol.crypto.AesGcm;
import id.jawa.protocol.crypto.Bytes;
import id.jawa.protocol.crypto.Curve25519;
import id.jawa.protocol.crypto.Hkdf;
import id.jawa.protocol.crypto.KeyPair25519;
import id.jawa.protocol.crypto.Sha256;
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
    private JaWaProtocolStore protocolStore;
    private PairingCodeHandler pairCodeHandler;
    private ScheduledExecutorService keepalive;
    private Listener listener = new Listener() {};
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
    }

    /**
     * Registers an event listener callback for connection events, QR codes, incoming messages, and receipts.
     *
     * @param l Listener implementation
     * @return This client instance for method chaining
     */
    public JaWaClient listener(Listener l) { this.listener = l != null ? l : new Listener() {}; return this; }

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

        creds = store.load();
        boolean isPairing = (creds == null || creds.account == null);
        if (creds == null) {
            creds = AuthCreds.generate();
            LOG.info("No creds found — generated fresh pair: regId={}", creds.registrationId);
        } else if (isPairing) {
            LOG.info("Found unpaired creds (regId={}) — resuming QR pairing", creds.registrationId);
        } else {
            LOG.info("Found paired creds — login mode (jid={})", creds.meJid);
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
            signalStore        = new InMemorySignalKeyStore(preKeyStorage);
            senderKeyStore     = new InMemorySenderKeyStore(senderKeyStorage);
            appStateProcessor  = new AppStateProcessor(appStateKeyStorage);
        } else if (signalDir != null && sessionStorage == null) {
            sessionStorage     = new FileSessionStorage(signalDir.resolve("sessions"));
            preKeyStorage      = new FilePreKeyStorage(signalDir.resolve("prekeys"));
            senderKeyStorage   = new FileSenderKeyStorage(signalDir.resolve("sender-keys"));
            appStateKeyStorage = new FileAppStateKeyStorage(signalDir.resolve("appstate-keys"));
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
        LOG.info("Noise handshake complete — steady state {}", isPairing ? "(pairing)" : "(login)");

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
                LOG.trace("recv: {}", node);

                try {
                    if (handleStanza(node, pair)) continue;
                    listener.onStanza(node);
                } catch (Throwable per) {
                    LOG.warn("Error handling stanza {} — continuing", node.tag(), per);
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
        for (Wa.Message.AppStateSyncKey k : pm.getAppStateSyncKeyShare().getKeysList()) {
            byte[] id = k.getKeyId().getKeyId().toByteArray();
            byte[] data = k.getKeyData().getKeyData().toByteArray();
            if (data.length != 32) continue;
            appStateKeyStorage.put(new AppStateKey(id, data));
            count++;
        }
        if (count > 0) {
            LOG.info("Persisted {} app-state key(s) shared by {}", count, decoded.senderJid());
            try { listener.onAppStateKeysReceived(count); }
            catch (Throwable t) { LOG.warn("listener.onAppStateKeysReceived threw", t); }
        }
    }

    public CompletableFuture<List<AppStateProcessor.Mutation>>
            requestAppStateSync(PatchName name, long fromVersion) {
        if (appStateProcessor == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("signalDir not set — app-state sync requires persistent storage"));
        }
        String iqId = newIqId();
        BinaryNode iq = fromVersion < 0
            ? AppStateSyncQuery.buildSnapshotRequest(iqId, name)
            : AppStateSyncQuery.buildPatchRequest(iqId, name, fromVersion);
        LOG.debug("Sent app-state sync IQ id={} name={} from={}", iqId, name.wire, fromVersion);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("App-state sync rejected: {}", resp);
                return List.<AppStateProcessor.Mutation>of();
            }
            AppStateSyncQuery.PatchList list =
                AppStateSyncQuery.parseResponse(resp);
            if (list == null) return List.<AppStateProcessor.Mutation>of();

            List<AppStateProcessor.Mutation> all = new ArrayList<>();
            if (list.snapshot() != null) {
                all.addAll(appStateProcessor.decodeSnapshot(list.snapshot(), false));
            }
            for (Wa.SyncdPatch p : list.patches()) {
                all.addAll(appStateProcessor.decodePatch(p, false));
            }
            LOG.info("App-state sync {}: {} snapshot record(s) + {} patch(es) -> {} mutation(s) decoded",
                name.wire,
                list.snapshot() == null ? 0 : list.snapshot().getRecordsCount(),
                list.patches().size(),
                all.size());
            if (!all.isEmpty()) {
                try { listener.onAppStateMutations(name, all); }
                catch (Throwable t) { LOG.warn("listener.onAppStateMutations threw", t); }
            }
            return all;
        });
    }

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
        try { listener.onError(new IllegalStateException("server rejected session: " + detail)); }
        catch (Throwable t) { LOG.warn("listener.onError threw", t); }
    }

    private void handleStreamError(BinaryNode node) {
        BinaryNode conflict = node.child("conflict");
        if (conflict != null) {
            String type = conflict.attr("type", "");
            LOG.warn("Stream error: conflict type={} — another session for this device is active", type);

            terminated.set(true);
            try { listener.onTerminated(TerminationReason.REPLACED, "conflict type=" + type, true); }
            catch (Throwable t) { LOG.warn("listener.onTerminated threw", t); }
            return;
        }
        LOG.warn("Stream error: {} (treating as transient)", node);
        try { listener.onTerminated(TerminationReason.TRANSIENT, "stream:error " + node, false); }
        catch (Throwable t) { LOG.warn("listener.onTerminated threw", t); }
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

    public String sendIq(BinaryNode iq, Consumer<BinaryNode> onResponse) {
        String id = iq.attr("id");
        if (id == null) throw new IllegalArgumentException("iq has no id attribute");
        if (onResponse != null) pendingIqResults.put(id, onResponse);
        send(iq);
        return id;
    }

    public CompletableFuture<BinaryNode> sendIqAsync(BinaryNode iq) {
        var f = new CompletableFuture<BinaryNode>();
        sendIq(iq, f::complete);
        return f;
    }

    public CompletableFuture<String> requestPairingCode(String phoneNumber, String customCode) {
        if (creds.account != null) {
            throw new IllegalStateException("already paired (creds.account is set) — cannot request a pairing code");
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

    public CompletableFuture<String> sendNewsletterText(
            String newsletterJid, String text) {
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

    public CompletableFuture<String> sendNewsletterReaction(
            String newsletterJid, String serverId, String reactionCode) {
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
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", targetJid, "type", "get", "xmlns", "w:profile:picture", "id", iqId),
            List.of(new BinaryNode("picture", Map.of("type", "preview"), null)));
        return sendIqAsync(iq).thenApply(resp -> {
            var picNode = resp.child("picture");
            return picNode != null ? picNode.attr("url") : null;
        });
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
            Map.of("to", groupJid, "type", "set", "xmlns", "w:profile:picture", "id", iqId),
            List.of(picNode));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> removeGroupPicture(String groupJid) {
        String iqId = newIqId();
        BinaryNode picNode = new BinaryNode("picture",
            Map.of("type", "delete"),
            null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", groupJid, "type", "set", "xmlns", "w:profile:picture", "id", iqId),
            List.of(picNode));
        return sendIqAsync(iq);
    }

    public CompletableFuture<BinaryNode> updateProfilePicture(byte[] imageBytes) {
        Jid myJid = Jid.parse(creds.meJid);
        String ownBareJid = myJid != null ? myJid.user() + "@s.whatsapp.net" : creds.meJid;
        return updateGroupPicture(ownBareJid, imageBytes);
    }

    public CompletableFuture<BinaryNode> removeProfilePicture() {
        Jid myJid = Jid.parse(creds.meJid);
        String ownBareJid = myJid != null ? myJid.user() + "@s.whatsapp.net" : creds.meJid;
        return removeGroupPicture(ownBareJid);
    }

    public CompletableFuture<BinaryNode> updateBlockStatus(String targetJid, boolean block) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode("item", Map.of("action", block ? "block" : "unblock", "jid", targetJid), null);
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "blocklist", "id", iqId),
            List.of(item));
        return sendIqAsync(iq);
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
        return sendIqAsync(iq).thenApply(this::parseBlocklistNode);
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
        final Wa.Message outboundMsg = wrapEphemeralIfNeeded(toUser, msg);
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
            String resolvedRoutingJid = toUser;
            String resolvedEncryptionLid = null;
            if (toUser != null && toUser.endsWith("@lid")) {
                String mappedPn = LID_TO_PN_MAP.get(toUser);
                if (mappedPn != null && !mappedPn.isEmpty()) {
                    LOG.debug("DM dual-identity resolved: route via PN {} but encrypt via LID {}", mappedPn, toUser);
                    resolvedRoutingJid = mappedPn;
                    resolvedEncryptionLid = toUser;
                }
            }
            final String finalRoutingJid = resolvedRoutingJid;
            final String finalEncryptionLid = resolvedEncryptionLid;

            Jid pnBase = Jid.parse(finalRoutingJid);
            Jid lidBase = finalEncryptionLid != null ? Jid.parse(finalEncryptionLid) : null;

            List<String> allDeviceJids = new ArrayList<>();
            List<String> encryptionDeviceJids = new ArrayList<>();

            for (var entry : devicesMap.entrySet()) {
                Jid base = Jid.parse(entry.getKey());
                if (base == null) continue;
                boolean isTarget = targetJidObj != null && base.user().equals(targetJidObj.user());

                for (var d : entry.getValue()) {
                    if (isTarget && finalEncryptionLid != null && pnBase != null && lidBase != null) {
                        String wireJid = pnBase.user() + (d.id() != 0 ? ":" + d.id() : "") + "@" + pnBase.server();
                        String encJid = lidBase.user() + (d.id() != 0 ? ":" + d.id() : "") + "@" + lidBase.server();
                        allDeviceJids.add(wireJid);
                        encryptionDeviceJids.add(encJid);
                    } else {
                        String jidStr = d.asJid(base.user(), base.server()).asString();
                        allDeviceJids.add(jidStr);
                        encryptionDeviceJids.add(jidStr);
                    }
                }
            }

            return fetchBundlesAndInstallSessions(encryptionDeviceJids).thenApply(addresses -> {
                String msgId = generateMessageId();
                messageSendLock.lock();
                try {
                    MessageSender.Result result = MessageSender.buildDualIdentityStanza(
                        protocolStore, creds, msgId, finalRoutingJid,
                        allDeviceJids, encryptionDeviceJids, outboundMsg);
                    send(result.stanza());
                    int ownCount = countOwnDevices(allDeviceJids, ownUser);
                    LOG.info("Sent message id={} to={} ({} device(s) total, {} own-companion DSM)",
                        msgId, finalRoutingJid, allDeviceJids.size(),
                        Math.max(0, ownCount - 1));
                    return msgId;
                } finally {
                    messageSendLock.unlock();
                }
            });
        });
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
        final Wa.Message outboundMsg = wrapEphemeralIfNeeded(groupJid, msg);
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
                                groupJid, allDeviceJids, outboundMsg);
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

    public CompletableFuture<String> sendNewsletterMessage(
            String newsletterJid, Wa.Message msg) {
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
            if (cached != null && !cached.isExpired(java.time.Instant.now())) {
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

                LOG.info("Server requested restart (code=515) — expected after pairing; reconnect to enter steady state");
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
            LOG.info("Login successful — meJid={} lid={} platform={}",
                node.attr("jid", creds.meJid),
                node.attr("lid", creds.meLid),
                node.attr("platform", creds.platform));
            reconnectAttempts.set(0);
            sendActivePassive();
            sendPresenceAvailable();
            listener.onConnected();
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
            } else if ("receipt".equals(node.tag())) {
                try {
                    Receipt parsed = Receipt.parse(node);
                    if (parsed != null) listener.onReceipt(parsed);
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
            return true;
        }
        if (pairSuccess != null) {
            BinaryNode reply = pair.handlePairSuccess(node);
            send(reply);
            store.save(creds);
            listener.onPaired(creds.meJid, creds.pushName, creds.platform);

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

    public void send(BinaryNode node) {
        if (transport == null || frame == null) {
            LOG.warn("Cannot send node while transport/frame is not connected");
            return;
        }
        byte[] plain = BinaryEncoder.encode(node);
        byte[] enc = transport.encrypt(plain);
        frame.send(enc);
    }

    private void handleInboundMessage(BinaryNode message) {
        try {
            MessageReceiver.Decoded decoded =
                MessageReceiver.decode(message, protocolStore, senderKeyStore);
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
        } catch (MessageReceiver.DecryptException de) {
            LOG.warn("Decrypt failed for message id={} from={}: {}",
                message.attr("id"), message.attr("from"), de.getMessage());
            sendAck(message);
            sendRetryReceipt(message);
        }
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
                String ownJid = Jid.bare(creds.meJid);
                byte[] retryKey = Hkdf.derive(
                    mediaKey, null, "WhatsApp Media Retry Notification".getBytes(StandardCharsets.UTF_8), 32);
                Wa.ServerErrorReceipt receipt = Wa.ServerErrorReceipt.newBuilder()
                    .setStanzaId(messageId)
                    .build();
                byte[] plaintext = receipt.toByteArray();
                byte[] iv = Bytes.random(12);
                byte[] ciphertext = AesGcm.encrypt(
                    retryKey, iv, plaintext, messageId.getBytes(StandardCharsets.UTF_8));

                Map<String, String> rmrAttrs = new LinkedHashMap<>();
                rmrAttrs.put("jid", chatJid);
                rmrAttrs.put("from_me", String.valueOf(isFromMe));
                if (isGroup && senderJid != null && !senderJid.isBlank()) {
                    rmrAttrs.put("participant", senderJid);
                }

                List<BinaryNode> encryptChildren = List.of(
                    new BinaryNode("enc_p", Map.of(), ciphertext),
                    new BinaryNode("enc_iv", Map.of(), iv)
                );

                BinaryNode encryptNode = new BinaryNode("encrypt", Map.of(), encryptChildren);
                BinaryNode rmrNode = new BinaryNode("rmr", rmrAttrs, null);

                Map<String, String> receiptAttrs = new LinkedHashMap<>();
                receiptAttrs.put("id", messageId);
                receiptAttrs.put("to", ownJid);
                receiptAttrs.put("type", "server-error");

                BinaryNode receiptStanza = new BinaryNode("receipt", receiptAttrs, List.of(encryptNode, rmrNode));
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

    public CompletableFuture<ProfilePictureInfo> getProfilePictureInfo(String targetJid, boolean preview) {
        String iqId = newIqId();
        String type = preview ? "preview" : "image";
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", targetJid, "type", "get", "xmlns", "w:profile:picture", "id", iqId),
            List.of(new BinaryNode("picture", Map.of("type", type, "query", "url"), null)));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode pic = resp.child("picture");
            if (pic == null) return null;
            String id = pic.attr("id");
            String url = pic.attr("url");
            String picType = pic.attr("type");
            String directPath = pic.attr("direct_path");
            return new ProfilePictureInfo(id, url, picType, directPath);
        });
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
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq", Map.of(
            "id", iqId,
            "to", Jid.SERVER_WHATSAPP,
            "type", "get",
            "xmlns", "privacy"
        ), List.of(new BinaryNode("privacy", Map.of(), null)));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode privacy = resp.child("privacy");
            Map<String, String> map = new LinkedHashMap<>();
            if (privacy != null) {
                for (BinaryNode child : privacy.childrenList()) {
                    if ("category".equals(child.tag())) {
                        String name = child.attr("name");
                        String val = child.attr("value");
                        if (name != null && val != null) map.put(name, val);
                    }
                }
            }
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
        String iqId = newIqId();
        BinaryNode node = new BinaryNode("disappearing_mode", Map.of(
            "duration", String.valueOf(seconds)
        ), null);
        BinaryNode iq = new BinaryNode("iq", Map.of(
            "id", iqId,
            "to", Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "disappearing_mode"
        ), List.of(node));
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("Failed to set default disappearing timer: " + resp);
            }
        });
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
