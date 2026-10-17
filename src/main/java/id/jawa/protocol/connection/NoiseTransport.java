package id.jawa.protocol.connection;

import id.jawa.protocol.crypto.AesGcm;

/**
 * Encrypted transport state derived following a successful Noise handshake (Noise_XX_25519_AESGCM_SHA256).
 * <p>
 * Manages separate write and read keys with monotonic 64-bit sequence counters for IV generation.
 */
public final class NoiseTransport {

    private final byte[] writeKey;
    private final byte[] readKey;
    private long writeCounter = 0;
    private long readCounter  = 0;

    NoiseTransport(byte[] writeKey, byte[] readKey) {
        if (writeKey.length != 32 || readKey.length != 32) {
            throw new IllegalArgumentException("transport keys must be 32 bytes");
        }
        this.writeKey = writeKey;
        this.readKey  = readKey;
    }

    /**
     * Encrypts an outbound plaintext frame using the write key and current write counter.
     *
     * @param plaintext raw payload bytes to encrypt
     * @return encrypted ciphertext with appended authentication tag
     */
    public synchronized byte[] encrypt(byte[] plaintext) {
        byte[] iv = AesGcm.noiseIv(writeCounter++);
        return AesGcm.encrypt(writeKey, iv, plaintext, null);
    }

    /**
     * Decrypts an inbound ciphertext frame using the read key and current read counter.
     *
     * @param ciphertext incoming encrypted payload bytes
     * @return decrypted plaintext bytes
     */
    public synchronized byte[] decrypt(byte[] ciphertext) {
        byte[] iv = AesGcm.noiseIv(readCounter++);
        return AesGcm.decrypt(readKey, iv, ciphertext, null);
    }

    public long writeCounter() {
        return writeCounter;
    }

    public long readCounter() {
        return readCounter;
    }
}
