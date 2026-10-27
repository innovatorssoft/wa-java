package id.jawa.feature.appstate;

import id.jawa.proto.Wa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a collection's LtHash state from decoded inbound sync data —
 * the wa-java half of Baileys' {@code decodeSyncdSnapshot} /
 * {@code decodePatches} state evolution ({@code lib/Utils/chat-utils.js:297,332}).
 *
 * <p>Baileys' rule, mirrored here: a snapshot starts from a fresh
 * state and takes the snapshot proto's version; each patch starts from
 * the previous state and takes the patch proto's version — or, when a
 * patch proto carries no version, the response collection's version
 * plus 1 (Baileys' {@code extractSyncdPatches} fallback,
 * {@code lib/Utils/chat-utils.js:248}). The LtHash mix itself follows
 * Baileys' {@code makeLtHashGenerator.mix}: a SET adds its value MAC
 * (subtracting any previous value MAC for the same index); a REMOVE
 * subtracts the previous value MAC and drops the index, and mixes
 * nothing when the index was not mapped. Note this deliberately
 * differs from the <em>encoder's</em> quirk
 * ({@link AppPatchEncoder}: a locally encoded REMOVE re-adds its own
 * value MAC to the map); decode evolution follows the decode path,
 * which does not re-add.
 *
 * <p>Trust rule, stated plainly: evolution returns {@code null} —
 * "do not persist" — whenever it cannot be trusted, instead of
 * persisting a half-evolved state that would corrupt the next patch's
 * MACs: a missing version (no proto version and no collection
 * fallback), or any undecodable record/mutation in the segment
 * ({@link AppStateProcessor} silently skips those, so the caller must
 * pass the decoded count alongside the input count and this class
 * checks them). A {@code null} result means the version store keeps
 * whatever it had (or stays empty) and the next patch encodes from
 * that stored state after a fresh resync — it is never a licence to
 * persist a version number with a zero hash.
 *
 * <p>MAC caveat: wa-java's decode path runs with
 * {@code validateMacs=false} (see {@code JaWaClient.requestAppStateSync}),
 * so an evolved state is not snapshot-MAC-verified the way Baileys'
 * decode is when validation is on. That is the pre-existing decode
 * caveat, inherited here, not introduced by this class.
 */
public final class SyncStateEvolver {

    private SyncStateEvolver() {}

    /**
     * Evolves a fresh state from a snapshot segment.
     *
     * @param snapshot snapshot proto (its version is the new version)
     * @param decoded mutations decoded from the snapshot's records
     * @return the evolved state, or null when the snapshot has no
     *         version or any record failed to decode
     */
    public static LtHashState evolveFromSnapshot(Wa.SyncdSnapshot snapshot,
                                                 List<AppStateProcessor.Mutation> decoded) {
        if (snapshot == null || decoded == null) return null;
        if (!snapshot.hasVersion()) return null;
        if (decoded.size() != snapshot.getRecordsCount()) return null;
        LtHashState evolved = mix(LtHashState.fresh(), decoded);
        return new LtHashState(snapshot.getVersion().getVersion(), evolved.hash(), evolved.indexValueMap());
    }

    /**
     * Evolves a state through patch segments in order.
     *
     * @param current state to start from (snapshot-evolved state, or
     *                the stored state for a patch-only sync); null
     *                means "no trustworthy base" and returns null
     * @param patches patch protos in server order
     * @param decodedPerPatch decoded mutations per patch, in the same
     *                        order as {@code patches}
     * @param collectionVersion response collection's {@code version}
     *                          attribute, or null when absent — the
     *                          fallback for patches without a proto
     *                          version (fallback value is +1)
     * @return the evolved state, or null when any patch lacks a
     *         derivable version or any mutation failed to decode
     */
    public static LtHashState evolveFromPatches(LtHashState current, List<Wa.SyncdPatch> patches,
                                                List<List<AppStateProcessor.Mutation>> decodedPerPatch,
                                                Long collectionVersion) {
        if (current == null || patches == null || decodedPerPatch == null) return null;
        if (decodedPerPatch.size() != patches.size()) return null;
        LtHashState state = current;
        for (int i = 0; i < patches.size(); i++) {
            Wa.SyncdPatch patch = patches.get(i);
            List<AppStateProcessor.Mutation> decoded = decodedPerPatch.get(i);
            if (decoded == null || decoded.size() != patch.getMutationsCount()) return null;
            Long version = patchVersion(patch, collectionVersion);
            if (version == null) return null;
            LtHashState mixed = mix(state, decoded);
            state = new LtHashState(version, mixed.hash(), mixed.indexValueMap());
        }
        return state;
    }

    /** A patch's version: its proto version, else collection version + 1, else null. */
    static Long patchVersion(Wa.SyncdPatch patch, Long collectionVersion) {
        if (patch.hasVersion()) return patch.getVersion().getVersion();
        if (collectionVersion != null) return collectionVersion + 1;
        return null;
    }

    /** Baileys {@code makeLtHashGenerator.mix} over decoded mutations, version untouched. */
    private static LtHashState mix(LtHashState base, List<AppStateProcessor.Mutation> mutations) {
        Map<String, byte[]> map = base.mutableIndexValueMap();
        List<byte[]> subtract = new ArrayList<>();
        List<byte[]> add = new ArrayList<>();
        for (AppStateProcessor.Mutation m : mutations) {
            String key = LtHashState.mapKey(m.indexMac());
            byte[] prev = map.get(key);
            if (m.operation() == Wa.SyncdMutation.SyncdOperation.REMOVE) {
                if (prev != null) {
                    map.remove(key);
                    subtract.add(prev);
                }
            } else {
                add.add(m.valueMac());
                map.put(key, m.valueMac().clone());
                if (prev != null) subtract.add(prev);
            }
        }
        byte[] hash = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(
            base.hash(), subtract.toArray(new byte[0][]), add.toArray(new byte[0][]));
        return new LtHashState(base.version(), hash, map);
    }
}
