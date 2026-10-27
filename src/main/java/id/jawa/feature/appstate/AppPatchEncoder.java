package id.jawa.feature.appstate;

import com.google.protobuf.ByteString;
import id.jawa.proto.Wa;
import id.jawa.protocol.crypto.AesCbc;
import id.jawa.protocol.crypto.HmacSha256;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Pure encoder for outbound app-state patches — the inverse of
 * {@link AppStateProcessor} (decode) and the Java mirror of Baileys'
 * {@code encodeSyncdPatch} in {@code lib/Utils/chat-utils.js:109}.
 *
 * <p>Given an {@link AppPatchBuilder.AppPatch}, the app-state-sync key
 * and the collection's current {@link LtHashState}, it produces the
 * {@code SyncdPatch} proto Baileys sends inside a
 * {@code w:sync:app:state} IQ, plus the new state. No network access,
 * no key-store access, no mutation of the input state: the returned
 * state is a new object. Unit-testable without a server; the round-trip
 * test encodes here and decodes with {@link AppStateProcessor} with
 * MAC validation on.
 *
 * <p>Formulae, each checked against Baileys (and, where the decoder
 * already implements it, against {@link AppStateProcessor}):
 * <ul>
 *   <li>Index bytes: {@code JSON.stringify(index)} (UTF-8), i.e. a JSON
 *       array of strings with no spaces — {@link #indexJson(List)}.</li>
 *   <li>{@code SyncActionData{index, value: syncAction, padding: empty,
 *       version: apiVersion}} — {@code SyncActionData} proto fields 1-4.</li>
 *   <li>Encryption: AES-256-CBC with a fresh random 16-byte IV, IV
 *       prepended to the ciphertext (Baileys {@code aesEncrypt} in
 *       {@code lib/Utils/crypto.js}); the key is the expanded
 *       {@code valueEncryptionKey} from {@link AppStateKey#expand()}.</li>
 *   <li>Value MAC: HMAC-SHA512, truncated to 32 bytes, over
 *       {@code opByte || keyId || encValue || uint64BE(keyId.length + 1)}
 *       where {@code opByte} is 0x01 for SET and 0x02 for REMOVE
 *       (Baileys {@code generateMac}; identical to
 *       {@code AppStateProcessor.generateContentMac}, which uses
 *       {@code protoNumber + 1}).</li>
 *   <li>Index MAC: HMAC-SHA256 of the index bytes under
 *       {@code indexKey} (Baileys {@code hmacSign} default sha256).</li>
 *   <li>LtHash mix ({@code makeLtHashGenerator}): a SET adds the new
 *       value MAC and, if the index was already mapped, subtracts the
 *       previous value MAC; a REMOVE with no previous mapping mixes
 *       nothing (Baileys logs and skips it); a REMOVE with a previous
 *       mapping subtracts it. The hash update uses
 *       {@link LtHash#WA_PATCH_INTEGRITY}.</li>
 *   <li>Version increments by 1 <em>after</em> the LtHash mix.</li>
 *   <li>Snapshot MAC: HMAC-SHA256 over
 *       {@code hash || uint64BE(version) || type} under
 *       {@code snapshotMacKey} (Baileys {@code generateSnapshotMac}).</li>
 *   <li>Patch MAC: HMAC-SHA256 over
 *       {@code snapshotMac || valueMac || uint64BE(version) || type}
 *       under {@code patchMacKey} (Baileys {@code generatePatchMac}).</li>
 *   <li>{@code SyncdPatch{patchMac, snapshotMac, keyId, mutations:
 *       [{operation, record: {index: {blob: indexMac}, value: {blob:
 *       encValue || valueMac}, keyId}}]}} — note Baileys does
 *       <em>not</em> set the proto {@code version} field on the patch;
 *       neither does this encoder.</li>
 * </ul>
 *
 * <p>One Baileys quirk is mirrored deliberately, not fixed: after the
 * LtHash finish, {@code encodeSyncdPatch} unconditionally writes
 * {@code state.indexValueMap[base64(indexMac)] = {valueMac}} — even for
 * a REMOVE, whose mix just deleted that entry. A subsequent REMOVE of
 * the same index would therefore subtract the REMOVE's own value MAC
 * rather than finding no previous mapping. Changing that would diverge
 * from Baileys' state evolution, so it is preserved and flagged here.
 *
 * <p>Not re-verified from source (stated, not guessed): the key
 * expansion itself. {@link AppStateKey#expand()} derives 160 bytes via
 * HKDF-SHA256 with info {@code "WhatsApp Mutation Keys"} and splits
 * them index / valueEnc / valueMac / snapshotMac / patchMac. Baileys
 * delegates that expansion to the {@code whatsapp-rust-bridge} WASM
 * module ({@code expandAppStateKeys}), whose source is not in the
 * Baileys tree, so the split order cannot be re-checked against JS
 * source here. It is inherited from wa-java's existing
 * {@code AppStateKey} (which the decoder already relies on), and the
 * encoder/decoder round-trip in tests proves self-consistency, not
 * wire-compatibility with a real server.
 */
public final class AppPatchEncoder {

    private static final SecureRandom RANDOM = new SecureRandom();

    private AppPatchEncoder() {}

    /** Result of one encoding: the patch proto and the new state. */
    public record EncodedPatch(
        Wa.SyncdPatch patch,
        LtHashState state,
        byte[] indexMac,
        byte[] valueMac,
        byte[] snapshotMac,
        byte[] patchMac
    ) {
        public EncodedPatch {
            if (patch == null) throw new IllegalArgumentException("patch must not be null");
            if (state == null) throw new IllegalArgumentException("state must not be null");
            indexMac = copy(indexMac);
            valueMac = copy(valueMac);
            snapshotMac = copy(snapshotMac);
            patchMac = copy(patchMac);
        }

        private static byte[] copy(byte[] b) {
            return b == null ? null : b.clone();
        }
    }

    /**
     * Encodes one patch.
     *
     * @param patch patch model from {@link AppPatchBuilder}; its
     *              {@code operation} and {@code apiVersion} are used as-is
     * @param key app-state-sync key for {@code myAppStateKeyId}; a null
     *            key, or a key with no id / no key data, fails with
     *            {@link IllegalStateException}, mirroring Baileys'
     *            missing-key error in {@code encodeSyncdPatch}
     * @param state current LtHash state for the patch's collection
     * @return the {@code SyncdPatch} proto, the new state, and the MACs
     * @throws IllegalArgumentException for a null patch/state or a
     *         malformed patch (empty index, null index element)
     * @throws IllegalStateException for a missing/incomplete key
     */
    public static EncodedPatch encode(AppPatchBuilder.AppPatch patch, AppStateKey key, LtHashState state) {
        if (patch == null) throw new IllegalArgumentException("patch must not be null");
        if (state == null) throw new IllegalArgumentException("state must not be null");
        if (key == null || key.keyId() == null || key.keyId().length == 0 || key.keyData() == null) {
            throw new IllegalStateException("app-state key not present");
        }
        if (patch.index().isEmpty()) {
            throw new IllegalArgumentException("patch index must not be empty");
        }
        for (String element : patch.index()) {
            if (element == null) throw new IllegalArgumentException("patch index elements must not be null");
        }

        byte[] keyId = key.keyId().clone();
        AppStateKey.Expanded ek = key.expand();

        byte[] indexBytes = indexJson(patch.index()).getBytes(StandardCharsets.UTF_8);
        Wa.SyncActionData data = Wa.SyncActionData.newBuilder()
            .setIndex(ByteString.copyFrom(indexBytes))
            .setValue(patch.syncAction())
            .setPadding(ByteString.EMPTY)
            .setVersion(patch.apiVersion())
            .build();
        byte[] encoded = data.toByteArray();

        byte[] iv = new byte[16];
        RANDOM.nextBytes(iv);
        byte[] ciphertext = AesCbc.encrypt(ek.valueEnc(), iv, encoded);
        byte[] encValue = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, encValue, 0, iv.length);
        System.arraycopy(ciphertext, 0, encValue, iv.length, ciphertext.length);

        Wa.SyncdMutation.SyncdOperation protoOp = patch.operation() == AppPatchBuilder.Operation.REMOVE
            ? Wa.SyncdMutation.SyncdOperation.REMOVE
            : Wa.SyncdMutation.SyncdOperation.SET;
        byte[] valueMac = generateMac(protoOp, encValue, keyId, ek.valueMac());
        byte[] indexMac = HmacSha256.sign(ek.index(), indexBytes);

        // LtHash mix, mirroring makeLtHashGenerator.mix.
        Map<String, byte[]> map = state.mutableIndexValueMap();
        String mapKey = LtHashState.mapKey(indexMac);
        byte[] prev = map.get(mapKey);
        List<byte[]> subtract = new ArrayList<>();
        List<byte[]> add = new ArrayList<>();
        if (protoOp == Wa.SyncdMutation.SyncdOperation.REMOVE) {
            if (prev != null) {
                map.remove(mapKey);
                subtract.add(prev);
            }
            // No previous mapping: Baileys skips the mix entirely.
        } else {
            add.add(valueMac);
            map.put(mapKey, valueMac.clone());
            if (prev != null) subtract.add(prev);
        }
        byte[] newHash = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(
            state.hash(), subtract.toArray(new byte[0][]), add.toArray(new byte[0][]));
        long newVersion = state.version() + 1;
        // Baileys quirk (see class Javadoc): unconditional re-add, even for REMOVE.
        map.put(mapKey, valueMac.clone());
        LtHashState newState = new LtHashState(newVersion, newHash, map);

        byte[] typeBytes = patch.type().getBytes(StandardCharsets.UTF_8);
        byte[] snapshotMac = HmacSha256.sign(ek.snapshotMac(), newHash, uint64Be(newVersion), typeBytes);
        byte[] patchMac = HmacSha256.sign(ek.patchMac(), snapshotMac, valueMac, uint64Be(newVersion), typeBytes);

        byte[] valueBlob = new byte[encValue.length + valueMac.length];
        System.arraycopy(encValue, 0, valueBlob, 0, encValue.length);
        System.arraycopy(valueMac, 0, valueBlob, encValue.length, valueMac.length);

        Wa.SyncdPatch syncdPatch = Wa.SyncdPatch.newBuilder()
            .setPatchMac(ByteString.copyFrom(patchMac))
            .setSnapshotMac(ByteString.copyFrom(snapshotMac))
            .setKeyId(Wa.KeyId.newBuilder().setId(ByteString.copyFrom(keyId)))
            .addMutations(Wa.SyncdMutation.newBuilder()
                .setOperation(protoOp)
                .setRecord(Wa.SyncdRecord.newBuilder()
                    .setIndex(Wa.SyncdIndex.newBuilder().setBlob(ByteString.copyFrom(indexMac)))
                    .setValue(Wa.SyncdValue.newBuilder().setBlob(ByteString.copyFrom(valueBlob)))
                    .setKeyId(Wa.KeyId.newBuilder().setId(ByteString.copyFrom(keyId)))
                    .build())
                .build())
            .build();

        return new EncodedPatch(syncdPatch, newState, indexMac, valueMac, snapshotMac, patchMac);
    }

    /** {@code JSON.stringify} of a string array: no spaces, JS escaping. */
    static String indexJson(List<String> index) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < index.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"');
            for (char c : index.get(i).toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * Baileys {@code generateMac}: HMAC-SHA512 over
     * {@code opByte || keyId || data || uint64BE(keyId.length + 1)},
     * truncated to 32 bytes. Identical to the decoder's
     * {@code generateContentMac} formula.
     */
    static byte[] generateMac(Wa.SyncdMutation.SyncdOperation op, byte[] data, byte[] keyId, byte[] macKey) {
        byte[] operationBytes = new byte[]{(byte) (op.getNumber() + 1)};
        byte[] keyDataLen = uint64Be(keyId.length + 1);
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(macKey, "HmacSHA512"));
            mac.update(operationBytes);
            mac.update(keyId);
            mac.update(data);
            mac.update(keyDataLen);
            return Arrays.copyOfRange(mac.doFinal(), 0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA512 init failed", e);
        }
    }

    static byte[] uint64Be(long value) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return out;
    }
}
