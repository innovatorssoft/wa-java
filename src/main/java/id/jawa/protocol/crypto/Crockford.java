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

public final class Crockford {

    public static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTVWXYZ";

    private Crockford() {}

    public static String encode(byte[] data) {
        int value = 0;
        int bits = 0;
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }
}
