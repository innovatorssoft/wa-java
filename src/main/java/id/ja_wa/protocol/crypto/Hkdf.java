package id.ja_wa.protocol.crypto;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/**
 * HMAC-based Extract-and-Expand Key Derivation Function (HKDF) using SHA-256 (RFC 5869).
 * <p>
 * Widely utilized in WhatsApp media decryption, Noise handshake key schedules, and pairing secrets.
 */
public final class Hkdf {

    private Hkdf() {}

    /**
     * Derives output keying material (OKM) from input keying material (IKM), optional salt, and context info.
     *
     * @param ikm input keying material
     * @param salt optional salt value (if null, a string of zeros is used)
     * @param info optional context and application specific information
     * @param length length of output keying material in bytes
     * @return the derived output keying material
     */
    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest());
        g.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[length];
        g.generateBytes(out, 0, length);
        return out;
    }

    /**
     * Derives output keying material with empty context info.
     *
     * @param ikm input keying material
     * @param salt optional salt value
     * @param length length of output keying material in bytes
     * @return the derived output keying material
     */
    public static byte[] derive(byte[] ikm, byte[] salt, int length) {
        return derive(ikm, salt, new byte[0], length);
    }
}
