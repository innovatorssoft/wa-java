package id.jawa.feature.media;


import id.jawa.protocol.crypto.AesCbc;
import id.jawa.protocol.crypto.Hkdf;
import id.jawa.protocol.crypto.HmacSha256;
import id.jawa.protocol.crypto.Sha256;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class MediaCrypto {

    private MediaCrypto() {}

    public enum MediaType {
        IMAGE("WhatsApp Image Keys"),
        VIDEO("WhatsApp Video Keys"),
        AUDIO("WhatsApp Audio Keys"),
        DOCUMENT("WhatsApp Document Keys"),
        /**
         * History-sync file ({@code md-msg-hist}). Baileys'
         * {@code MEDIA_HKDF_KEY_MAPPING['md-msg-hist'] = 'History'}
         * ({@code lib/Defaults/index.js}) combined with
         * {@code hkdfInfoKey} ({@code lib/Utils/messages-media.js}:
         * {@code `WhatsApp ${mapping} Keys`}) gives this info string.
         * Download-only: history files are never uploaded, so this
         * type is deliberately absent from {@code MediaUploader}'s
         * MMS path map.
         */
        HISTORY("WhatsApp History Keys");

        private final byte[] info;

        MediaType(String info) {
            this.info = info.getBytes(StandardCharsets.UTF_8);
        }

        public byte[] info() { return info.clone(); }
    }

    public record ExpandedKey(byte[] iv, byte[] cipherKey, byte[] macKey, byte[] refKey) {}

    public static ExpandedKey expandKey(byte[] mediaKey, MediaType type) {
        if (mediaKey.length != 32) {
            throw new IllegalArgumentException("mediaKey must be 32 bytes, got " + mediaKey.length);
        }
        byte[] expanded = Hkdf.derive(mediaKey, new byte[0], type.info(), 112);
        return new ExpandedKey(
            Arrays.copyOfRange(expanded, 0, 16),
            Arrays.copyOfRange(expanded, 16, 48),
            Arrays.copyOfRange(expanded, 48, 80),
            Arrays.copyOfRange(expanded, 80, 112)
        );
    }

    public record EncryptedMedia(
        byte[] ciphertext,
        byte[] mac,
        byte[] fileSha256,
        byte[] fileEncSha256
    ) {

        public byte[] uploadBytes() {
            byte[] out = new byte[ciphertext.length + mac.length];
            System.arraycopy(ciphertext, 0, out, 0, ciphertext.length);
            System.arraycopy(mac, 0, out, ciphertext.length, mac.length);
            return out;
        }
    }

    public static EncryptedMedia encrypt(byte[] plaintext, byte[] mediaKey, MediaType type) {
        ExpandedKey k = expandKey(mediaKey, type);
        byte[] ciphertext = AesCbc.encrypt(k.cipherKey(), k.iv(), plaintext);

        byte[] macInput = new byte[k.iv().length + ciphertext.length];
        System.arraycopy(k.iv(), 0, macInput, 0, k.iv().length);
        System.arraycopy(ciphertext, 0, macInput, k.iv().length, ciphertext.length);
        byte[] fullMac = HmacSha256.sign(k.macKey(), macInput);
        byte[] mac10 = Arrays.copyOfRange(fullMac, 0, 10);

        byte[] uploadBytes = new byte[ciphertext.length + 10];
        System.arraycopy(ciphertext, 0, uploadBytes, 0, ciphertext.length);
        System.arraycopy(mac10, 0, uploadBytes, ciphertext.length, 10);

        return new EncryptedMedia(
            ciphertext,
            mac10,
            Sha256.hash(plaintext),
            Sha256.hash(uploadBytes)
        );
    }

    public static byte[] decrypt(byte[] ciphertextAndMac, byte[] mediaKey, MediaType type) {
        if (ciphertextAndMac.length < 10) {
            throw new IllegalArgumentException("payload too short for MAC");
        }
        ExpandedKey k = expandKey(mediaKey, type);

        int ctLen = ciphertextAndMac.length - 10;
        byte[] ciphertext = Arrays.copyOfRange(ciphertextAndMac, 0, ctLen);
        byte[] receivedMac = Arrays.copyOfRange(ciphertextAndMac, ctLen, ctLen + 10);

        byte[] macInput = new byte[k.iv().length + ciphertext.length];
        System.arraycopy(k.iv(), 0, macInput, 0, k.iv().length);
        System.arraycopy(ciphertext, 0, macInput, k.iv().length, ciphertext.length);
        byte[] fullMac = HmacSha256.sign(k.macKey(), macInput);
        byte[] expectedMac = Arrays.copyOfRange(fullMac, 0, 10);

        if (!constantTimeEquals(expectedMac, receivedMac)) {
            throw new IllegalStateException("media MAC mismatch");
        }
        return AesCbc.decrypt(k.cipherKey(), k.iv(), ciphertext);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
