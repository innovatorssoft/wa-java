package id.jawa.protocol.crypto;


import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

public final class Hkdf {
    private Hkdf() {}

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest());
        g.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[length];
        g.generateBytes(out, 0, length);
        return out;
    }

    public static byte[] derive(byte[] ikm, byte[] salt, int length) {
        return derive(ikm, salt, new byte[0], length);
    }
}
