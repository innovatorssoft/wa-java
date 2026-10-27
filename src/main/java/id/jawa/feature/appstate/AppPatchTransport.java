package id.jawa.feature.appstate;

import id.jawa.domain.model.Jid;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Send side of {@code appPatch} (Baileys {@code lib/Socket/chats.js:712}).
 *
 * <p>Increment 5 wired the JaWaClient hook this class was waiting for
 * ({@code JaWaClient.appPatch}, backed by
 * {@code creds.myAppStateKeyId} and a
 * {@link FileAppStateVersionStore} or the in-memory store): the
 * transport-taking {@code ChatService.sendServer*} overloads still
 * take an explicit transport for callers that want one, and the
 * no-transport overloads delegate to the client hook. This class
 * itself is unchanged — it remains the transport the hook builds on.
 *
 * <p>Pipeline, mirroring Baileys {@code appPatch} step for step:
 * resync the collection, read the current version state (fresh state
 * when none is stored — Baileys' {@code newLTHashState()} branch),
 * {@link AppPatchEncoder#encode encode} the patch, send the
 * {@code w:sync:app:state} IQ with a {@code collection} whose
 * {@code version} is the <em>new</em> state version minus 1 (Baileys:
 * {@code version: (state.version - 1).toString()},
 * {@code return_snapshot: 'false'}) carrying the {@code SyncdPatch}
 * bytes, and only then persist the new version (Baileys persists after
 * {@code query(node)} succeeds). An error IQ, a missing key, or a
 * failed send therefore leaves the stored version untouched.
 *
 * <p>Resync contract (read this before wiring a custom transport):
 * Baileys' {@code resyncAppState} refreshes the stored version from the
 * server before encoding, so the encoder never builds on a stale
 * version. The JaWaClient hook's resync adapter does this via
 * {@code requestAppStateSyncWithVersions}, which persists the evolved
 * LtHash state when trustworthy (see {@code SyncStateEvolver}); a
 * hand-built transport's resync adapter must update the version store
 * when it learns a newer version, or the transport encodes on whatever
 * {@link AppStateVersionStore#load(String)} returns.
 *
 * <p>Silent-failure behaviour is <em>not</em> changed: this class adds
 * no connected check of its own. If the {@link IqSender} is
 * {@code JaWaClient::sendIqAsync}, a disconnected client behaves
 * exactly as every other IQ method does — {@code JaWaClient.send()}
 * logs a warning and drops the node, leaving the future pending —
 * because that behaviour belongs to the client, not to this transport.
 * Callers that need a failure on disconnect must enforce it in their
 * {@link IqSender}.
 */
public final class AppPatchTransport {

    /** Resyncs one collection before a patch (Baileys {@code resyncAppState}). */
    @FunctionalInterface
    public interface Resync {
        /** @param collectionName patch type, e.g. {@code "regular"} */
        CompletableFuture<Void> resync(String collectionName);
    }

    /** Looks up an app-state-sync key by raw key id. */
    @FunctionalInterface
    public interface KeyLookup {
        /** @return the key, or null when not present */
        AppStateKey lookup(byte[] keyId);
    }

    /** Sends an IQ and completes with the server's response node. */
    @FunctionalInterface
    public interface IqSender {
        CompletableFuture<BinaryNode> sendIq(BinaryNode iq);
    }

    private final KeyLookup keyLookup;
    private final Resync resync;
    private final AppStateVersionStore versionStore;
    private final IqSender iqSender;
    private final Supplier<String> iqIdSupplier;

    /**
     * @param keyLookup key lookup by raw key id; null lookups fail the send
     * @param resync collection resync step, run before every patch
     * @param versionStore version state per collection
     * @param iqSender IQ sender (e.g. {@code JaWaClient::sendIqAsync} in the hook)
     * @param iqIdSupplier IQ id generator (e.g. the client's id generator)
     */
    public AppPatchTransport(KeyLookup keyLookup, Resync resync, AppStateVersionStore versionStore,
                             IqSender iqSender, Supplier<String> iqIdSupplier) {
        if (keyLookup == null) throw new IllegalArgumentException("keyLookup must not be null");
        if (resync == null) throw new IllegalArgumentException("resync must not be null");
        if (versionStore == null) throw new IllegalArgumentException("versionStore must not be null");
        if (iqSender == null) throw new IllegalArgumentException("iqSender must not be null");
        if (iqIdSupplier == null) throw new IllegalArgumentException("iqIdSupplier must not be null");
        this.keyLookup = keyLookup;
        this.resync = resync;
        this.versionStore = versionStore;
        this.iqSender = iqSender;
        this.iqIdSupplier = iqIdSupplier;
    }

    /**
     * Resyncs, encodes, sends and (on success) persists one patch.
     *
     * @param patch patch model from {@link AppPatchBuilder}
     * @param myKeyId raw id of the account's app-state-sync key
     *                  (Baileys {@code creds.myAppStateKeyId}, base64-decoded)
     * @return future completing with the encoder result; fails (without
     *         persisting a new version) on a missing key id, a missing
     *         key, an encoding failure, an error IQ, or a send failure
     */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> send(AppPatchBuilder.AppPatch patch, byte[] myKeyId) {
        if (patch == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("patch must not be null"));
        }
        if (myKeyId == null || myKeyId.length == 0) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("App state key not present: myAppStateKeyId is not set"));
        }
        byte[] keyId = myKeyId.clone();
        return resync.resync(patch.type())
            .thenApply(ignored -> {
                LtHashState current = versionStore.load(patch.type());
                if (current == null) current = LtHashState.fresh();
                AppStateKey key = keyLookup.lookup(keyId);
                if (key == null) {
                    throw new IllegalStateException("app-state key not present for the given key id");
                }
                return AppPatchEncoder.encode(patch, key, current);
            })
            .thenCompose(encoded -> {
                BinaryNode iq = buildAppPatchIq(iqIdSupplier.get(), patch, encoded);
                return iqSender.sendIq(iq).thenApply(resp -> {
                    if (resp != null && "error".equals(resp.attr("type"))) {
                        throw new IllegalStateException("appPatch rejected by server: " + resp);
                    }
                    versionStore.save(patch.type(), encoded.state());
                    return encoded;
                });
            });
    }

    /**
     * Builds the {@code w:sync:app:state} IQ for an encoded patch,
     * mirroring Baileys {@code appPatch}'s node: {@code iq(to:
     * s.whatsapp.net, type: set, xmlns: w:sync:app:state) > sync >
     * collection(name, version: newVersion - 1, return_snapshot: false)
     * > patch(SyncdPatch bytes)}.
     *
     * @param iqId IQ id
     * @param patch original patch model (its {@code type} is the collection name)
     * @param encoded encoder result
     * @return the IQ node, ready for {@code sendIqAsync}
     */
    public static BinaryNode buildAppPatchIq(String iqId, AppPatchBuilder.AppPatch patch,
                                             AppPatchEncoder.EncodedPatch encoded) {
        if (iqId == null || iqId.isBlank()) throw new IllegalArgumentException("iqId must not be blank");
        if (patch == null) throw new IllegalArgumentException("patch must not be null");
        if (encoded == null) throw new IllegalArgumentException("encoded must not be null");
        long collectionVersion = encoded.state().version() - 1;
        if (collectionVersion < 0) {
            throw new IllegalArgumentException("encoded state version must be at least 1");
        }
        BinaryNode patchNode = BinaryNode.bytes("patch", Map.of(), encoded.patch().toByteArray());
        BinaryNode collection = BinaryNode.children("collection",
            Map.of("name", patch.type(),
                "version", Long.toString(collectionVersion),
                "return_snapshot", "false"),
            List.of(patchNode));
        BinaryNode sync = BinaryNode.children("sync", Map.of(), List.of(collection));
        return BinaryNode.children("iq",
            Map.of("id", iqId,
                "type", "set",
                "xmlns", "w:sync:app:state",
                "to", Jid.SERVER_WHATSAPP),
            List.of(sync));
    }
}
