package id.ja_wa.protocol.crypto;

import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;

/**
 * Elliptic-curve Diffie-Hellman operations on Curve25519 for WhatsApp Noise handshakes and Signal Protocol.
 * <p>
 * Handles 32-byte raw public keys and standard 33-byte type-prefixed keys (0x05 prefix).
 */
public final class Curve25519 {

    /** Signal protocol type prefix byte (0x05) denoting a Curve25519 public key. */
    public static final byte KEY_BUNDLE_TYPE = 0x05;

    private Curve25519() {}

    /**
     * Generates a new random Curve25519 key pair.
     *
     * @return the generated 32-byte private and public key pair
     */
    public static KeyPair25519 generateKeyPair() {
        ECKeyPair kp = Curve.generateKeyPair();
        byte[] pub33 = kp.getPublicKey().serialize();
        byte[] pub32 = stripType(pub33);
        byte[] priv = kp.getPrivateKey().serialize();
        return new KeyPair25519(priv, pub32);
    }

    /**
     * Computes an ECDH shared secret between a private key and a public key.
     *
     * @param privateKey 32-byte private key
     * @param publicKey 32-byte or 33-byte public key
     * @return the 32-byte shared agreement
     */
    public static byte[] sharedKey(byte[] privateKey, byte[] publicKey) {
        try {
            ECPublicKey pub = Curve.decodePoint(prependType(publicKey), 0);
            ECPrivateKey priv = Curve.decodePrivatePoint(privateKey);
            return Curve.calculateAgreement(pub, priv);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("invalid public key", e);
        }
    }

    /**
     * Signs a message using XEdDSA over Curve25519.
     *
     * @param privateKey 32-byte private key
     * @param message arbitrary message bytes to sign
     * @return the 64-byte signature
     */
    public static byte[] sign(byte[] privateKey, byte[] message) {
        try {
            ECPrivateKey priv = Curve.decodePrivatePoint(privateKey);
            return Curve.calculateSignature(priv, message);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("invalid private key", e);
        }
    }

    /**
     * Verifies an XEdDSA signature over Curve25519.
     *
     * @param publicKey 32-byte or 33-byte public key
     * @param message signed message bytes
     * @param signature 64-byte signature
     * @return true if valid, false otherwise
     */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        try {
            byte[] keyBytes = publicKey.length == 32 ? prependType(publicKey) : publicKey;
            ECPublicKey pub = Curve.decodePoint(keyBytes, 0);
            return Curve.verifySignature(pub, message, signature);
        } catch (InvalidKeyException e) {
            return false;
        }
    }

    /**
     * Prepends the standard 0x05 type byte to a 32-byte raw public key if not already present.
     *
     * @param raw32 32-byte raw public key
     * @return 33-byte prefixed public key
     */
    public static byte[] prependType(byte[] raw32) {
        if (raw32.length == 33 && raw32[0] == KEY_BUNDLE_TYPE) return raw32;
        if (raw32.length != 32) {
            throw new IllegalArgumentException("expected 32 bytes, got " + raw32.length);
        }
        byte[] out = new byte[33];
        out[0] = KEY_BUNDLE_TYPE;
        System.arraycopy(raw32, 0, out, 1, 32);
        return out;
    }

    /**
     * Strips the 0x05 type prefix byte from a 33-byte public key if present.
     *
     * @param prefixed33 33-byte prefixed public key
     * @return 32-byte raw public key
     */
    public static byte[] stripType(byte[] prefixed33) {
        if (prefixed33.length == 32) return prefixed33;
        if (prefixed33.length != 33) {
            throw new IllegalArgumentException("expected 33 bytes, got " + prefixed33.length);
        }
        byte[] out = new byte[32];
        System.arraycopy(prefixed33, 1, out, 0, 32);
        return out;
    }
}
