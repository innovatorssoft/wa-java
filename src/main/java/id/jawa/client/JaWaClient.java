package id.jawa.client;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;

import com.google.protobuf.ByteString;
import id.jawa.protocol.codec.BinaryDecoder;
import id.jawa.protocol.codec.BinaryEncoder;
import id.jawa.protocol.codec.BinaryNode;
import id.jawa.protocol.connection.FrameSocket;
import id.jawa.protocol.connection.NoiseHandshake;
import id.jawa.protocol.connection.NoiseTransport;
import id.jawa.feature.pairing.ClientPayloadBuilder;
import id.jawa.feature.pairing.PairingCodeHandler;
import id.jawa.feature.pairing.PairingHandler;
import id.jawa.proto.Wa;
import id.jawa.feature.messaging.MessageEncoder;
import id.jawa.feature.messaging.MessageSender;
import id.jawa.domain.store.InMemorySignalKeyStore;
import id.jawa.feature.signal.JaWaProtocolStore;
import id.jawa.feature.signal.PreKeyBundleFetcher;
import id.jawa.feature.signal.PreKeyManager;
import id.jawa.feature.signal.SessionBootstrap;
import id.jawa.domain.store.SignalKeyStore;
import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.store.AuthStore;
import id.jawa.protocol.crypto.Bytes;
import id.jawa.protocol.crypto.Curve25519;
import id.jawa.protocol.crypto.KeyPair25519;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JaWaClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JaWaClient.class);

    public static final java.util.concurrent.ConcurrentHashMap<String, String> LID_TO_PN_MAP =
        new java.util.concurrent.ConcurrentHashMap<>();

    public enum TerminationReason {

        REVOKED,

        BANNED,

        REPLACED,

        VERSION_OBSOLETE,

        TRANSIENT,

        UNKNOWN
    }

    public interface Listener {

        default void onQr(List<String> qrStrings) {}

        default void onPaired(String jid, String pushName, String platform) {}

        default void onConnected() {}

        default void onMessage(id.jawa.feature.messaging.MessageReceiver.Decoded decoded) {}

        default void onReceipt(id.jawa.domain.model.Receipt receipt) {}

        default void onAppStateKeysReceived(int count) {}

        default void onAppStateMutations(id.jawa.feature.appstate.PatchName name,
                                         java.util.List<id.jawa.feature.appstate.AppStateProcessor.Mutation> mutations) {}

        default void onTerminated(TerminationReason reason, String detail, boolean permanent) {}

        default void onAck(BinaryNode ack) {}
        default void onStanza(BinaryNode node) {}
        default void onError(Throwable t) {}
    }

    private final AuthStore store;
    private SignalKeyStore signalStore = new InMemorySignalKeyStore();
    private id.jawa.domain.store.InMemorySenderKeyStore senderKeyStore = new id.jawa.domain.store.InMemorySenderKeyStore();
    private final java.nio.file.Path signalDir;
    private id.jawa.domain.store.FileSessionStorage sessionStorage;
    private id.jawa.domain.store.FilePreKeyStorage preKeyStorage;
    private id.jawa.domain.store.FileSenderKeyStorage senderKeyStorage;
    private id.jawa.feature.appstate.FileAppStateKeyStorage appStateKeyStorage;
    private id.jawa.feature.appstate.AppStateProcessor appStateProcessor;
    private JaWaProtocolStore protocolStore;
    private PairingCodeHandler pairCodeHandler;
    private java.util.concurrent.ScheduledExecutorService keepalive;
    private Listener listener = new Listener() {};
    private FrameSocket frame;
    private NoiseHandshake noise;
    private NoiseTransport transport;
    private AuthCreds creds;
    private Thread readerThread;
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private final AtomicBoolean reconnectAfterPair = new AtomicBoolean(false);

    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger reconnectAttempts =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private boolean autoReconnect = true;
    private boolean autoReadReceipt = false;
    private boolean autoComposingPresence = false;
    private boolean autoPreKeyUpload = true;
    private boolean autoEphemeral = true;
    private boolean autoIgnoreOfflineMessages = true;
    private int offlineMessageGracePeriodSec = 2;
    private volatile long connectedAtEpochSec = 0;
    private final java.util.concurrent.ConcurrentHashMap<String, Integer>
        chatEphemeralTimers = new java.util.concurrent.ConcurrentHashMap<>();
    private String wsUrl = WaConstants.WS_URL;

    private static final long[] RECONNECT_BACKOFF_MS = {2_000, 4_000, 8_000, 16_000, 30_000, 60_000};

    private final java.util.concurrent.CountDownLatch closeLatch = new java.util.concurrent.CountDownLatch(1);
    private static final int PRE_KEY_UPLOAD_COUNT = 30;

    private final MessagingService messagingService = new MessagingService(this);
    private final GroupService groupService = new GroupService(this);
    private final MediaService mediaService = new MediaService(this);
    private final NewsletterService newsletterService = new NewsletterService(this);
    private final ProfileService profileService = new ProfileService(this);
    private final ChatService chatService = new ChatService(this);
    private final PrivacyService privacyService = new PrivacyService(this);

    private final java.util.concurrent.ConcurrentHashMap<String, id.jawa.feature.messaging.GroupListQuery.GroupInfo>
        groupCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.List<id.jawa.domain.model.DeviceInfo>>
        deviceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String>
        groupPhashMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock messageSendLock =
        new java.util.concurrent.locks.ReentrantLock();

    public JaWaClient(AuthStore store) {
        this(store, null);
    }

    public JaWaClient(AuthStore store, java.nio.file.Path signalDir) {
        this.store = store;
        this.signalDir = signalDir;
    }

    public JaWaClient listener(Listener l) { this.listener = l != null ? l : new Listener() {}; return this; }

    public JaWaClient autoReconnect(boolean enabled) {
        this.autoReconnect = enabled;
        return this;
    }

    public JaWaClient autoReadReceipt(boolean enabled) {
        this.autoReadReceipt = enabled;
        return this;
    }

    public JaWaClient autoComposingPresence(boolean enabled) {
        this.autoComposingPresence = enabled;
        return this;
    }

    public JaWaClient autoPreKeyUpload(boolean enabled) {
        this.autoPreKeyUpload = enabled;
        return this;
    }

    public boolean autoReconnect() { return autoReconnect; }
    public boolean autoReadReceipt() { return autoReadReceipt; }
    public boolean autoComposingPresence() { return autoComposingPresence; }
    public boolean autoPreKeyUpload() { return autoPreKeyUpload; }
    public boolean autoEphemeral() { return autoEphemeral; }
    public boolean isAutoEphemeral() { return autoEphemeral; }

    public JaWaClient autoEphemeral(boolean enabled) {
        this.autoEphemeral = enabled;
        return this;
    }

    public boolean autoIgnoreOfflineMessages() { return autoIgnoreOfflineMessages; }
    public boolean isAutoIgnoreOfflineMessages() { return autoIgnoreOfflineMessages; }

    public JaWaClient autoIgnoreOfflineMessages(boolean enabled) {
        this.autoIgnoreOfflineMessages = enabled;
        return this;
    }

    public int offlineMessageGracePeriodSec() { return offlineMessageGracePeriodSec; }

    public JaWaClient offlineMessageGracePeriodSec(int seconds) {
        this.offlineMessageGracePeriodSec = Math.max(0, seconds);
        return this;
    }

    public long getConnectedAtEpochSec() { return connectedAtEpochSec; }

    public java.util.Map<String, Integer> getChatEphemeralTimers() {
        return chatEphemeralTimers;
    }

    public void setChatEphemeralTimer(String chatJid, int timerSeconds) {
        if (chatJid == null) return;
        if (timerSeconds > 0) {
            chatEphemeralTimers.put(chatJid, timerSeconds);
        } else {
            chatEphemeralTimers.remove(chatJid);
        }
    }

    public int getChatEphemeralTimer(String chatJid) {
        if (chatJid == null) return 0;
        return chatEphemeralTimers.getOrDefault(chatJid, 0);
    }

    public id.jawa.proto.Wa.Message wrapEphemeralIfNeeded(String chatJid, id.jawa.proto.Wa.Message msg) {
        if (!autoEphemeral || msg == null || chatJid == null) {
            return msg;
        }
        if (msg.hasEphemeralMessage() || msg.hasProtocolMessage() || msg.hasReactionMessage()) {
            return msg;
        }
        String key = chatJid;
        id.jawa.domain.model.Jid parsed = id.jawa.domain.model.Jid.parse(chatJid);
        if (parsed != null && !chatJid.endsWith("@g.us")) {
            key = parsed.user() + "@" + parsed.server();
        }
        Integer timer = chatEphemeralTimers.get(key);
        if (timer == null && !key.equals(chatJid)) {
            timer = chatEphemeralTimers.get(chatJid);
        }
        if (timer != null && timer > 0) {
            return id.jawa.proto.Wa.Message.newBuilder()
                .setEphemeralMessage(id.jawa.proto.Wa.Message.FutureProofMessage.newBuilder()
                    .setMessage(msg))
                .build();
        }
        return msg;
    }

    public JaWaClient wsUrl(String url) {
        this.wsUrl = (url == null || url.isBlank()) ? WaConstants.WS_URL : url;
        return this;
    }
    public String wsUrl() { return wsUrl; }

    public MessagingService messages() { return messagingService; }
    public GroupService groups() { return groupService; }
    public MediaService media() { return mediaService; }
    public NewsletterService newsletters() { return newsletterService; }
    public ProfileService profile() { return profileService; }
    public ChatService chats() { return chatService; }
    public PrivacyService privacy() { return privacyService; }

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
        if (store instanceof id.jawa.domain.store.SqliteAuthStore sqliteStore && sessionStorage == null) {
            sessionStorage     = new id.jawa.domain.store.FileSessionStorage(sqliteStore);
            preKeyStorage      = new id.jawa.domain.store.FilePreKeyStorage(sqliteStore);
            senderKeyStorage   = new id.jawa.domain.store.FileSenderKeyStorage(sqliteStore);
            appStateKeyStorage = new id.jawa.feature.appstate.FileAppStateKeyStorage(sqliteStore);
            signalStore        = new InMemorySignalKeyStore(preKeyStorage);
            senderKeyStore     = new id.jawa.domain.store.InMemorySenderKeyStore(senderKeyStorage);
            appStateProcessor  = new id.jawa.feature.appstate.AppStateProcessor(appStateKeyStorage);
        } else if (signalDir != null && sessionStorage == null) {
            sessionStorage     = new id.jawa.domain.store.FileSessionStorage(signalDir.resolve("sessions"));
            preKeyStorage      = new id.jawa.domain.store.FilePreKeyStorage(signalDir.resolve("prekeys"));
            senderKeyStorage   = new id.jawa.domain.store.FileSenderKeyStorage(signalDir.resolve("sender-keys"));
            appStateKeyStorage = new id.jawa.feature.appstate.FileAppStateKeyStorage(signalDir.resolve("appstate-keys"));
            signalStore        = new InMemorySignalKeyStore(preKeyStorage);
            senderKeyStore     = new id.jawa.domain.store.InMemorySenderKeyStore(senderKeyStorage);
            appStateProcessor  = new id.jawa.feature.appstate.AppStateProcessor(appStateKeyStorage);
        }
        protocolStore = new JaWaProtocolStore(creds, sessionStorage);

        if (preKeyStorage != null) {

            preKeyStorage.pruneKeepHighest(600);
            for (var entry : preKeyStorage.snapshot().entrySet()) {
                try {
                    byte[] prefixedPub = Curve25519.prependType(entry.getValue().publicKey());
                    org.whispersystems.libsignal.ecc.ECKeyPair kp =
                        new org.whispersystems.libsignal.ecc.ECKeyPair(
                            org.whispersystems.libsignal.ecc.Curve.decodePoint(prefixedPub, 0),
                            org.whispersystems.libsignal.ecc.Curve.decodePrivatePoint(entry.getValue().privateKey()));
                    protocolStore.storePreKey(entry.getKey(),
                        new org.whispersystems.libsignal.state.PreKeyRecord(entry.getKey(), kp));
                } catch (org.whispersystems.libsignal.InvalidKeyException e) {
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
        keepalive = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
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
                        java.util.Map.of(
                            "to",    id.jawa.domain.model.Jid.SERVER_WHATSAPP,
                            "type",  "get",
                            "xmlns", "w:p",
                            "id",    iqId
                        ),
                        java.util.List.of(new BinaryNode("ping", java.util.Map.of(), null)));
                    sendIq(ping, resp -> LOG.trace("keepalive ack id={}", iqId));
                } catch (Throwable t) {
                    LOG.warn("Keepalive send failed", t);
                }

                long delay = 20 + java.util.concurrent.ThreadLocalRandom.current().nextInt(10);
                keepalive.schedule(this, delay, java.util.concurrent.TimeUnit.SECONDS);
            }
        };
        keepalive.schedule(tick, 20, java.util.concurrent.TimeUnit.SECONDS);
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

    private void captureAppStateKeysFrom(id.jawa.feature.messaging.MessageReceiver.Decoded decoded) {
        if (appStateKeyStorage == null || decoded.message() == null) return;
        if (!decoded.message().hasProtocolMessage()) return;
        var pm = decoded.message().getProtocolMessage();
        if (!pm.hasAppStateSyncKeyShare()) return;
        int count = 0;
        for (id.jawa.proto.Wa.Message.AppStateSyncKey k : pm.getAppStateSyncKeyShare().getKeysList()) {
            byte[] id = k.getKeyId().getKeyId().toByteArray();
            byte[] data = k.getKeyData().getKeyData().toByteArray();
            if (data.length != 32) continue;
            appStateKeyStorage.put(new id.jawa.feature.appstate.AppStateKey(id, data));
            count++;
        }
        if (count > 0) {
            LOG.info("Persisted {} app-state key(s) shared by {}", count, decoded.senderJid());
            try { listener.onAppStateKeysReceived(count); }
            catch (Throwable t) { LOG.warn("listener.onAppStateKeysReceived threw", t); }
        }
    }

    public java.util.concurrent.CompletableFuture<java.util.List<id.jawa.feature.appstate.AppStateProcessor.Mutation>>
            requestAppStateSync(id.jawa.feature.appstate.PatchName name, long fromVersion) {
        if (appStateProcessor == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("signalDir not set — app-state sync requires persistent storage"));
        }
        String iqId = newIqId();
        BinaryNode iq = fromVersion < 0
            ? id.jawa.feature.appstate.AppStateSyncQuery.buildSnapshotRequest(iqId, name)
            : id.jawa.feature.appstate.AppStateSyncQuery.buildPatchRequest(iqId, name, fromVersion);
        LOG.debug("Sent app-state sync IQ id={} name={} from={}", iqId, name.wire, fromVersion);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("App-state sync rejected: {}", resp);
                return java.util.List.<id.jawa.feature.appstate.AppStateProcessor.Mutation>of();
            }
            id.jawa.feature.appstate.AppStateSyncQuery.PatchList list =
                id.jawa.feature.appstate.AppStateSyncQuery.parseResponse(resp);
            if (list == null) return java.util.List.<id.jawa.feature.appstate.AppStateProcessor.Mutation>of();

            java.util.List<id.jawa.feature.appstate.AppStateProcessor.Mutation> all = new java.util.ArrayList<>();
            if (list.snapshot() != null) {
                all.addAll(appStateProcessor.decodeSnapshot(list.snapshot(), false));
            }
            for (id.jawa.proto.Wa.SyncdPatch p : list.patches()) {
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

    private final java.util.Map<String, java.util.function.Consumer<BinaryNode>> pendingIqResults
        = new java.util.concurrent.ConcurrentHashMap<>();

    private String newIqId() {
        return Bytes.toHex(Bytes.random(8));
    }

    public String generateMessageId() {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(8 + 64 + 16);
        bb.putLong(System.currentTimeMillis() / 1000L);
        if (creds != null && creds.meJid != null) {
            id.jawa.domain.model.Jid j = id.jawa.domain.model.Jid.parse(creds.meJid);
            if (j != null && j.user() != null) {
                bb.put((j.user() + "@c.us").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        bb.put(Bytes.random(16));
        bb.flip();
        byte[] input = new byte[bb.remaining()];
        bb.get(input);
        byte[] hash = Sha256.hash(input);
        byte[] prefix = java.util.Arrays.copyOfRange(hash, 0, 9);
        return "3EB0" + Bytes.toHex(prefix).toUpperCase();
    }

    public String sendIq(BinaryNode iq, java.util.function.Consumer<BinaryNode> onResponse) {
        String id = iq.attr("id");
        if (id == null) throw new IllegalArgumentException("iq has no id attribute");
        if (onResponse != null) pendingIqResults.put(id, onResponse);
        send(iq);
        return id;
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> sendIqAsync(BinaryNode iq) {
        var f = new java.util.concurrent.CompletableFuture<BinaryNode>();
        sendIq(iq, f::complete);
        return f;
    }

    public java.util.concurrent.CompletableFuture<String> requestPairingCode(String phoneNumber, String customCode) {
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

    public java.util.concurrent.CompletableFuture<String> sendMessage(String chatJid, id.jawa.proto.Wa.Message msg) {
        if (chatJid.endsWith("@newsletter")) {
            return sendNewsletterMessage(chatJid, msg);
        }
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendMessage(
            id.jawa.feature.messaging.MessageReceiver.Decoded target, id.jawa.proto.Wa.Message msg) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        if (!chatJid.endsWith("@g.us")) {
            id.jawa.domain.model.Jid j = id.jawa.domain.model.Jid.parse(chatJid);
            if (j != null) {
                chatJid = j.user() + "@" + j.server();
            }
        }
        return sendMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendText(String chatJid, String text) {
        return sendMessage(chatJid, MessageEncoder.text(text));
    }

    public java.util.concurrent.CompletableFuture<String> sendText(
            id.jawa.feature.messaging.MessageReceiver.Decoded target, String text) {
        return sendMessage(target, MessageEncoder.text(text));
    }

    public java.util.concurrent.CompletableFuture<String> sendTextWithMentions(
            String chatJid, String text, java.util.List<String> mentionedBareJids) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.textWithMentions(text, mentionedBareJids);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendPoll(
            String chatJid, String name, java.util.List<String> options, int selectableCount) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.pollMessage(name, options, selectableCount);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendLocation(
            String chatJid, double latitude, double longitude, String name, String address) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.locationMessage(latitude, longitude, name, address);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendContact(
            String chatJid, String displayName, String vcard) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.contactMessage(displayName, vcard);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendStatusText(
            String text, int backgroundArgb, int fontType) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.statusTextMessage(text, backgroundArgb, fontType);
        return sendDmMessage("status@broadcast", msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendStatusMedia(id.jawa.proto.Wa.Message mediaMsg) {
        return sendDmMessage("status@broadcast", mediaMsg);
    }

    public java.util.concurrent.CompletableFuture<String> sendNewsletterText(
            String newsletterJid, String text) {
        String msgId = generateMessageId();
        byte[] msgBytes = MessageEncoder.encode(MessageEncoder.text(text));
        BinaryNode node = new BinaryNode("message",
            java.util.Map.of(
                "to", newsletterJid,
                "type", "text",
                "id", msgId
            ),
            java.util.List.of(new BinaryNode("plaintext", java.util.Map.of(), msgBytes)));
        send(node);
        return java.util.concurrent.CompletableFuture.completedFuture(msgId);
    }

    public java.util.concurrent.CompletableFuture<String> sendNewsletterReaction(
            String newsletterJid, String serverId, String reactionCode) {
        String msgId = generateMessageId();
        java.util.Map<String, String> attrs = new java.util.HashMap<>();
        attrs.put("to", newsletterJid);
        attrs.put("type", "reaction");
        attrs.put("server_id", serverId);
        attrs.put("id", msgId);
        if (reactionCode == null || reactionCode.isBlank()) {
            attrs.put("edit", "7");
        }
        BinaryNode reactNode = new BinaryNode("reaction",
            reactionCode != null && !reactionCode.isBlank() ? java.util.Map.of("code", reactionCode) : java.util.Map.of(),
            null);
        BinaryNode msgNode = new BinaryNode("message", attrs, java.util.List.of(reactNode));
        send(msgNode);
        return java.util.concurrent.CompletableFuture.completedFuture(msgId);
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> fetchNewsletterMessages(
            String newsletterJid, int count) {
        String iqId = newIqId();
        BinaryNode node = new BinaryNode("iq",
            java.util.Map.of(
                "id", iqId,
                "type", "get",
                "xmlns", "newsletter",
                "to", newsletterJid
            ),
            java.util.List.of(new BinaryNode("message_updates", java.util.Map.of("count", String.valueOf(count)), null)));
        return sendIqAsync(node);
    }

    public byte[] downloadMedia(id.jawa.proto.Wa.Message msg) {
        return id.jawa.feature.media.MediaDownloader.download(msg);
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> updateProfileStatus(String statusText) {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "status", "id", iqId),
            java.util.List.of(new BinaryNode("status", java.util.Map.of(), statusText.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return sendIqAsync(iq);
    }

    public java.util.concurrent.CompletableFuture<String> fetchProfilePictureUrl(String targetJid) {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", targetJid, "type", "get", "xmlns", "w:profile:picture", "id", iqId),
            java.util.List.of(new BinaryNode("picture", java.util.Map.of("type", "preview"), null)));
        return sendIqAsync(iq).thenApply(resp -> {
            var picNode = resp.child("picture");
            return picNode != null ? picNode.attr("url") : null;
        });
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> updateGroupPicture(
            String groupJid, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return removeGroupPicture(groupJid);
        }
        String iqId = newIqId();
        BinaryNode picNode = new BinaryNode("picture",
            java.util.Map.of("type", "image"),
            imageBytes);
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", groupJid, "type", "set", "xmlns", "w:profile:picture", "id", iqId),
            java.util.List.of(picNode));
        return sendIqAsync(iq);
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> removeGroupPicture(String groupJid) {
        String iqId = newIqId();
        BinaryNode picNode = new BinaryNode("picture",
            java.util.Map.of("type", "delete"),
            null);
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", groupJid, "type", "set", "xmlns", "w:profile:picture", "id", iqId),
            java.util.List.of(picNode));
        return sendIqAsync(iq);
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> updateProfilePicture(byte[] imageBytes) {
        id.jawa.domain.model.Jid myJid = id.jawa.domain.model.Jid.parse(creds.meJid);
        String ownBareJid = myJid != null ? myJid.user() + "@s.whatsapp.net" : creds.meJid;
        return updateGroupPicture(ownBareJid, imageBytes);
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> removeProfilePicture() {
        id.jawa.domain.model.Jid myJid = id.jawa.domain.model.Jid.parse(creds.meJid);
        String ownBareJid = myJid != null ? myJid.user() + "@s.whatsapp.net" : creds.meJid;
        return removeGroupPicture(ownBareJid);
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> updateBlockStatus(String targetJid, boolean block) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode("item", java.util.Map.of("action", block ? "block" : "unblock", "jid", targetJid), null);
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "blocklist", "id", iqId),
            java.util.List.of(item));
        return sendIqAsync(iq);
    }

    public java.util.concurrent.CompletableFuture<java.util.List<String>> fetchBlockList() {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "blocklist", "id", iqId),
            null);
        return sendIqAsync(iq).thenApply(resp -> {
            var listNode = resp.child("list");
            if (listNode == null) return java.util.List.of();
            java.util.List<String> jids = new java.util.ArrayList<>();
            for (var item : listNode.childrenList()) {
                if ("item".equals(item.tag()) && item.attr("jid") != null) {
                    jids.add(item.attr("jid"));
                }
            }
            return jids;
        });
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> pinChat(String chatJid, boolean pin) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode("pin", java.util.Map.of("jid", chatJid, "action", pin ? "pin" : "unpin"), null);
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "action", "id", iqId),
            java.util.List.of(item));
        return sendIqAsync(iq);
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> archiveChat(String chatJid, boolean archive) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode("archive", java.util.Map.of("jid", chatJid, "action", archive ? "archive" : "unarchive"), null);
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "action", "id", iqId),
            java.util.List.of(item));
        return sendIqAsync(iq);
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> muteChat(String chatJid, long durationSeconds) {
        String iqId = newIqId();
        java.util.Map<String, String> attrs = new java.util.HashMap<>();
        attrs.put("jid", chatJid);
        if (durationSeconds > 0) {
            attrs.put("type", "set");
            attrs.put("time", String.valueOf(durationSeconds));
        } else {
            attrs.put("type", "delete");
        }
        BinaryNode item = new BinaryNode("mute", attrs, null);
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "action", "id", iqId),
            java.util.List.of(item));
        return sendIqAsync(iq);
    }

    public java.util.concurrent.CompletableFuture<String>
            sendDmMessage(String toUser, id.jawa.proto.Wa.Message msg) {
        final id.jawa.proto.Wa.Message outboundMsg = wrapEphemeralIfNeeded(toUser, msg);
        String ownBareJid;
        if (toUser != null && toUser.endsWith("@lid") && creds.meLid != null && !creds.meLid.isBlank()) {
            id.jawa.domain.model.Jid lidJid = id.jawa.domain.model.Jid.parse(creds.meLid);
            ownBareJid = lidJid != null ? lidJid.user() + "@lid" : creds.meLid;
        } else {
            id.jawa.domain.model.Jid myJid = id.jawa.domain.model.Jid.parse(creds.meJid);
            if (myJid == null) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("creds.meJid invalid"));
            }
            ownBareJid = myJid.user() + "@" + id.jawa.domain.model.Jid.SERVER_WHATSAPP;
        }
        boolean isSelfSend = toUser != null && toUser.equals(ownBareJid);
        java.util.List<String> queryTargets = isSelfSend
            ? java.util.List.of(toUser)
            : java.util.List.of(toUser, ownBareJid);

        id.jawa.domain.model.Jid ownJidObj = id.jawa.domain.model.Jid.parse(ownBareJid);
        String ownUser = ownJidObj != null ? ownJidObj.user() : "";
        id.jawa.domain.model.Jid targetJidObj = id.jawa.domain.model.Jid.parse(toUser);

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

            id.jawa.domain.model.Jid pnBase = id.jawa.domain.model.Jid.parse(finalRoutingJid);
            id.jawa.domain.model.Jid lidBase = finalEncryptionLid != null ? id.jawa.domain.model.Jid.parse(finalEncryptionLid) : null;

            java.util.List<String> allDeviceJids = new java.util.ArrayList<>();
            java.util.List<String> encryptionDeviceJids = new java.util.ArrayList<>();

            for (var entry : devicesMap.entrySet()) {
                id.jawa.domain.model.Jid base = id.jawa.domain.model.Jid.parse(entry.getKey());
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

    private static int countOwnDevices(java.util.List<String> deviceJids, String ownUser) {
        int n = 0;
        for (String dj : deviceJids) {
            id.jawa.domain.model.Jid j = id.jawa.domain.model.Jid.parse(dj);
            if (j != null && ownUser.equals(j.user())) n++;
        }
        return n;
    }

    public java.util.concurrent.CompletableFuture<java.util.List<org.whispersystems.libsignal.SignalProtocolAddress>>
            fetchBundlesAndInstallSessions(java.util.List<String> deviceJids) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String dj : deviceJids) {
            id.jawa.domain.model.Jid j = id.jawa.domain.model.Jid.parse(dj);
            if (j == null) continue;
            org.whispersystems.libsignal.SignalProtocolAddress addr = id.jawa.feature.signal.SessionBootstrap.addressFor(j);
            if (!protocolStore.containsSession(addr)) {
                missing.add(dj);
            }
        }
        if (missing.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
        }
        if (missing.size() <= 40) {
            String iqId = newIqId();
            BinaryNode q = PreKeyBundleFetcher.buildFetchStanza(iqId, missing);
            LOG.debug("Sent pre-key fetch IQ id={} for {} devices", iqId, missing.size());
            return sendIqAsync(q).thenApply(resp -> {
                if ("error".equals(resp.attr("type"))) {
                    LOG.warn("Pre-key fetch error: {}", resp);
                    return java.util.List.of();
                }
                var bundles = PreKeyBundleFetcher.parseResponse(resp);
                LOG.debug("Parsed {} bundles", bundles.size());
                return SessionBootstrap.installAll(protocolStore, bundles);
            });
        }
        int batchSize = 40;
        java.util.List<java.util.concurrent.CompletableFuture<java.util.List<org.whispersystems.libsignal.SignalProtocolAddress>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < missing.size(); i += batchSize) {
            java.util.List<String> chunk = missing.subList(i, Math.min(i + batchSize, missing.size()));
            String iqId = newIqId();
            BinaryNode q = PreKeyBundleFetcher.buildFetchStanza(iqId, chunk);
            futures.add(sendIqAsync(q).thenApply(resp -> {
                if ("error".equals(resp.attr("type"))) {
                    LOG.warn("Pre-key fetch error: {}", resp);
                    return java.util.List.of();
                }
                var bundles = PreKeyBundleFetcher.parseResponse(resp);
                return SessionBootstrap.installAll(protocolStore, bundles);
            }));
        }
        return java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture<?>[0]))
            .thenApply(v -> {
                java.util.List<org.whispersystems.libsignal.SignalProtocolAddress> result = new java.util.ArrayList<>();
                for (var f : futures) {
                    result.addAll(f.join());
                }
                return result;
            });
    }

    public java.util.concurrent.CompletableFuture<Void> setDisappearingTimer(String chatJid, int timerSeconds) {
        if (chatJid == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        setChatEphemeralTimer(chatJid, timerSeconds);
        if (chatJid.endsWith("@g.us")) {
            String iqId = newIqId();
            BinaryNode content = timerSeconds <= 0
                ? new BinaryNode("not_ephemeral", java.util.Map.of(), null)
                : new BinaryNode("ephemeral", java.util.Map.of("expiration", String.valueOf(timerSeconds)), null);
            BinaryNode iq = new BinaryNode("iq",
                java.util.Map.of("to", chatJid, "type", "set", "xmlns", "w:g2", "id", iqId),
                java.util.List.of(content));
            return sendIqAsync(iq).thenApply(res -> null);
        } else {
            id.jawa.proto.Wa.Message msg = id.jawa.proto.Wa.Message.newBuilder()
                .setProtocolMessage(id.jawa.proto.Wa.Message.ProtocolMessage.newBuilder()
                    .setType(id.jawa.proto.Wa.Message.ProtocolMessage.Type.EPHEMERAL_SETTING)
                    .setEphemeralExpiration(timerSeconds)
                    .setEphemeralSettingTimestamp(System.currentTimeMillis() / 1000L))
                .build();
            return sendMessage(chatJid, msg).thenApply(res -> null);
        }
    }

    public java.util.concurrent.CompletableFuture<String>
            sendGroupText(String groupJid, String text) {
        return sendGroupMessage(groupJid, MessageEncoder.text(text));
    }

    public java.util.concurrent.CompletableFuture<String>
            sendGroupMessage(String groupJid, id.jawa.proto.Wa.Message msg) {
        final id.jawa.proto.Wa.Message outboundMsg = wrapEphemeralIfNeeded(groupJid, msg);
        id.jawa.feature.messaging.GroupListQuery.GroupInfo cached = groupCache.get(groupJid);
        java.util.concurrent.CompletableFuture<id.jawa.feature.messaging.GroupListQuery.GroupInfo> targetFuture =
            cached != null
                ? java.util.concurrent.CompletableFuture.completedFuture(cached)
                : queryGroupInfo(groupJid);

        return targetFuture.thenCompose(target -> {
            if (target == null) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("not a member of group: " + groupJid));
            }
            java.util.LinkedHashSet<String> bareUsers = new java.util.LinkedHashSet<>();
            for (String pj : target.participantJids()) {
                id.jawa.domain.model.Jid j = id.jawa.domain.model.Jid.parse(pj);
                if (j == null) continue;
                bareUsers.add(j.user() + "@" + j.server());
            }
            if (bareUsers.isEmpty()) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("group has no participants"));
            }
            return queryDevices(bareUsers).thenCompose(devicesMap -> {
                java.util.List<String> allDeviceJids = new java.util.ArrayList<>();
                for (var entry : devicesMap.entrySet()) {
                    id.jawa.domain.model.Jid base = id.jawa.domain.model.Jid.parse(entry.getKey());
                    if (base == null) continue;
                    for (var d : entry.getValue()) {
                        allDeviceJids.add(d.asJid(base.user(), base.server()).asString());
                    }
                }
                return fetchBundlesAndInstallSessions(allDeviceJids).thenApply(addresses -> {
                    String msgId = generateMessageId();
                    messageSendLock.lock();
                    try {
                        id.jawa.feature.messaging.GroupSender.Result result =
                            id.jawa.feature.messaging.GroupSender.buildStanza(
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

    public java.util.concurrent.CompletableFuture<String> sendNewsletterMessage(
            String newsletterJid, id.jawa.proto.Wa.Message msg) {
        String msgId = generateMessageId();

        String msgType = "text";
        if (msg.hasImageMessage()) msgType = "image";
        else if (msg.hasVideoMessage()) msgType = "video";
        else if (msg.hasAudioMessage()) msgType = "audio";
        else if (msg.hasDocumentMessage()) msgType = "document";

        BinaryNode plaintextNode = new BinaryNode("plaintext", java.util.Map.of(), msg.toByteArray());

        BinaryNode stanza = new BinaryNode("message", java.util.Map.of(
            "to", newsletterJid,
            "id", msgId,
            "type", msgType
        ), java.util.List.of(plaintextNode));

        send(stanza);
        LOG.info("Sent newsletter message id={} to={} type={}", msgId, newsletterJid, msgType);
        return java.util.concurrent.CompletableFuture.completedFuture(msgId);
    }

    public java.util.concurrent.CompletableFuture<String> sendReaction(
            String chatJid,
            String targetMsgId,
            String targetParticipant,
            boolean fromMe,
            String emoji) {
        long ts = System.currentTimeMillis();
        id.jawa.proto.Wa.Message msg = MessageEncoder.reaction(
            chatJid, targetMsgId, targetParticipant, fromMe, emoji, ts);
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendReaction(
            id.jawa.feature.messaging.MessageReceiver.Decoded target, String emoji) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        if (!chatJid.endsWith("@g.us")) {
            id.jawa.domain.model.Jid j = id.jawa.domain.model.Jid.parse(chatJid);
            if (j != null) chatJid = j.user() + "@" + j.server();
        }
        id.jawa.domain.model.Jid myJid = id.jawa.domain.model.Jid.parse(creds.meJid);
        id.jawa.domain.model.Jid senderJid = id.jawa.domain.model.Jid.parse(target.senderJid());
        boolean fromMe = myJid != null && senderJid != null && myJid.user().equals(senderJid.user());
        String targetParticipant = target.groupJid() != null ? target.senderJid() : null;
        return sendReaction(chatJid, target.msgId(), targetParticipant, fromMe, emoji);
    }

    public java.util.concurrent.CompletableFuture<String> sendReply(
            String chatJid,
            String text,
            String quotedMsgId,
            String quotedSender,
            String quotedText) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.reply(
            text, quotedMsgId, quotedSender, quotedText);
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendReply(
            id.jawa.feature.messaging.MessageReceiver.Decoded target, String text) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        if (!chatJid.endsWith("@g.us")) {
            id.jawa.domain.model.Jid j = id.jawa.domain.model.Jid.parse(chatJid);
            if (j != null) chatJid = j.user() + "@" + j.server();
        }
        String quotedSender = target.groupJid() != null ? target.senderJid() : null;
        String quotedText = target.text() != null ? target.text() : "";
        return sendReply(chatJid, text, target.msgId(), quotedSender, quotedText);
    }

    public java.util.concurrent.CompletableFuture<String> sendListMessage(
            String chatJid,
            String title,
            String body,
            String footer,
            String buttonText,
            java.util.List<MessageEncoder.ListSection> sections) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.listMessage(
            title, body, footer, buttonText, sections);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendCtaButtons(
            String chatJid,
            String body,
            String footer,
            java.util.List<MessageEncoder.CtaButton> buttons) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.interactiveCtaButtons(body, footer, buttons);
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
        java.util.List<MessageEncoder.CtaButton> buttons
    ) {}

    public java.util.concurrent.CompletableFuture<String> sendCarousel(
            String chatJid,
            String body,
            String footer,
            java.util.List<CarouselCardInput> inputs) {
        return sendCarousel(chatJid, body, footer, inputs, null);
    }

    public java.util.concurrent.CompletableFuture<String> sendCarousel(
            String chatJid,
            String body,
            String footer,
            java.util.List<CarouselCardInput> inputs,
            id.jawa.proto.Wa.ContextInfo quoted) {
        java.util.List<java.util.concurrent.CompletableFuture<MessageEncoder.CarouselCard>> futures =
            new java.util.ArrayList<>();
        for (CarouselCardInput in : inputs) {
            boolean isVideo = in.mimetype() != null && in.mimetype().startsWith("video/");
            id.jawa.feature.media.MediaCrypto.MediaType type = isVideo
                ? id.jawa.feature.media.MediaCrypto.MediaType.VIDEO
                : id.jawa.feature.media.MediaCrypto.MediaType.IMAGE;
            futures.add(encryptAndUpload(in.mediaBytes(), type).thenApply(u -> {
                if (isVideo) {
                    id.jawa.proto.Wa.Message inner = MessageEncoder.videoMessage(
                        u.upload().url(), u.upload().directPath(),
                        u.mediaKey(),
                        u.enc().fileSha256(), u.enc().fileEncSha256(),
                        in.mediaBytes().length, in.mimetype(),
                        in.caption(), 0, 0, 0);
                    return new MessageEncoder.CarouselCard(
                        in.title(), in.caption(), null, inner.getVideoMessage(),
                        in.footer(), in.buttons());
                }
                id.jawa.proto.Wa.Message inner = MessageEncoder.imageMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    in.mediaBytes().length, in.mimetype(), in.caption());
                return new MessageEncoder.CarouselCard(
                    in.title(), in.caption(), inner.getImageMessage(), null,
                    in.footer(), in.buttons());
            }));
        }
        java.util.concurrent.CompletableFuture<Void> all = java.util.concurrent.CompletableFuture
            .allOf(futures.toArray(new java.util.concurrent.CompletableFuture<?>[0]));
        return all.thenCompose(v -> {
            java.util.List<MessageEncoder.CarouselCard> cards = new java.util.ArrayList<>();
            for (var f : futures) cards.add(f.join());
            id.jawa.proto.Wa.Message msg = MessageEncoder.interactiveCarousel(body, footer, cards);
            if (quoted != null) {
                msg = msg.toBuilder()
                    .setInteractiveMessage(msg.getInteractiveMessage().toBuilder().setContextInfo(quoted))
                    .build();
            }
            return sendBuiltMessage(chatJid, msg);
        });
    }

    public java.util.concurrent.CompletableFuture<String> sendButtonsMessage(
            String chatJid,
            String body,
            String footer,
            java.util.List<MessageEncoder.QuickReplyButton> buttons) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.buttonsMessage(body, footer, buttons);
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendEdit(
            String chatJid,
            String targetMsgId,
            String newText) {
        id.jawa.proto.Wa.Message inner = MessageEncoder.text(newText);
        id.jawa.proto.Wa.Message msg = MessageEncoder.edit(
            chatJid, targetMsgId, inner, System.currentTimeMillis());
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendEdit(
            id.jawa.feature.messaging.MessageReceiver.Decoded target, String newText) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        if (!chatJid.endsWith("@g.us")) {
            id.jawa.domain.model.Jid j = id.jawa.domain.model.Jid.parse(chatJid);
            if (j != null) chatJid = j.user() + "@" + j.server();
        }
        return sendEdit(chatJid, target.msgId(), newText);
    }

    public java.util.concurrent.CompletableFuture<byte[]> downloadMedia(
            String url,
            String directPath,
            byte[] mediaKey,
            byte[] fileEncSha256,
            id.jawa.feature.media.MediaCrypto.MediaType type) {
        if (url != null && !url.isEmpty()) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return id.jawa.feature.media.MediaDownloader.downloadByUrl(url, mediaKey, fileEncSha256, type);
                } catch (java.io.IOException | InterruptedException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
        }
        if (directPath == null || directPath.isEmpty()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalArgumentException("both url and directPath are empty"));
        }
        return refreshMediaConn().thenCompose(mc ->
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return id.jawa.feature.media.MediaDownloader.downloadByDirectPath(
                        mc, directPath, mediaKey, fileEncSha256, type);
                } catch (java.io.IOException | InterruptedException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }));
    }

    private record MediaUpload(
        byte[] mediaKey,
        id.jawa.feature.media.MediaCrypto.EncryptedMedia enc,
        id.jawa.feature.media.MediaUploader.Result upload
    ) {}

    private java.util.concurrent.CompletableFuture<MediaUpload> encryptAndUpload(
            byte[] bytes,
            id.jawa.feature.media.MediaCrypto.MediaType type) {
        byte[] mediaKey = id.jawa.protocol.crypto.Bytes.random(32);
        id.jawa.feature.media.MediaCrypto.EncryptedMedia enc =
            id.jawa.feature.media.MediaCrypto.encrypt(bytes, mediaKey, type);
        return refreshMediaConn().thenCompose(mc -> {
            try {
                id.jawa.feature.media.MediaUploader.Result up =
                    id.jawa.feature.media.MediaUploader.upload(mc, enc, type);
                return java.util.concurrent.CompletableFuture.completedFuture(
                    new MediaUpload(mediaKey, enc, up));
            } catch (java.io.IOException | InterruptedException e) {
                return java.util.concurrent.CompletableFuture.failedFuture(e);
            }
        });
    }

    private java.util.concurrent.CompletableFuture<String> sendBuiltMessage(
            String chatJid, id.jawa.proto.Wa.Message msg) {
        if (chatJid.endsWith("@newsletter")) {
            return sendNewsletterMessage(chatJid, msg);
        }
        return chatJid.endsWith("@g.us")
            ? sendGroupMessage(chatJid, msg)
            : sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendImage(
            String chatJid,
            byte[] imageBytes,
            String mimetype,
            String caption) {
        return encryptAndUpload(imageBytes, id.jawa.feature.media.MediaCrypto.MediaType.IMAGE)
            .thenCompose(u -> sendBuiltMessage(chatJid,
                MessageEncoder.imageMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    imageBytes.length, mimetype, caption)));
    }

    public java.util.concurrent.CompletableFuture<String> sendImageViewOnce(
            String chatJid, byte[] imageBytes, String mimetype, String caption) {
        return encryptAndUpload(imageBytes, id.jawa.feature.media.MediaCrypto.MediaType.IMAGE)
            .thenCompose(u -> {
                id.jawa.proto.Wa.Message inner = MessageEncoder.imageMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    imageBytes.length, mimetype, caption);
                return sendBuiltMessage(chatJid, MessageEncoder.viewOnceWrap(inner));
            });
    }

    public java.util.concurrent.CompletableFuture<String> sendVideoViewOnce(
            String chatJid, byte[] videoBytes, String mimetype, String caption,
            int seconds, int width, int height) {
        return encryptAndUpload(videoBytes, id.jawa.feature.media.MediaCrypto.MediaType.VIDEO)
            .thenCompose(u -> {
                id.jawa.proto.Wa.Message inner = MessageEncoder.videoMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    videoBytes.length, mimetype, caption, seconds, width, height);
                return sendBuiltMessage(chatJid, MessageEncoder.viewOnceWrap(inner));
            });
    }

    public java.util.concurrent.CompletableFuture<String> sendAudioViewOnce(
            String chatJid, byte[] audioBytes, String mimetype, int seconds, boolean ptt) {
        return encryptAndUpload(audioBytes, id.jawa.feature.media.MediaCrypto.MediaType.AUDIO)
            .thenCompose(u -> {
                id.jawa.proto.Wa.Message inner = MessageEncoder.audioMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    audioBytes.length, mimetype, seconds, ptt);
                return sendBuiltMessage(chatJid, MessageEncoder.viewOnceExtensionWrap(inner));
            });
    }

    public java.util.concurrent.CompletableFuture<String> sendVideo(
            String chatJid,
            byte[] videoBytes,
            String mimetype,
            String caption,
            int seconds,
            int width,
            int height) {
        return encryptAndUpload(videoBytes, id.jawa.feature.media.MediaCrypto.MediaType.VIDEO)
            .thenCompose(u -> sendBuiltMessage(chatJid,
                MessageEncoder.videoMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    videoBytes.length, mimetype,
                    caption, seconds, width, height)));
    }

    public java.util.concurrent.CompletableFuture<String> sendAudio(
            String chatJid,
            byte[] audioBytes,
            String mimetype,
            int seconds,
            boolean ptt) {
        return encryptAndUpload(audioBytes, id.jawa.feature.media.MediaCrypto.MediaType.AUDIO)
            .thenCompose(u -> sendBuiltMessage(chatJid,
                MessageEncoder.audioMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    audioBytes.length, mimetype, seconds, ptt)));
    }

    public java.util.concurrent.CompletableFuture<String> sendDocument(
            String chatJid,
            byte[] documentBytes,
            String mimetype,
            String fileName,
            String title) {
        return encryptAndUpload(documentBytes, id.jawa.feature.media.MediaCrypto.MediaType.DOCUMENT)
            .thenCompose(u -> sendBuiltMessage(chatJid,
                MessageEncoder.documentMessage(
                    u.upload().url(), u.upload().directPath(),
                    u.mediaKey(),
                    u.enc().fileSha256(), u.enc().fileEncSha256(),
                    documentBytes.length, mimetype, fileName, title)));
    }

    public java.util.concurrent.CompletableFuture<String> sendRevoke(
            String chatJid,
            String targetMsgId,
            String targetParticipant,
            boolean fromMe) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.revoke(
            chatJid, targetMsgId, targetParticipant, fromMe);
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    public java.util.concurrent.CompletableFuture<String> sendRevoke(
            id.jawa.feature.messaging.MessageReceiver.Decoded target) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        if (!chatJid.endsWith("@g.us")) {
            id.jawa.domain.model.Jid j = id.jawa.domain.model.Jid.parse(chatJid);
            if (j != null) chatJid = j.user() + "@" + j.server();
        }
        id.jawa.domain.model.Jid myJid = id.jawa.domain.model.Jid.parse(creds.meJid);
        id.jawa.domain.model.Jid senderJid = id.jawa.domain.model.Jid.parse(target.senderJid());
        boolean fromMe = myJid != null && senderJid != null && myJid.user().equals(senderJid.user());
        String targetParticipant = target.groupJid() != null ? target.senderJid() : null;
        return sendRevoke(chatJid, target.msgId(), targetParticipant, fromMe);
    }

    private volatile id.jawa.feature.media.MediaConn mediaConnCache;
    private final Object mediaConnLock = new Object();

    public java.util.concurrent.CompletableFuture<id.jawa.feature.media.MediaConn> refreshMediaConn() {
        synchronized (mediaConnLock) {
            id.jawa.feature.media.MediaConn cached = mediaConnCache;
            if (cached != null && !cached.isExpired(java.time.Instant.now())) {
                return java.util.concurrent.CompletableFuture.completedFuture(cached);
            }
        }
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.media.MediaConn.buildQuery(iqId);
        LOG.debug("Sent mediaConn IQ id={}", iqId);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("mediaConn IQ rejected: {}", resp);
                throw new IllegalStateException("mediaConn IQ rejected");
            }
            id.jawa.feature.media.MediaConn fresh = id.jawa.feature.media.MediaConn.parseResponse(resp);
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

    public java.util.concurrent.CompletableFuture<String> createGroup(
            String name, java.util.List<String> participantBareJids) {
        String iqId = newIqId();
        String createKey = newIqId().toUpperCase();
        BinaryNode iq = id.jawa.feature.messaging.GroupAction.buildCreate(
            iqId, name, participantBareJids, createKey);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("createGroup rejected: " + resp);
            }
            BinaryNode group = resp.child("group");
            if (group == null) throw new IllegalStateException("createGroup response missing <group>: " + resp);
            String jid = group.attr("id");
            if (jid != null && !jid.contains("@")) jid = jid + "@" + id.jawa.domain.model.Jid.SERVER_GROUP;
            return jid;
        });
    }

    public java.util.concurrent.CompletableFuture<Void> leaveGroup(String groupJid) {
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.messaging.GroupAction.buildLeave(iqId, groupJid);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("leaveGroup rejected: " + resp);
            }
        });
    }

    public java.util.concurrent.CompletableFuture<Void> updateGroupParticipants(
            String groupJid,
            id.jawa.feature.messaging.GroupAction.ParticipantChange action,
            java.util.List<String> participantBareJids) {
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.messaging.GroupAction.buildParticipantChange(
            iqId, groupJid, action, participantBareJids);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException(action + " rejected: " + resp);
            }
        });
    }

    public java.util.concurrent.CompletableFuture<Void> setGroupSubject(String groupJid, String name) {
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.messaging.GroupAction.buildSetSubject(iqId, groupJid, name);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("setGroupSubject rejected: " + resp);
            }
        });
    }

    public java.util.concurrent.CompletableFuture<Void> setGroupDescription(
            String groupJid, String body, String previousId) {
        String iqId = newIqId();
        String newId = newIqId().toUpperCase();
        BinaryNode iq = id.jawa.feature.messaging.GroupAction.buildSetDescription(
            iqId, groupJid, body, previousId, newId);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("setGroupDescription rejected: " + resp);
            }
        });
    }

    public java.util.concurrent.CompletableFuture<String> getGroupInviteLink(String groupJid, boolean reset) {
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.messaging.GroupAction.buildInviteLink(iqId, groupJid, reset);
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

    public java.util.concurrent.CompletableFuture<String> getGroupInviteLink(String groupJid) {
        return getGroupInviteLink(groupJid, false);
    }

    public java.util.concurrent.CompletableFuture<String> revokeGroupInviteLink(String groupJid) {
        return getGroupInviteLink(groupJid, true);
    }

    public java.util.concurrent.CompletableFuture<BinaryNode> getGroupInfoFromLink(String code) {
        String cleanCode = cleanInviteCode(code);
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.messaging.GroupAction.buildLinkInfo(iqId, cleanCode);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("getGroupInfoFromLink rejected: " + resp);
            }
            return resp.child("group");
        });
    }

    public java.util.concurrent.CompletableFuture<String> joinGroupWithLink(String code) {
        String cleanCode = cleanInviteCode(code);
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.messaging.GroupAction.buildJoinWithLink(iqId, cleanCode);
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

    public java.util.concurrent.CompletableFuture<Void> setGroupLocked(String groupJid, boolean locked) {
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.messaging.GroupAction.buildSetLocked(iqId, groupJid, locked);
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("setGroupLocked rejected: " + resp);
            }
        });
    }

    public java.util.concurrent.CompletableFuture<Void> setGroupAnnounce(String groupJid, boolean announce) {
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.messaging.GroupAction.buildSetAnnounce(iqId, groupJid, announce);
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

    public java.util.concurrent.CompletableFuture<java.util.List<id.jawa.feature.messaging.GroupListQuery.GroupInfo>>
            queryJoinedGroups() {
        return queryJoinedGroups(false);
    }

    public java.util.concurrent.CompletableFuture<java.util.List<id.jawa.feature.messaging.GroupListQuery.GroupInfo>>
            queryJoinedGroups(boolean forceRefresh) {
        if (!forceRefresh && !groupCache.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                new java.util.ArrayList<>(groupCache.values()));
        }
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.messaging.GroupListQuery.buildQuery(iqId);
        LOG.debug("Sent joined-groups query id={}", iqId);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("Group list query rejected: {}", resp);
                return java.util.List.of();
            }
            var list = id.jawa.feature.messaging.GroupListQuery.parseResponse(resp);
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

    public java.util.concurrent.CompletableFuture<id.jawa.feature.messaging.GroupListQuery.GroupInfo>
            queryGroupInfo(String groupJid) {
        String iqId = newIqId();
        BinaryNode iq = id.jawa.feature.messaging.GroupListQuery.buildGroupInfoQuery(iqId, groupJid);
        LOG.debug("Sent group info query id={} to={}", iqId, groupJid);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("Group info query rejected for {}: {}", groupJid, resp);
                return null;
            }
            BinaryNode groupNode = resp.child("group");
            if (groupNode == null) return null;
            id.jawa.feature.messaging.GroupListQuery.GroupInfo info =
                id.jawa.feature.messaging.GroupListQuery.parseSingleGroup(groupNode);
            if (info != null) {
                groupCache.put(info.jid(), info);
                if (info.isEphemeral() && info.ephemeralExpiration() > 0) {
                    chatEphemeralTimers.put(info.jid(), info.ephemeralExpiration());
                }
            }
            return info;
        });
    }

    public java.util.concurrent.CompletableFuture<java.util.List<org.whispersystems.libsignal.SignalProtocolAddress>>
            bootstrapSessions(String targetUser) {
        return queryDevices(java.util.List.of(targetUser))
            .thenCompose(devicesMap -> {
                var devices = devicesMap.getOrDefault(targetUser, java.util.List.of());
                if (devices.isEmpty()) {
                    java.util.List<org.whispersystems.libsignal.SignalProtocolAddress> empty = java.util.List.of();
                    return java.util.concurrent.CompletableFuture.completedFuture(empty);
                }
                id.jawa.domain.model.Jid base = id.jawa.domain.model.Jid.parse(targetUser);
                String user = base.user();
                String server = base.server();
                java.util.List<String> deviceJids = new java.util.ArrayList<>(devices.size());
                for (var d : devices) deviceJids.add(d.asJid(user, server).asString());
                return fetchBundlesAndInstallSessions(deviceJids);
            });
    }

    public java.util.concurrent.CompletableFuture<java.util.Map<String, java.util.List<id.jawa.domain.model.DeviceInfo>>>
            queryDevices(java.util.Collection<String> targetJids) {
        java.util.Map<String, java.util.List<id.jawa.domain.model.DeviceInfo>> result = new java.util.HashMap<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String tj : targetJids) {
            var cached = deviceCache.get(tj);
            if (cached != null) {
                result.put(tj, cached);
            } else {
                missing.add(tj);
            }
        }
        if (missing.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(result);
        }
        String iqId = newIqId();
        String sid = "jawa-" + Bytes.toHex(Bytes.random(4));
        BinaryNode q = id.jawa.feature.messaging.UsyncQuery.buildDeviceListQuery(iqId, sid, missing);
        LOG.debug("Sent USync device-list query id={} sid={} for {}", iqId, sid, missing);
        return sendIqAsync(q).thenApply(resp -> {
            LOG.debug("USync response: {}", resp);
            var parsed = id.jawa.feature.messaging.UsyncQuery.parseDeviceListResult(resp);
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
            java.util.Map.of(
                "to",    id.jawa.domain.model.Jid.SERVER_WHATSAPP,
                "xmlns", "passive",
                "type",  "set",
                "id",    iqId
            ),
            java.util.List.of(new BinaryNode("active", java.util.Map.of(), null)));
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
            send(new BinaryNode("presence", java.util.Map.of(
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
                org.whispersystems.libsignal.ecc.ECKeyPair kp =
                    new org.whispersystems.libsignal.ecc.ECKeyPair(
                        org.whispersystems.libsignal.ecc.Curve.decodePoint(prefixedPub, 0),
                        org.whispersystems.libsignal.ecc.Curve.decodePrivatePoint(entry.getValue().privateKey()));
                protocolStore.storePreKey(entry.getKey(),
                    new org.whispersystems.libsignal.state.PreKeyRecord(entry.getKey(), kp));
            } catch (org.whispersystems.libsignal.InvalidKeyException e) {
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
                    id.jawa.domain.model.Jid j = id.jawa.domain.model.Jid.parse(from);
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
                    id.jawa.domain.model.Receipt parsed = id.jawa.domain.model.Receipt.parse(node);
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
                    java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP,
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
        byte[] plain = BinaryEncoder.encode(node);
        byte[] enc = transport.encrypt(plain);
        frame.send(enc);
    }

    private void handleInboundMessage(BinaryNode message) {
        try {
            id.jawa.feature.messaging.MessageReceiver.Decoded decoded =
                id.jawa.feature.messaging.MessageReceiver.decode(message, protocolStore, senderKeyStore);
            if (decoded.message() != null) {
                if (decoded.message().hasProtocolMessage()) {
                    var proto = decoded.message().getProtocolMessage();
                    if (proto.getType() == id.jawa.proto.Wa.Message.ProtocolMessage.Type.EPHEMERAL_SETTING) {
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
        } catch (id.jawa.feature.messaging.MessageReceiver.DecryptException de) {
            LOG.warn("Decrypt failed for message id={} from={}: {}",
                message.attr("id"), message.attr("from"), de.getMessage());
            sendAck(message);
            sendRetryReceipt(message);
        }
    }

    private static int extractExpiration(id.jawa.proto.Wa.Message msg) {
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
        java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
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

    private final java.util.concurrent.ConcurrentMap<String, Integer> messageRetries
        = new java.util.concurrent.ConcurrentHashMap<>();
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

        java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put("id", id);
        attrs.put("to", from);
        attrs.put("type", "retry");
        String participant = message.attr("participant");
        if (participant != null) attrs.put("participant", participant);
        String recipient = message.attr("recipient");
        if (recipient != null) attrs.put("recipient", recipient);

        BinaryNode retry = new BinaryNode("retry", java.util.Map.of(
            "count", Integer.toString(retryCount),
            "id",    id,
            "t",     message.attr("t", "0"),
            "v",     "1"
        ), null);
        BinaryNode registration = new BinaryNode("registration", java.util.Map.of(),
            encodeUintBE(creds.registrationId, 4));

        send(new BinaryNode("receipt", attrs, java.util.List.of(retry, registration)));
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

    public void sendReadReceipt(id.jawa.feature.messaging.MessageReceiver.Decoded target) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        String senderJid = target.groupJid() != null ? target.senderJid() : null;
        sendReadReceipt(chatJid, target.msgId(), senderJid);
    }

    public void sendPlayedReceipt(String chatJid, String msgId, String senderJid) {
        sendStateReceipt(chatJid, msgId, senderJid, "played");
    }

    public void sendPlayedReceipt(id.jawa.feature.messaging.MessageReceiver.Decoded target) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        String senderJid = target.groupJid() != null ? target.senderJid() : null;
        sendPlayedReceipt(chatJid, target.msgId(), senderJid);
    }

    public void sendReadReceiptBatch(String chatJid, java.util.List<String> msgIds, String senderJid) {
        sendStateReceiptBatch(chatJid, msgIds, senderJid, "read");
    }

    private void sendStateReceipt(String chatJid, String msgId, String senderJid, String type) {
        sendStateReceiptBatch(chatJid, java.util.List.of(msgId), senderJid, type);
    }

    private void sendStateReceiptBatch(String chatJid, java.util.List<String> msgIds,
                                       String senderJid, String type) {
        if (msgIds.isEmpty()) return;
        java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
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
            java.util.List<BinaryNode> items = new java.util.ArrayList<>();
            for (int i = 1; i < msgIds.size(); i++) {
                items.add(BinaryNode.of("item", java.util.Map.of("id", msgIds.get(i))));
            }
            BinaryNode list = new BinaryNode("list", java.util.Map.of(), items);
            receipt = new BinaryNode("receipt", attrs, java.util.List.of(list));
        }
        send(receipt);
    }

    private void sendReceipt(BinaryNode message, String type) {
        String id = message.attr("id");
        String from = message.attr("from");
        if (id == null || from == null) return;
        java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
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

    public java.util.concurrent.CompletableFuture<java.util.Map<String, id.jawa.domain.model.ContactStatus>> isOnWhatsApp(
            java.util.Collection<String> phones) {
        String iqId = newIqId();
        String sid = newIqId();
        BinaryNode q = id.jawa.feature.messaging.UsyncQuery.buildContactQuery(iqId, sid, phones);
        LOG.debug("Sent contact check USync IQ id={} for {} phones", iqId, phones.size());
        return sendIqAsync(q).thenApply(id.jawa.feature.messaging.UsyncQuery::parseContactResult);
    }

    public void sendChatPresence(String chatJid, ChatPresence state) {
        BinaryNode child;
        if (state == ChatPresence.COMPOSING) {
            child = new BinaryNode("composing", java.util.Map.of("media", "text"), null);
        } else if (state == ChatPresence.RECORDING) {
            child = new BinaryNode("composing", java.util.Map.of("media", "audio"), null);
        } else {
            child = new BinaryNode("paused", java.util.Map.of(), null);
        }
        BinaryNode stanza = new BinaryNode("chatstate", java.util.Map.of("to", chatJid), java.util.List.of(child));
        send(stanza);
        LOG.debug("Sent chat presence state={} to={}", state, chatJid);
    }

    public void sendPresence(boolean available) {
        BinaryNode stanza = new BinaryNode("presence", java.util.Map.of(
            "type", available ? "available" : "unavailable"
        ), null);
        send(stanza);
        LOG.debug("Sent presence type={}", available ? "available" : "unavailable");
    }

    public void subscribePresence(String jid) {
        if (jid == null) return;
        BinaryNode stanza = new BinaryNode("presence", java.util.Map.of(
            "type", "subscribe",
            "to", jid
        ), null);
        send(stanza);
        LOG.debug("Sent presence subscribe to={}", jid);
    }

    public java.util.concurrent.CompletableFuture<Void> setStatusMessage(String statusText) {
        String iqId = newIqId();
        BinaryNode statusNode = new BinaryNode("status", java.util.Map.of(), statusText.getBytes());
        BinaryNode iq = new BinaryNode("iq", java.util.Map.of(
            "to", id.jawa.domain.model.Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "status",
            "id", iqId
        ), java.util.List.of(statusNode));
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("Failed to set status message: " + resp);
            }
            return null;
        });
    }

    public java.util.concurrent.CompletableFuture<java.util.List<String>> getBlocklist() {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq", java.util.Map.of(
            "to", id.jawa.domain.model.Jid.SERVER_WHATSAPP,
            "type", "get",
            "xmlns", "blocklist",
            "id", iqId
        ), null);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("Failed to get blocklist: " + resp);
            }
            BinaryNode blocklist = resp.child("blocklist");
            java.util.List<String> jids = new java.util.ArrayList<>();
            if (blocklist != null) {
                for (BinaryNode item : blocklist.children("item")) {
                    String jid = item.attr("jid");
                    if (jid != null) jids.add(jid);
                }
            }
            return jids;
        });
    }

    public java.util.concurrent.CompletableFuture<Void> updateBlocklist(String contactJid, boolean block) {
        String iqId = newIqId();
        BinaryNode item = new BinaryNode(block ? "block" : "unblock", java.util.Map.of("jid", contactJid), null);
        BinaryNode blocklist = new BinaryNode("blocklist", java.util.Map.of(), java.util.List.of(item));
        BinaryNode iq = new BinaryNode("iq", java.util.Map.of(
            "to", id.jawa.domain.model.Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "blocklist",
            "id", iqId
        ), java.util.List.of(blocklist));
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("Failed to update blocklist: " + resp);
            }
            return null;
        });
    }

    public java.util.concurrent.CompletableFuture<String> getProfilePictureInfo(String contactJid) {
        String iqId = newIqId();
        BinaryNode picture = new BinaryNode("picture", java.util.Map.of("type", "image"), null);
        BinaryNode iq = new BinaryNode("iq", java.util.Map.of(
            "to", contactJid,
            "type", "get",
            "xmlns", "w:profile:picture",
            "id", iqId
        ), java.util.List.of(picture));
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                return null;
            }
            BinaryNode pic = resp.child("picture");
            return pic != null ? pic.attr("url") : null;
        });
    }

    public java.util.concurrent.CompletableFuture<id.jawa.domain.model.PrivacySettings> getPrivacySettings() {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq", java.util.Map.of(
            "id", iqId,
            "to", id.jawa.domain.model.Jid.SERVER_WHATSAPP,
            "type", "get",
            "xmlns", "privacy"
        ), java.util.List.of(new BinaryNode("privacy", java.util.Map.of(), null)));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode privacy = resp.child("privacy");
            java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
            if (privacy != null) {
                for (BinaryNode child : privacy.childrenList()) {
                    if ("category".equals(child.tag())) {
                        String name = child.attr("name");
                        String val = child.attr("value");
                        if (name != null && val != null) map.put(name, val);
                    }
                }
            }
            return new id.jawa.domain.model.PrivacySettings(
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

    public java.util.concurrent.CompletableFuture<Void> setPrivacySetting(String category, String value) {
        String iqId = newIqId();
        BinaryNode catNode = new BinaryNode("category", java.util.Map.of(
            "name", category,
            "value", value
        ), null);
        BinaryNode privacyNode = new BinaryNode("privacy", java.util.Map.of(), java.util.List.of(catNode));
        BinaryNode iq = new BinaryNode("iq", java.util.Map.of(
            "id", iqId,
            "to", id.jawa.domain.model.Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "privacy"
        ), java.util.List.of(privacyNode));
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("Failed to set privacy setting: " + resp);
            }
        });
    }

    public java.util.concurrent.CompletableFuture<Void> setDefaultDisappearingTimer(int seconds) {
        String iqId = newIqId();
        BinaryNode node = new BinaryNode("disappearing_mode", java.util.Map.of(
            "duration", String.valueOf(seconds)
        ), null);
        BinaryNode iq = new BinaryNode("iq", java.util.Map.of(
            "id", iqId,
            "to", id.jawa.domain.model.Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "disappearing_mode"
        ), java.util.List.of(node));
        return sendIqAsync(iq).thenAccept(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("Failed to set default disappearing timer: " + resp);
            }
        });
    }

    public java.util.concurrent.CompletableFuture<String> getContactQrLink(boolean revoke) {
        String iqId = newIqId();
        BinaryNode qrNode = new BinaryNode("qr", java.util.Map.of(
            "type", "contact",
            "action", revoke ? "revoke" : "get"
        ), null);
        BinaryNode iq = new BinaryNode("iq", java.util.Map.of(
            "id", iqId,
            "type", "set",
            "xmlns", "w:qr"
        ), java.util.List.of(qrNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode qr = resp.child("qr");
            if (qr == null) throw new IllegalStateException("no qr node in response: " + resp);
            String code = qr.attr("code");
            if (code == null) throw new IllegalStateException("no code in qr response");
            return "https://wa.me/qr/" + code;
        });
    }

    public java.util.concurrent.CompletableFuture<id.jawa.domain.model.ContactQrTarget> resolveContactQrLink(String codeOrUrl) {
        String code = codeOrUrl;
        if (code.contains("/")) code = code.substring(code.lastIndexOf('/') + 1);
        if (code.contains("?")) code = code.substring(0, code.indexOf('?'));
        String iqId = newIqId();
        BinaryNode qrNode = new BinaryNode("qr", java.util.Map.of(
            "code", code
        ), null);
        BinaryNode iq = new BinaryNode("iq", java.util.Map.of(
            "id", iqId,
            "type", "get",
            "xmlns", "w:qr"
        ), java.util.List.of(qrNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode qr = resp.child("qr");
            if (qr == null) throw new IllegalStateException("contact qr link not found: " + resp);
            return new id.jawa.domain.model.ContactQrTarget(
                qr.attr("jid"),
                qr.attr("notify"),
                qr.attr("type")
            );
        });
    }

    public java.util.concurrent.CompletableFuture<java.util.Map<String, id.jawa.domain.model.UserInfo>>
            getUserInfo(java.util.Collection<String> targetJids) {
        String iqId = newIqId();
        String sid = newIqId();
        BinaryNode q = id.jawa.feature.messaging.UsyncQuery.buildUserInfoQuery(iqId, sid, targetJids);
        LOG.debug("Sent user-info query id={} for {} jids", iqId, targetJids.size());
        return sendIqAsync(q).thenApply(resp -> {
            var map = id.jawa.feature.messaging.UsyncQuery.parseUserInfoResult(resp);
            for (var entry : map.entrySet()) {
                if (entry.getValue().lid() != null && !entry.getValue().lid().isEmpty()) {
                    LID_TO_PN_MAP.put(entry.getValue().lid(), entry.getKey());
                }
            }
            return map;
        });
    }

    public java.util.concurrent.CompletableFuture<id.jawa.domain.model.UserInfo>
            getUserInfo(String targetJid) {
        return getUserInfo(java.util.List.of(targetJid))
            .thenApply(map -> map.get(targetJid));
    }

    public java.util.concurrent.CompletableFuture<id.jawa.domain.model.BusinessProfile> getBusinessProfile(String jid) {
        String iqId = newIqId();
        String targetJid = jid.contains("@") ? jid : jid + "@s.whatsapp.net";
        BinaryNode profileNode = new BinaryNode("profile", java.util.Map.of("jid", targetJid), null);
        BinaryNode bizNode = new BinaryNode("business_profile", java.util.Map.of("v", "244"), java.util.List.of(profileNode));
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "w:biz", "id", iqId),
            java.util.List.of(bizNode));
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
            java.util.List<String> websites = new java.util.ArrayList<>();
            for (BinaryNode w : prof.children("website")) {
                if (w.textContent() != null && !w.textContent().isEmpty()) {
                    websites.add(w.textContent());
                }
            }
            java.util.List<id.jawa.domain.model.BusinessProfile.Category> categories = new java.util.ArrayList<>();
            BinaryNode catNode = prof.child("categories");
            if (catNode != null) {
                for (BinaryNode c : catNode.children("category")) {
                    categories.add(new id.jawa.domain.model.BusinessProfile.Category(c.attr("id"), c.textContent()));
                }
            }
            BinaryNode bhNode = prof.child("business_hours");
            String timezone = bhNode != null ? bhNode.attr("timezone") : null;
            java.util.List<id.jawa.domain.model.BusinessProfile.BusinessHourConfig> hours = new java.util.ArrayList<>();
            if (bhNode != null) {
                for (BinaryNode h : bhNode.children("business_hours_config")) {
                    hours.add(new id.jawa.domain.model.BusinessProfile.BusinessHourConfig(
                        h.attr("day_of_week"),
                        h.attr("mode"),
                        h.attr("open_time"),
                        h.attr("close_time")
                    ));
                }
            }
            java.util.Map<String, String> options = new java.util.HashMap<>();
            BinaryNode poNode = prof.child("profile_options");
            if (poNode != null) {
                for (BinaryNode opt : poNode.childrenList()) {
                    if (opt.textContent() != null) {
                        options.put(opt.tag(), opt.textContent());
                    }
                }
            }
            return new id.jawa.domain.model.BusinessProfile(
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

    public java.util.concurrent.CompletableFuture<id.jawa.domain.model.BusinessMessageLinkTarget> resolveBusinessMessageLink(String codeOrUrl) {
        String code = codeOrUrl;
        if (code.contains("/")) code = code.substring(code.lastIndexOf('/') + 1);
        if (code.contains("?")) code = code.substring(0, code.indexOf('?'));
        String iqId = newIqId();
        BinaryNode qrNode = new BinaryNode("qr", java.util.Map.of("code", code), null);
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("type", "get", "xmlns", "w:qr", "id", iqId),
            java.util.List.of(qrNode));
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
            return new id.jawa.domain.model.BusinessMessageLinkTarget(
                jid,
                pushName,
                message,
                isSigned,
                verifiedName,
                verifiedLevel
            );
        });
    }

    public java.util.concurrent.CompletableFuture<String> getStatus(String targetJid) {
        String iqId = newIqId();
        java.util.Map<String, String> attrs = new java.util.HashMap<>();
        if (targetJid != null && !targetJid.isBlank()) {
            attrs.put("jid", targetJid.contains("@") ? targetJid : targetJid + "@s.whatsapp.net");
        }
        BinaryNode statusNode = new BinaryNode("status", attrs, null);
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "status", "id", iqId),
            java.util.List.of(statusNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode s = resp.child("status");
            return s != null ? s.textContent() : null;
        });
    }

    public java.util.concurrent.CompletableFuture<Void> setGroupJoinApprovalMode(String groupJid, boolean approvalRequired) {
        String iqId = newIqId();
        BinaryNode groupJoin = new BinaryNode("group_join", java.util.Map.of("state", approvalRequired ? "on" : "off"), null);
        BinaryNode approvalNode = new BinaryNode("membership_approval_mode", java.util.Map.of(), java.util.List.of(groupJoin));
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", groupJid, "type", "set", "xmlns", "w:g2", "id", iqId),
            java.util.List.of(approvalNode));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public java.util.concurrent.CompletableFuture<Void> setGroupMemberAddMode(String groupJid, boolean adminOnly) {
        String iqId = newIqId();
        String modeStr = adminOnly ? "admin_add" : "all_member_add";
        BinaryNode modeNode = new BinaryNode("member_add_mode", java.util.Map.of(), modeStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", groupJid, "type", "set", "xmlns", "w:g2", "id", iqId),
            java.util.List.of(modeNode));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public java.util.concurrent.CompletableFuture<java.util.List<id.jawa.domain.model.GroupJoinRequest>> getGroupRequestParticipants(String groupJid) {
        String iqId = newIqId();
        BinaryNode reqNode = new BinaryNode("membership_approval_requests", java.util.Map.of(), null);
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", groupJid, "type", "get", "xmlns", "w:g2", "id", iqId),
            java.util.List.of(reqNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode reqs = resp.child("membership_approval_requests");
            java.util.List<id.jawa.domain.model.GroupJoinRequest> list = new java.util.ArrayList<>();
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
                        list.add(new id.jawa.domain.model.GroupJoinRequest(jid, time));
                    }
                }
            }
            return list;
        });
    }

    public java.util.concurrent.CompletableFuture<Void> updateGroupRequestParticipants(
            String groupJid, java.util.List<String> userJids, boolean approve) {
        String iqId = newIqId();
        String action = approve ? "approve" : "reject";
        java.util.List<BinaryNode> participants = new java.util.ArrayList<>();
        for (String jid : userJids) {
            participants.add(new BinaryNode("participant", java.util.Map.of("jid", jid), null));
        }
        BinaryNode actionNode = new BinaryNode(action, java.util.Map.of(), participants);
        BinaryNode reqAction = new BinaryNode("membership_requests_action", java.util.Map.of(), java.util.List.of(actionNode));
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", groupJid, "type", "set", "xmlns", "w:g2", "id", iqId),
            java.util.List.of(reqAction));
        return sendIqAsync(iq).thenApply(resp -> null);
    }

    public java.util.concurrent.CompletableFuture<Void> rejectCall(String callFrom, String callId) {
        if (creds == null || creds.meJid == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Not logged in"));
        }
        String ownJid = id.jawa.domain.model.Jid.bare(creds.meJid);
        String cleanFrom = id.jawa.domain.model.Jid.bare(callFrom);
        BinaryNode rejectNode = new BinaryNode("reject",
            java.util.Map.of("call-id", callId, "call-creator", cleanFrom, "count", "0"),
            null);
        BinaryNode callNode = new BinaryNode("call",
            java.util.Map.of("id", generateMessageId(), "from", ownJid, "to", cleanFrom),
            java.util.List.of(rejectNode));
        send(callNode);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    public java.util.concurrent.CompletableFuture<Long> subscribeNewsletterLiveUpdates(String newsletterJid) {
        String iqId = newIqId();
        BinaryNode liveNode = new BinaryNode("live_updates", java.util.Map.of(), null);
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", newsletterJid, "type", "set", "xmlns", "newsletter", "id", iqId),
            java.util.List.of(liveNode));
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

    public java.util.concurrent.CompletableFuture<Void> markNewsletterViewed(String newsletterJid, java.util.List<Long> serverIds) {
        java.util.List<BinaryNode> items = new java.util.ArrayList<>();
        for (Long id : serverIds) {
            items.add(new BinaryNode("item", java.util.Map.of("server_id", String.valueOf(id)), null));
        }
        BinaryNode listNode = new BinaryNode("list", java.util.Map.of(), items);
        BinaryNode receiptNode = new BinaryNode("receipt",
            java.util.Map.of("to", newsletterJid, "type", "view", "id", generateMessageId()),
            java.util.List.of(listNode));
        send(receiptNode);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    public java.util.concurrent.CompletableFuture<java.util.List<id.jawa.domain.model.NewsletterMessage>>
            getNewsletterMessages(String newsletterJid, int count, Long beforeServerId) {
        String iqId = newIqId();
        java.util.Map<String, String> attrs = new java.util.HashMap<>();
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
            java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "newsletter", "id", iqId),
            java.util.List.of(q));
        return sendIqAsync(iq).thenApply(resp -> {
            java.util.List<id.jawa.domain.model.NewsletterMessage> list = new java.util.ArrayList<>();
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
                java.util.Map<String, Integer> reactions = new java.util.HashMap<>();
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
                id.jawa.proto.Wa.Message protoMsg = null;
                BinaryNode pt = msgNode.child("plaintext");
                if (pt != null && pt.bytesContent() != null) {
                    try {
                        protoMsg = id.jawa.proto.Wa.Message.parseFrom(pt.bytesContent());
                    } catch (Exception ignored) {}
                }
                list.add(new id.jawa.domain.model.NewsletterMessage(
                    serverId, msgId, type, timestamp, viewsCount, reactions, protoMsg
                ));
            }
            return list;
        });
    }

    public void disconnect() {
        close();
    }

    public java.util.concurrent.CompletableFuture<Void> logout() {
        if (creds == null || creds.meJid == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Not logged in"));
        }
        String iqId = newIqId();
        BinaryNode rmDevice = new BinaryNode("remove-companion-device", java.util.Map.of(
            "jid", creds.meJid,
            "reason", "user_initiated"
        ), null);
        BinaryNode iq = new BinaryNode("iq", java.util.Map.of(
            "to", id.jawa.domain.model.Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "md",
            "id", iqId
        ), java.util.List.of(rmDevice));
        return sendIqAsync(iq).handle((resp, err) -> {
            try {
                store.clear();
            } catch (Exception ignored) {}
            close();
            return null;
        });
    }

    public java.util.concurrent.CompletableFuture<Void> updatePushName(String newPushName) {
        if (creds == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Not logged in"));
        }
        creds.pushName = newPushName;
        try {
            store.save(creds);
        } catch (Exception e) {
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        }
        send(new BinaryNode("presence", java.util.Map.of(
            "type", "available",
            "name", newPushName
        ), null));
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    public java.util.concurrent.CompletableFuture<String> sendMexQuery(String queryId, String jsonVariables) {
        String iqId = newIqId();
        String payload = "{\"variables\":" + (jsonVariables != null && !jsonVariables.isBlank() ? jsonVariables : "{}") + "}";
        BinaryNode queryNode = new BinaryNode("query",
            java.util.Map.of("query_id", queryId),
            payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of("to", id.jawa.domain.model.Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "w:mex", "id", iqId),
            java.util.List.of(queryNode));
        return sendIqAsync(iq).thenApply(resp -> {
            BinaryNode res = resp.child("result");
            return res != null ? res.textContent() : null;
        });
    }

    public java.util.concurrent.CompletableFuture<Void> followNewsletter(String newsletterJid) {
        String vars = "{\"newsletter_id\":\"" + newsletterJid + "\"}";
        return sendMexQuery("9926858900719341", vars).thenApply(resp -> null);
    }

    public java.util.concurrent.CompletableFuture<Void> unfollowNewsletter(String newsletterJid) {
        String vars = "{\"newsletter_id\":\"" + newsletterJid + "\"}";
        return sendMexQuery("6392786840836363", vars).thenApply(resp -> null);
    }

    public java.util.concurrent.CompletableFuture<Void> toggleMuteNewsletter(String newsletterJid, boolean mute) {
        String queryId = mute ? "6274038279359549" : "6068417879924485";
        String vars = "{\"newsletter_id\":\"" + newsletterJid + "\"}";
        return sendMexQuery(queryId, vars).thenApply(resp -> null);
    }

    public java.util.concurrent.CompletableFuture<id.jawa.domain.model.NewsletterMetadata> getNewsletterInfo(String newsletterJid) {
        String vars = "{\"fetch_creation_time\":true,\"fetch_full_image\":true,\"fetch_viewer_metadata\":true,\"input\":{\"key\":\"" + newsletterJid + "\",\"type\":\"JID\"}}";
        return sendMexQuery("6563316087068696", vars).thenApply(json -> parseNewsletterJson(json, newsletterJid));
    }

    public java.util.concurrent.CompletableFuture<id.jawa.domain.model.NewsletterMetadata> getNewsletterInfoWithInvite(String inviteCodeOrUrl) {
        String code = inviteCodeOrUrl;
        if (code.contains("/")) code = code.substring(code.lastIndexOf('/') + 1);
        if (code.contains("?")) code = code.substring(0, code.indexOf('?'));
        String vars = "{\"fetch_creation_time\":true,\"fetch_full_image\":true,\"fetch_viewer_metadata\":false,\"input\":{\"key\":\"" + code + "\",\"type\":\"INVITE\"}}";
        return sendMexQuery("6563316087068696", vars).thenApply(json -> parseNewsletterJson(json, null));
    }

    private static id.jawa.domain.model.NewsletterMetadata parseNewsletterJson(String json, String fallbackJid) {
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

        return new id.jawa.domain.model.NewsletterMetadata(
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

    public AuthCreds creds() { return creds; }
}
