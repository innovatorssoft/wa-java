package id.ja_wa.protocol.connection;


import id.ja_wa.domain.model.WaConstants;
import id.ja_wa.protocol.crypto.AesGcm;
import id.ja_wa.protocol.crypto.Bytes;
import id.ja_wa.protocol.crypto.Curve25519;
import id.ja_wa.protocol.crypto.Hkdf;
import id.ja_wa.protocol.crypto.KeyPair25519;
import id.ja_wa.protocol.crypto.Sha256;

import java.util.Arrays;

/**
 * Implements the Noise_XX_25519_AESGCM_SHA256 handshake state machine for WhatsApp Web authentication.
 * <p>
 * Performs mutual key exchange, server certificate verification via {@link CertChainValidator},
 * and derives symmetric transport keys for bidirectional encrypted streaming.
 */
public final class NoiseHandshake {

    private final byte[] ephemeralPriv;

    private byte[] hash;
    private byte[] salt;
    private byte[] encKey;
    private byte[] decKey;
    private long counter;

    private NoiseTransport transport;

    /**
     * Initializes a new Noise handshake state with the client's ephemeral key and connection intro header.
     *
     * @param ephemeral client ephemeral Curve25519 key pair
     * @param introHeader protocol intro bytes
     */
    public NoiseHandshake(KeyPair25519 ephemeral, byte[] introHeader) {
        this.ephemeralPriv = ephemeral.privateKey();
        this.hash = WaConstants.NOISE_MODE.clone();
        this.salt = hash.clone();
        this.encKey = hash.clone();
        this.decKey = hash.clone();
        this.counter = 0;

        mixHash(introHeader);
        mixHash(ephemeral.publicKey());
    }

    public byte[] processServerHello(byte[] serverEphemeral,
                                     byte[] serverStaticCt,
                                     byte[] serverPayloadCt,
                                     KeyPair25519 noiseStaticKey) {
        mixHash(serverEphemeral);
        mixKey(Curve25519.sharedKey(ephemeralPriv, serverEphemeral));

        byte[] serverStaticPub = decrypt(serverStaticCt);
        mixKey(Curve25519.sharedKey(ephemeralPriv, serverStaticPub));

        byte[] certBytes = decrypt(serverPayloadCt);
        CertChainValidator.validate(serverStaticPub, certBytes);

        byte[] encStatic = encrypt(noiseStaticKey.publicKey());
        mixKey(Curve25519.sharedKey(noiseStaticKey.privateKey(), serverEphemeral));

        return encStatic;
    }

    public byte[] encrypt(byte[] plaintext) {
        if (transport != null) return transport.encrypt(plaintext);
        byte[] iv = AesGcm.noiseIv(counter++);
        byte[] ct = AesGcm.encrypt(encKey, iv, plaintext, hash);
        mixHash(ct);
        return ct;
    }

    public byte[] decrypt(byte[] ciphertext) {
        if (transport != null) return transport.decrypt(ciphertext);
        byte[] iv = AesGcm.noiseIv(counter++);
        byte[] pt = AesGcm.decrypt(decKey, iv, ciphertext, hash);
        mixHash(ciphertext);
        return pt;
    }

    public NoiseTransport finish() {
        if (transport != null) return transport;
        byte[] derived = Hkdf.derive(new byte[0], salt, 64);
        byte[] writeKey = Bytes.slice(derived, 0, 32);
        byte[] readKey  = Bytes.slice(derived, 32, 64);
        transport = new NoiseTransport(writeKey, readKey);

        Arrays.fill(salt, (byte) 0);
        Arrays.fill(encKey, (byte) 0);
        Arrays.fill(decKey, (byte) 0);
        return transport;
    }

    public NoiseTransport transport() { return transport; }
    public boolean isFinished() { return transport != null; }

    private void mixHash(byte[] data) {
        hash = Sha256.hash(hash, data);
    }

    private void mixKey(byte[] data) {
        byte[] derived = Hkdf.derive(data, salt, 64);
        salt = Bytes.slice(derived, 0, 32);
        byte[] k = Bytes.slice(derived, 32, 64);
        encKey = k;
        decKey = k;
        counter = 0;
    }
}
