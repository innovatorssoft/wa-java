package id.jawa.domain.model;


import id.jawa.protocol.crypto.Curve25519;
import id.jawa.protocol.crypto.KeyPair25519;

public record SignedPreKey(int keyId, KeyPair25519 keyPair, byte[] signature) {

    public static SignedPreKey generate(int keyId, KeyPair25519 identityKey) {
        KeyPair25519 pre = Curve25519.generateKeyPair();
        byte[] toSign = Curve25519.prependType(pre.publicKey());
        byte[] sig = Curve25519.sign(identityKey.privateKey(), toSign);
        return new SignedPreKey(keyId, pre, sig);
    }
}
