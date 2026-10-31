package id.ja_wa.feature.appstate;


import com.google.protobuf.InvalidProtocolBufferException;
import id.ja_wa.protocol.codec.BinaryNode;
import id.ja_wa.proto.Wa;
import id.ja_wa.domain.model.Jid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AppStateSyncQuery {

    private AppStateSyncQuery() {}

    public static BinaryNode buildSnapshotRequest(String iqId, PatchName name) {
        return buildIq(iqId, name,  -1L,  true);
    }

    public static BinaryNode buildPatchRequest(String iqId, PatchName name, long fromVersion) {
        return buildIq(iqId, name, fromVersion, false);
    }

    private static BinaryNode buildIq(String iqId, PatchName name, long fromVersion, boolean returnSnapshot) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("name", name.wire);
        attrs.put("return_snapshot", returnSnapshot ? "true" : "false");
        if (!returnSnapshot) attrs.put("version", Long.toString(fromVersion));

        BinaryNode collection = new BinaryNode("collection", attrs, null);
        BinaryNode sync = new BinaryNode("sync", Map.of(), List.of(collection));
        return new BinaryNode("iq",
            Map.of(
                "id",    iqId,
                "type",  "set",
                "xmlns", "w:sync:app:state",
                "to",    Jid.SERVER_WHATSAPP
            ),
            List.of(sync));
    }

    /**
     * Increment 5: the parsed sync response, now carrying the version
     * numbers the server reports — previously dropped entirely, which
     * is why no resync adapter could refresh the version store
     * (Increment 4's version-plumbing gap).
     *
     * <p>Where versions appear, per Baileys
     * {@code lib/Utils/chat-utils.js:248} {@code extractSyncdPatches}
     * and {@code :297,332} {@code decodeSyncdSnapshot}/{@code decodePatches}:
     * the snapshot proto's {@code version} and each patch proto's
     * {@code version} ({@code SyncdVersion{version}} in
     * {@code wa.proto}), plus the response collection node's
     * {@code version} attribute, which Baileys uses only as a fallback
     * (patch version = collection version + 1 when a patch proto
     * carries no version). {@link #reportedVersion()} applies exactly
     * that precedence: last patch's version (or its fallback) when
     * patches are present, else the snapshot's version, else null.
     * Null means "the server response carries no usable version" — it
     * is never filled in from the request's {@code fromVersion} or
     * from stored state, because either would fabricate a server
     * version. A response with neither snapshot nor patches (an
     * up-to-date empty reply) therefore reports null, and an
     * external-blob snapshot ({@code externalSnapshot} non-null, whose
     * records wa-java does not download) likewise reports null unless
     * patches in the same reply carry versions.
     */
    public record PatchList(
        PatchName name,
        boolean hasMorePatches,
        Wa.SyncdSnapshot snapshot,
        List<Wa.SyncdPatch> patches,
        Wa.ExternalBlobReference externalSnapshot,
        Long collectionVersion
    ) {
        /** Server-reported version for this collection, or null when unusable/absent. */
        public Long reportedVersion() {
            if (patches != null && !patches.isEmpty()) {
                return SyncStateEvolver.patchVersion(patches.get(patches.size() - 1), collectionVersion);
            }
            if (snapshot != null && snapshot.hasVersion()) {
                return snapshot.getVersion().getVersion();
            }
            return null;
        }
    }

    public static PatchList parseResponse(BinaryNode iqResult) {
        BinaryNode sync = iqResult.child("sync");
        if (sync == null) return null;
        BinaryNode collection = sync.child("collection");
        if (collection == null) return null;

        PatchName name = PatchName.fromWire(collection.attr("name"));
        boolean hasMore = "true".equals(collection.attr("has_more_patches"));
        Long collectionVersion = null;
        String versionAttr = collection.attr("version");
        if (versionAttr != null && !versionAttr.isBlank()) {
            try { collectionVersion = Long.parseLong(versionAttr); }
            catch (NumberFormatException ignored) { /* unusable version attr: reported as null */ }
        }

        Wa.SyncdSnapshot snapshot = null;
        Wa.ExternalBlobReference externalSnapshot = null;
        BinaryNode snapshotNode = collection.child("snapshot");
        if (snapshotNode != null && snapshotNode.bytesContent() != null) {
            try {
                Wa.ExternalBlobReference ref = Wa.ExternalBlobReference.parseFrom(snapshotNode.bytesContent());
                if (!ref.getDirectPath().isEmpty()) externalSnapshot = ref;
                else snapshot = Wa.SyncdSnapshot.parseFrom(snapshotNode.bytesContent());
            } catch (InvalidProtocolBufferException ignored) {
                try {
                    snapshot = Wa.SyncdSnapshot.parseFrom(snapshotNode.bytesContent());
                } catch (InvalidProtocolBufferException ignored2) {  }
            }
        }

        List<Wa.SyncdPatch> patches = new ArrayList<>();
        BinaryNode patchesNode = collection.child("patches");
        if (patchesNode != null) {
            for (BinaryNode patchNode : patchesNode.childrenList()) {
                if (!"patch".equals(patchNode.tag())) continue;
                byte[] bytes = patchNode.bytesContent();
                if (bytes == null) continue;
                try { patches.add(Wa.SyncdPatch.parseFrom(bytes)); }
                catch (InvalidProtocolBufferException ignored) {  }
            }
        }

        return new PatchList(name, hasMore, snapshot, patches, externalSnapshot, collectionVersion);
    }
}
