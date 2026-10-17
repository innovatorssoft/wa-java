package id.jawa.protocol.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * HMAC-SHA256 keyed-hash message authentication code computation utility.
 */
public final class HmacSha256 {

    private HmacSha256() {}

    /**
     * Computes the 32-byte HMAC-SHA256 signature for the given key and message segments.
     *
     * @param key the symmetric secret key
     * @param messages variable array of message bytes
     * @return the 32-byte HMAC digest
     */
    public static byte[] sign(byte[] key, byte[]... messages) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] m : messages) if (m != null) mac.update(m);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }
}
