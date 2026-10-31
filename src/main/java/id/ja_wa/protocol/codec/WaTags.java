package id.ja_wa.protocol.codec;

/**
 * Protocol byte tags and control tokens used in WhatsApp binary XML framing.
 */
public final class WaTags {

    public static final int LIST_EMPTY = 0;

    public static final int XML_STREAM_START = 1;
    public static final int XML_STREAM_END = 2;

    public static final int DICTIONARY_0 = 236;
    public static final int DICTIONARY_1 = 237;
    public static final int DICTIONARY_2 = 238;
    public static final int DICTIONARY_3 = 239;

    public static final int INTEROP_JID = 245;
    public static final int FB_JID = 246;
    public static final int AD_JID = 247;
    public static final int JID_PAIR = 250;

    public static final int LIST_8 = 248;
    public static final int LIST_16 = 249;

    public static final int HEX_8 = 251;
    public static final int NIBBLE_8 = 255;
    public static final int PACKED_MAX = 127;

    public static final int BINARY_8 = 252;
    public static final int BINARY_20 = 253;
    public static final int BINARY_32 = 254;

    public static final int FLAG_RAW = 0x00;
    public static final int FLAG_DEFLATE = 0x02;

    private WaTags() {}
}
