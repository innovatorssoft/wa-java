package id.jawa.domain.model;

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

import id.jawa.protocol.crypto.Bytes;

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
