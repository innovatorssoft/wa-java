package id.jawa.feature.appstate;


import com.google.protobuf.InvalidProtocolBufferException;
import id.jawa.protocol.codec.BinaryNode;
import id.jawa.proto.Wa;
import id.jawa.domain.model.Jid;

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

    public record PatchList(
        PatchName name,
        boolean hasMorePatches,
        Wa.SyncdSnapshot snapshot,
        List<Wa.SyncdPatch> patches,
        Wa.ExternalBlobReference externalSnapshot
    ) {}

    public static PatchList parseResponse(BinaryNode iqResult) {
        BinaryNode sync = iqResult.child("sync");
        if (sync == null) return null;
        BinaryNode collection = sync.child("collection");
        if (collection == null) return null;

        PatchName name = PatchName.fromWire(collection.attr("name"));
        boolean hasMore = "true".equals(collection.attr("has_more_patches"));

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

        return new PatchList(name, hasMore, snapshot, patches, externalSnapshot);
    }
}
