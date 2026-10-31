package id.ja_wa.domain.model;

import id.ja_wa.protocol.crypto.Curve25519;
import id.ja_wa.protocol.crypto.KeyPair25519;

/**
 * A cryptographically signed Signal protocol prekey used for asynchronous end-to-end session establishment.
 *
 * @param keyId Unique integer identifier for this signed prekey
 * @param keyPair The ephemeral Curve25519 key pair
 * @param signature Identity key signature verifying prekey authenticity
 */
public record SignedPreKey(int keyId, KeyPair25519 keyPair, byte[] signature) {

    /**
     * Generates a new SignedPreKey signed by the provided identity key.
     *
     * @param keyId Unique key identifier
     * @param identityKey Account Curve25519 identity key pair used for signing
     * @return Newly generated SignedPreKey instance
     */
    public static SignedPreKey generate(int keyId, KeyPair25519 identityKey) {
        KeyPair25519 pre = Curve25519.generateKeyPair();
        byte[] toSign = Curve25519.prependType(pre.publicKey());
        byte[] sig = Curve25519.sign(identityKey.privateKey(), toSign);
        return new SignedPreKey(keyId, pre, sig);
    }
}
