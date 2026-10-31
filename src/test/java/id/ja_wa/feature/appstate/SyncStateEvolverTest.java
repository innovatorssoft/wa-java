package id.ja_wa.feature.appstate;

import id.ja_wa.proto.Wa;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Increment 5: LtHash state evolution from decoded sync data (Baileys decodeSyncdSnapshot/decodePatches). */
class SyncStateEvolverTest {

    private static AppStateProcessor.Mutation mutation(Wa.SyncdMutation.SyncdOperation op, byte index, byte value) {
        byte[] indexMac = new byte[32];
        indexMac[0] = index;
        byte[] valueMac = new byte[32];
        valueMac[0] = value;
        return new AppStateProcessor.Mutation(op, Wa.SyncActionData.newBuilder().build(), indexMac, valueMac);
    }

    private static byte[] mac(byte first) {
        byte[] m = new byte[32];
        m[0] = first;
        return m;
    }

    @Test
    void snapshotEvolvesFromFreshWithSnapshotVersion() {
        Wa.SyncdSnapshot snapshot = Wa.SyncdSnapshot.newBuilder()
            .setVersion(Wa.SyncdVersion.newBuilder().setVersion(5))
            .addRecords(Wa.SyncdRecord.newBuilder().build())
            .addRecords(Wa.SyncdRecord.newBuilder().build())
            .build();
        LtHashState evolved = SyncStateEvolver.evolveFromSnapshot(snapshot,
            List.of(mutation(Wa.SyncdMutation.SyncdOperation.SET, (byte) 1, (byte) 11),
                mutation(Wa.SyncdMutation.SyncdOperation.SET, (byte) 2, (byte) 22)));
        assertThat(evolved).isNotNull();
        assertThat(evolved.version()).isEqualTo(5L);
        assertThat(evolved.indexValueMap()).hasSize(2);
        byte[] expectedHash = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(
            new byte[128], new byte[0][], new byte[][]{mac((byte) 11), mac((byte) 22)});
        assertThat(evolved.hash()).isEqualTo(expectedHash);
    }

    @Test
    void snapshotWithoutVersionOrWithSkippedRecordReturnsNull() {
        Wa.SyncdSnapshot noVersion = Wa.SyncdSnapshot.newBuilder()
            .addRecords(Wa.SyncdRecord.newBuilder().build()).build();
        assertThat(SyncStateEvolver.evolveFromSnapshot(noVersion,
            List.of(mutation(Wa.SyncdMutation.SyncdOperation.SET, (byte) 1, (byte) 11)))).isNull();

        Wa.SyncdSnapshot twoRecords = Wa.SyncdSnapshot.newBuilder()
            .setVersion(Wa.SyncdVersion.newBuilder().setVersion(5))
            .addRecords(Wa.SyncdRecord.newBuilder().build())
            .addRecords(Wa.SyncdRecord.newBuilder().build()).build();
        // One record failed to decode: do not persist a half-evolved state.
        assertThat(SyncStateEvolver.evolveFromSnapshot(twoRecords,
            List.of(mutation(Wa.SyncdMutation.SyncdOperation.SET, (byte) 1, (byte) 11)))).isNull();
    }

    @Test
    void patchesEvolveFromStoredStateWithSetAndRemove() {
        byte[] idxA = mac((byte) 1);
        byte[] valA = mac((byte) 11);
        LtHashState stored = new LtHashState(5L, new byte[128], Map.of(LtHashState.mapKey(idxA), valA));
        Wa.SyncdPatch patch = Wa.SyncdPatch.newBuilder()
            .setVersion(Wa.SyncdVersion.newBuilder().setVersion(6))
            .addMutations(Wa.SyncdMutation.newBuilder().build())
            .addMutations(Wa.SyncdMutation.newBuilder().build())
            .build();
        LtHashState evolved = SyncStateEvolver.evolveFromPatches(stored, List.of(patch),
            List.of(List.of(
                mutation(Wa.SyncdMutation.SyncdOperation.SET, (byte) 2, (byte) 22),
                mutation(Wa.SyncdMutation.SyncdOperation.REMOVE, (byte) 1, (byte) 99))),
            null);
        assertThat(evolved).isNotNull();
        assertThat(evolved.version()).isEqualTo(6L);
        // SET added index 2; REMOVE dropped index 1 (decode path does not re-add it).
        assertThat(evolved.indexValueMap()).containsOnlyKeys(LtHashState.mapKey(mac((byte) 2)));
        byte[] expectedHash = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(
            new byte[128], new byte[][]{valA}, new byte[][]{mac((byte) 22)});
        assertThat(evolved.hash()).isEqualTo(expectedHash);
    }

    @Test
    void patchVersionFallsBackToCollectionVersionPlusOne() {
        Wa.SyncdPatch patch = Wa.SyncdPatch.newBuilder()
            .addMutations(Wa.SyncdMutation.newBuilder().build()).build();
        LtHashState evolved = SyncStateEvolver.evolveFromPatches(LtHashState.fresh(), List.of(patch),
            List.of(List.of(mutation(Wa.SyncdMutation.SyncdOperation.SET, (byte) 3, (byte) 33))),
            9L);
        assertThat(evolved).isNotNull();
        assertThat(evolved.version()).isEqualTo(10L);
        // No proto version and no collection fallback: no trustworthy version.
        assertThat(SyncStateEvolver.evolveFromPatches(LtHashState.fresh(), List.of(patch),
            List.of(List.of(mutation(Wa.SyncdMutation.SyncdOperation.SET, (byte) 3, (byte) 33))),
            null)).isNull();
    }

    @Test
    void patchWithSkippedMutationOrNullBaseReturnsNull() {
        Wa.SyncdPatch patch = Wa.SyncdPatch.newBuilder()
            .setVersion(Wa.SyncdVersion.newBuilder().setVersion(6))
            .addMutations(Wa.SyncdMutation.newBuilder().build())
            .addMutations(Wa.SyncdMutation.newBuilder().build()).build();
        assertThat(SyncStateEvolver.evolveFromPatches(LtHashState.fresh(), List.of(patch),
            List.of(List.of(mutation(Wa.SyncdMutation.SyncdOperation.SET, (byte) 3, (byte) 33))),
            null)).isNull();
        assertThat(SyncStateEvolver.evolveFromPatches(null, List.of(patch),
            List.of(List.of(
                mutation(Wa.SyncdMutation.SyncdOperation.SET, (byte) 3, (byte) 33),
                mutation(Wa.SyncdMutation.SyncdOperation.SET, (byte) 4, (byte) 44))),
            null)).isNull();
    }
}
