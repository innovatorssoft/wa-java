package id.ja_wa.protocol.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * AES-GCM (Galois/Counter Mode) authenticated encryption and decryption utility.
 * <p>
 * Standardizes 128-bit authentication tag verification and WhatsApp Noise protocol IV generation.
 */
public final class AesGcm {

    private static final int TAG_BITS = 128;

    private AesGcm() {}

    /**
     * Encrypts plaintext using AES-GCM with associated authenticated data (AAD).
     *
     * @param key the symmetric encryption key
     * @param iv the 12-byte initialization vector / nonce
     * @param plaintext the plaintext bytes to encrypt
     * @param aad optional additional authenticated data
     * @return the resulting ciphertext with appended 128-bit authentication tag
     */
    public static byte[] encrypt(byte[] key, byte[] iv, byte[] plaintext, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return c.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    /**
     * Decrypts ciphertext using AES-GCM and verifies the authentication tag against AAD.
     *
     * @param key the symmetric encryption key
     * @param iv the 12-byte initialization vector / nonce
     * @param ciphertext the ciphertext bytes containing the trailing authentication tag
     * @param aad optional additional authenticated data
     * @return the decrypted plaintext bytes
     */
    public static byte[] decrypt(byte[] key, byte[] iv, byte[] ciphertext, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return c.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    /**
     * Formats a 12-byte big-endian initialization vector for WhatsApp Noise Protocol frames
     * using the 64-bit transmission counter.
     *
     * @param counter the incremental sequence counter
     * @return a 12-byte zero-padded IV
     */
    public static byte[] noiseIv(long counter) {
        byte[] iv = new byte[12];
        iv[8]  = (byte) ((counter >> 24) & 0xFF);
        iv[9]  = (byte) ((counter >> 16) & 0xFF);
        iv[10] = (byte) ((counter >> 8)  & 0xFF);
        iv[11] = (byte) ( counter        & 0xFF);
        return iv;
    }
}
