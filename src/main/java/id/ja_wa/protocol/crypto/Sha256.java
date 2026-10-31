package id.ja_wa.protocol.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 cryptographic hash function utility supporting multi-part stream hashing.
 */
public final class Sha256 {

    private Sha256() {}

    /**
     * Computes the 32-byte SHA-256 digest of concatenated byte array inputs.
     *
     * @param parts variable array of byte segments to hash
     * @return the 32-byte SHA-256 digest
     */
    public static byte[] hash(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) if (p != null) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
