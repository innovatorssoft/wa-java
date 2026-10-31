package id.ja_wa.feature.appstate;


import id.ja_wa.protocol.crypto.Hkdf;

import java.nio.charset.StandardCharsets;

public final class LtHash {

    public static final LtHash WA_PATCH_INTEGRITY = new LtHash(
        "WhatsApp Patch Integrity".getBytes(StandardCharsets.UTF_8), 128);

    private final byte[] hkdfInfo;
    private final int hkdfSize;

    public LtHash(byte[] hkdfInfo, int hkdfSize) {
        this.hkdfInfo = hkdfInfo.clone();
        this.hkdfSize = hkdfSize;
    }

    public byte[] subtractThenAdd(byte[] base, byte[][] subtract, byte[][] add) {
        byte[] out = base.clone();
        subtractThenAddInPlace(out, subtract, add);
        return out;
    }

    public void subtractThenAddInPlace(byte[] base, byte[][] subtract, byte[][] add) {
        for (byte[] item : subtract) {
            pointwise(base, expand(item), true);
        }
        for (byte[] item : add) {
            pointwise(base, expand(item), false);
        }
    }

    private byte[] expand(byte[] item) {
        return Hkdf.derive(item, new byte[0], hkdfInfo, hkdfSize);
    }

    private static void pointwise(byte[] base, byte[] input, boolean subtract) {
        for (int i = 0; i < base.length; i += 2) {
            int x = ((base[i] & 0xFF) | ((base[i + 1] & 0xFF) << 8));
            int y = ((input[i] & 0xFF) | ((input[i + 1] & 0xFF) << 8));
            int result = (subtract ? x - y : x + y) & 0xFFFF;
            base[i]     = (byte) (result & 0xFF);
            base[i + 1] = (byte) ((result >> 8) & 0xFF);
        }
    }
}
