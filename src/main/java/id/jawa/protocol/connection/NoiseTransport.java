package id.jawa.protocol.connection;


import id.jawa.protocol.crypto.AesGcm;

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

    public synchronized byte[] encrypt(byte[] plaintext) {
        byte[] iv = AesGcm.noiseIv(writeCounter++);
        return AesGcm.encrypt(writeKey, iv, plaintext, null);
    }

    public synchronized byte[] decrypt(byte[] ciphertext) {
        byte[] iv = AesGcm.noiseIv(readCounter++);
        return AesGcm.decrypt(readKey, iv, ciphertext, null);
    }

    public long writeCounter() { return writeCounter; }
    public long readCounter()  { return readCounter; }
}
