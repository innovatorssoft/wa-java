package id.jawa.domain.model;

import id.jawa.protocol.crypto.Bytes;

/**
 * Protocol constants, magic headers, noise parameters, and web client version specifications.
 */
public final class WaConstants {

    public static final String ORIGIN = "https://web.whatsapp.com";
    public static final String WS_URL = "wss://web.whatsapp.com/ws/chat";

    public static final byte WA_MAGIC = 6;
    public static final int  DICT_VERSION = 3;

    public static final byte[] WA_HEADER = new byte[] { 'W', 'A', WA_MAGIC, (byte) DICT_VERSION };

    public static final byte[] NOISE_MODE = pad32("Noise_XX_25519_AESGCM_SHA256");

    public static final int FRAME_MAX_SIZE = 1 << 24;
    public static final int FRAME_LENGTH_SIZE = 3;

    public static final int WA_CERT_SERIAL = 0;
    public static final String WA_CERT_ISSUER = "WhatsAppLongTerm1";
    public static final byte[] WA_CERT_PUBLIC_KEY = Bytes.fromHex(
        "142375574d0a587166aae71ebe516437c4a28b73e3695c6ce1f7f9545da8ee6b"
    );

    public static final byte[] ADV_ACCOUNT_SIG_PREFIX        = new byte[] { 6, 0 };
    public static final byte[] ADV_DEVICE_SIG_PREFIX         = new byte[] { 6, 1 };
    public static final byte[] ADV_HOSTED_ACCOUNT_SIG_PREFIX = new byte[] { 6, 5 };
    public static final byte[] ADV_HOSTED_DEVICE_SIG_PREFIX  = new byte[] { 6, 6 };

    public static final int[] WA_VERSION = new int[] { 2, 3000, 1044834443 };

    /**
     * Gets a defensive copy of the current WhatsApp Web protocol version array.
     *
     * @return Clone of the version triplet [primary, secondary, tertiary]
     */
    public static int[] getVersion() {
        return WA_VERSION.clone();
    }

    /**
     * Overrides the WhatsApp Web protocol version reported during noise handshake.
     *
     * @param primary Primary version number
     * @param secondary Secondary version number
     * @param tertiary Tertiary revision number
     */
    public static void setVersion(int primary, int secondary, int tertiary) {
        WA_VERSION[0] = primary;
        WA_VERSION[1] = secondary;
        WA_VERSION[2] = tertiary;
    }

    private WaConstants() {}

    private static byte[] pad32(String s) {
        byte[] raw = Bytes.utf8(s);
        if (raw.length == 32) return raw;
        if (raw.length > 32) throw new IllegalArgumentException("noise pattern too long");
        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 0, raw.length);
        return out;
    }
}
