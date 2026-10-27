package id.jawa.feature.appstate;

import id.jawa.proto.Wa;
import id.jawa.protocol.crypto.HmacSha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppPatchEncoderTest {

    private static final byte[] KEY_ID = "mykeyid1234567890".getBytes(StandardCharsets.UTF_8);

    private static AppStateKey testKey() {
        byte[] keyData = new byte[32];
        for (int i = 0; i < 32; i++) keyData[i] = (byte) i;
        return new AppStateKey(KEY_ID, keyData);
    }

    private static AppStateProcessor processorWithKey(Path dir, AppStateKey key) {
        FileAppStateKeyStorage storage = new FileAppStateKeyStorage(dir);
        storage.put(key);
        return new AppStateProcessor(storage);
    }

    @Test
    void encodeDecodeRoundTripWithMacValidation(@TempDir Path dir) {
        AppStateKey key = testKey();
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildAddOrEditContact(
            "111@s.whatsapp.net", "Jane Doe", "Jane", "jane", "222@lid", "111@s.whatsapp.net");
        AppPatchEncoder.EncodedPatch encoded = AppPatchEncoder.encode(patch, key, LtHashState.fresh());

        assertThat(encoded.state().version()).isEqualTo(1L);
        assertThat(encoded.patch().getMutationsCount()).isEqualTo(1);
        Wa.SyncdMutation mutation = encoded.patch().getMutations(0);
        assertThat(mutation.getOperation()).isEqualTo(Wa.SyncdMutation.SyncdOperation.SET);
        assertThat(mutation.getRecord().getKeyId().getId().toByteArray()).isEqualTo(KEY_ID);
        assertThat(encoded.patch().getKeyId().getId().toByteArray()).isEqualTo(KEY_ID);
        assertThat(encoded.patch().getPatchMac().toByteArray()).hasSize(32);
        assertThat(encoded.patch().getSnapshotMac().toByteArray()).hasSize(32);
        // Baileys does not set the proto version field on the patch.
        assertThat(encoded.patch().hasVersion()).isFalse();

        AppStateProcessor.Mutation decoded =
            processorWithKey(dir, key).decode(mutation, true);
        assertThat(decoded.operation()).isEqualTo(Wa.SyncdMutation.SyncdOperation.SET);
        assertThat(new String(decoded.action().getIndex().toByteArray(), StandardCharsets.UTF_8))
            .isEqualTo("[\"contact\",\"111@s.whatsapp.net\"]");
        assertThat(decoded.action().getValue().getContactAction().getFullName()).isEqualTo("Jane Doe");
        assertThat(decoded.action().getValue().getContactAction().getLidJid()).isEqualTo("222@lid");
        assertThat(decoded.action().getVersion()).isEqualTo(2);
        assertThat(decoded.action().getPadding().toByteArray()).isEmpty();
        assertThat(decoded.indexMac()).isEqualTo(encoded.indexMac());
        assertThat(decoded.valueMac()).isEqualTo(encoded.valueMac());
    }

    @Test
    void tamperedValueBlobFailsMacValidation(@TempDir Path dir) {
        AppStateKey key = testKey();
        AppPatchEncoder.EncodedPatch encoded = AppPatchEncoder.encode(
            AppPatchBuilder.buildLabelEdit("42", "Work", 3, null, false), key, LtHashState.fresh());
        Wa.SyncdMutation mutation = encoded.patch().getMutations(0);
        byte[] blob = mutation.getRecord().getValue().getBlob().toByteArray();
        blob[20] ^= 0x01;
        Wa.SyncdMutation tampered = Wa.SyncdMutation.newBuilder(mutation)
            .setRecord(Wa.SyncdRecord.newBuilder(mutation.getRecord())
                .setValue(Wa.SyncdValue.newBuilder().setBlob(com.google.protobuf.ByteString.copyFrom(blob))))
            .build();
        assertThatThrownBy(() -> processorWithKey(dir, key).decode(tampered, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MAC mismatch");
    }

    @Test
    void versionIncrementsAndHashChangesAcrossPatches() {
        AppStateKey key = testKey();
        AppPatchEncoder.EncodedPatch first = AppPatchEncoder.encode(
            AppPatchBuilder.buildAddOrEditContact("111@s.whatsapp.net", "Jane", null, null, null, null),
            key, LtHashState.fresh());
        AppPatchEncoder.EncodedPatch second = AppPatchEncoder.encode(
            AppPatchBuilder.buildLabelEdit("42", "Work", 3, null, false), key, first.state());
        assertThat(first.state().version()).isEqualTo(1L);
        assertThat(second.state().version()).isEqualTo(2L);
        assertThat(second.state().hash()).isNotEqualTo(new byte[128]);
        assertThat(second.state().hash()).isNotEqualTo(first.state().hash());
        assertThat(second.patch().getSnapshotMac()).isNotEqualTo(first.patch().getSnapshotMac());
    }

    @Test
    void setThenRemoveSameIndexReturnsHashToZero() {
        AppStateKey key = testKey();
        AppPatchBuilder.AppPatch set = AppPatchBuilder.buildChatLabel("111@s.whatsapp.net", "42", true);
        AppPatchEncoder.EncodedPatch afterSet = AppPatchEncoder.encode(set, key, LtHashState.fresh());
        // REMOVE of the same index: the builder for "remove" is the same
        // shape with labeled=false is NOT a REMOVE op, so build a REMOVE
        // patch directly to exercise the operation path.
        AppPatchBuilder.AppPatch remove = new AppPatchBuilder.AppPatch(
            set.index(), set.syncAction(), set.type(), set.apiVersion(), AppPatchBuilder.Operation.REMOVE);
        AppPatchEncoder.EncodedPatch afterRemove = AppPatchEncoder.encode(remove, key, afterSet.state());
        assertThat(afterRemove.state().version()).isEqualTo(2L);
        assertThat(afterRemove.state().hash()).isEqualTo(new byte[128]);
        assertThat(afterRemove.patch().getMutations(0).getOperation())
            .isEqualTo(Wa.SyncdMutation.SyncdOperation.REMOVE);
    }

    @Test
    void removeWithNoPreviousMappingMixesNothingButStillIncrements() {
        AppStateKey key = testKey();
        AppPatchBuilder.AppPatch remove = AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net");
        AppPatchEncoder.EncodedPatch encoded = AppPatchEncoder.encode(remove, key, LtHashState.fresh());
        assertThat(encoded.state().version()).isEqualTo(1L);
        assertThat(encoded.state().hash()).isEqualTo(new byte[128]);
        // Baileys quirk, mirrored: the index is (re-)added to the map even for REMOVE.
        assertThat(encoded.state().indexValueMap()).containsKey(LtHashState.mapKey(encoded.indexMac()));
    }

    @Test
    void snapshotAndPatchMacsMatchBaileysFormulas() {
        AppStateKey key = testKey();
        AppPatchEncoder.EncodedPatch encoded = AppPatchEncoder.encode(
            AppPatchBuilder.buildLabelEdit("42", "Work", 3, null, false), key, LtHashState.fresh());
        AppStateKey.Expanded ek = key.expand();
        byte[] type = "regular".getBytes(StandardCharsets.UTF_8);
        byte[] expectedSnapshot = HmacSha256.sign(ek.snapshotMac(), encoded.state().hash(),
            AppPatchEncoder.uint64Be(1L), type);
        byte[] expectedPatch = HmacSha256.sign(ek.patchMac(), expectedSnapshot, encoded.valueMac(),
            AppPatchEncoder.uint64Be(1L), type);
        assertThat(encoded.snapshotMac()).isEqualTo(expectedSnapshot);
        assertThat(encoded.patchMac()).isEqualTo(expectedPatch);
    }

    @Test
    void missingKeyFails() {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net");
        assertThatThrownBy(() -> AppPatchEncoder.encode(patch, null, LtHashState.fresh()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AppPatchEncoder.encode(patch, new AppStateKey(KEY_ID, null), LtHashState.fresh()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AppPatchEncoder.encode(patch, new AppStateKey(new byte[0], new byte[32]),
            LtHashState.fresh()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedPatchFails() {
        AppStateKey key = testKey();
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setContactAction(Wa.SyncActionValue.ContactAction.newBuilder()).build();
        AppPatchBuilder.AppPatch emptyIndex = new AppPatchBuilder.AppPatch(
            List.of(), value, "regular", 3, AppPatchBuilder.Operation.SET);
        assertThatThrownBy(() -> AppPatchEncoder.encode(emptyIndex, key, LtHashState.fresh()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppPatchEncoder.encode(null, key, LtHashState.fresh()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppPatchEncoder.encode(
            AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net"), key, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indexJsonMatchesJsonStringifyEscaping() {
        assertThat(AppPatchEncoder.indexJson(List.of("contact", "111@s.whatsapp.net")))
            .isEqualTo("[\"contact\",\"111@s.whatsapp.net\"]");
        assertThat(AppPatchEncoder.indexJson(List.of("a\"b", "c\\d", "e\nf")))
            .isEqualTo("[\"a\\\"b\",\"c\\\\d\",\"e\\nf\"]");
    }
}
