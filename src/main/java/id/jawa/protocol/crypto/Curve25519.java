package id.jawa.protocol.crypto;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;

import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;

public final class Curve25519 {

    public static final byte KEY_BUNDLE_TYPE = 0x05;

    private Curve25519() {}

    public static KeyPair25519 generateKeyPair() {
        ECKeyPair kp = Curve.generateKeyPair();
        byte[] pub33 = kp.getPublicKey().serialize();
        byte[] pub32 = stripType(pub33);
        byte[] priv = kp.getPrivateKey().serialize();
        return new KeyPair25519(priv, pub32);
    }

    public static byte[] sharedKey(byte[] privateKey, byte[] publicKey) {
        try {
            ECPublicKey pub = Curve.decodePoint(prependType(publicKey), 0);
            ECPrivateKey priv = Curve.decodePrivatePoint(privateKey);
            return Curve.calculateAgreement(pub, priv);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("invalid public key", e);
        }
    }

    public static byte[] sign(byte[] privateKey, byte[] message) {
        try {
            ECPrivateKey priv = Curve.decodePrivatePoint(privateKey);
            return Curve.calculateSignature(priv, message);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("invalid private key", e);
        }
    }

    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        try {
            byte[] keyBytes = publicKey.length == 32 ? prependType(publicKey) : publicKey;
            ECPublicKey pub = Curve.decodePoint(keyBytes, 0);
            return Curve.verifySignature(pub, message, signature);
        } catch (InvalidKeyException e) {
            return false;
        }
    }

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
