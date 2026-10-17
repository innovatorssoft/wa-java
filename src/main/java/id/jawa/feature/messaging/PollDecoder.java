package id.jawa.feature.messaging;


import id.jawa.protocol.crypto.AesGcm;
import id.jawa.protocol.crypto.Hkdf;
import id.jawa.protocol.crypto.Sha256;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PollDecoder {

    private static final byte[] USE_CASE = "Poll Vote".getBytes(StandardCharsets.UTF_8);

    private PollDecoder() {}

    public static List<String> decryptVote(byte[] encPayload, byte[] encIv,
                                           byte[] messageSecret,
                                           String pollMsgId,
                                           String pollSenderJid,
                                           String voterJid,
                                           List<String> originalOptions) {
        byte[] idBytes        = pollMsgId.getBytes(StandardCharsets.UTF_8);
        byte[] pollSenderBytes = pollSenderJid.getBytes(StandardCharsets.UTF_8);
        byte[] voterBytes      = voterJid.getBytes(StandardCharsets.UTF_8);

        byte[] info = concat(idBytes, pollSenderBytes, voterBytes, USE_CASE);
        byte[] secretKey = Hkdf.derive(messageSecret, new byte[0], info, 32);

        byte[] additionalData = new byte[idBytes.length + 1 + voterBytes.length];
        System.arraycopy(idBytes,    0, additionalData, 0, idBytes.length);
        additionalData[idBytes.length] = 0;
        System.arraycopy(voterBytes, 0, additionalData, idBytes.length + 1, voterBytes.length);

        byte[] plaintext = AesGcm.decrypt(secretKey, encIv, encPayload, additionalData);
        if (plaintext.length % 32 != 0) {
            throw new IllegalStateException("plaintext length " + plaintext.length + " not a multiple of 32");
        }

        Map<String, String> hashToOption = new HashMap<>();
        for (String opt : originalOptions) {
            hashToOption.put(hex(Sha256.hash(opt.getBytes(StandardCharsets.UTF_8))), opt);
        }

        List<String> picked = new ArrayList<>();
        for (int i = 0; i < plaintext.length; i += 32) {
            String hashHex = hex(Arrays.copyOfRange(plaintext, i, i + 32));
            String opt = hashToOption.get(hashHex);
            if (opt != null) picked.add(opt);
        }
        return picked;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
