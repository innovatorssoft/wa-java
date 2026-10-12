package id.jawa.feature.signal;

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

import id.jawa.protocol.codec.BinaryNode;
import id.jawa.domain.model.WaConstants;
import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.model.Jid;
import id.jawa.protocol.crypto.Curve25519;
import id.jawa.protocol.crypto.KeyPair25519;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PreKeyManager {

    private PreKeyManager() {}

    public static Map<Integer, KeyPair25519> generate(AuthCreds creds, SignalKeyStore store, int count) {
        int available = creds.nextPreKeyId - creds.firstUnuploadedPreKeyId;
        int remaining = count - available;
        if (remaining > 0) {
            for (int i = 0; i < remaining; i++) {
                int id = creds.nextPreKeyId + i;
                store.putPreKey(id, Curve25519.generateKeyPair());
            }
            creds.nextPreKeyId += remaining;
        }
        Map<Integer, KeyPair25519> out = new LinkedHashMap<>(count);
        for (int id = creds.firstUnuploadedPreKeyId; id < creds.firstUnuploadedPreKeyId + count; id++) {
            KeyPair25519 kp = store.getPreKey(id);
            if (kp != null) out.put(id, kp);
        }
        return out;
    }

    public static BinaryNode buildUploadStanza(String id, AuthCreds creds, Map<Integer, KeyPair25519> preKeys) {
        java.util.List<BinaryNode> keyList = new java.util.ArrayList<>(preKeys.size());
        for (var e : preKeys.entrySet()) {
            keyList.add(new BinaryNode("key", Map.of(), List.of(
                new BinaryNode("id",    Map.of(), encodeUintBE(e.getKey(), 3)),
                new BinaryNode("value", Map.of(), e.getValue().publicKey())
            )));
        }

        BinaryNode listNode = new BinaryNode("list", Map.of(), keyList);

        BinaryNode skey = new BinaryNode("skey", Map.of(), List.of(
            new BinaryNode("id",        Map.of(), encodeUintBE(creds.signedPreKey.keyId(), 3)),
            new BinaryNode("value",     Map.of(), creds.signedPreKey.keyPair().publicKey()),
            new BinaryNode("signature", Map.of(), creds.signedPreKey.signature())
        ));

        return new BinaryNode("iq",
            Map.of(
                "xmlns", "encrypt",
                "type",  "set",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    id
            ),
            List.of(
                new BinaryNode("registration", Map.of(), encodeUintBE(creds.registrationId, 4)),
                new BinaryNode("type",         Map.of(), new byte[] { Curve25519.KEY_BUNDLE_TYPE }),
                new BinaryNode("identity",     Map.of(), creds.signedIdentityKey.publicKey()),
                listNode,
                skey
            ));
    }

    public static void markUploaded(AuthCreds creds, Map<Integer, KeyPair25519> uploaded) {
        if (uploaded.isEmpty()) return;
        int maxId = 0;
        for (Integer id : uploaded.keySet()) if (id > maxId) maxId = id;
        creds.firstUnuploadedPreKeyId = maxId + 1;
    }

    @SuppressWarnings("unused")
    private static byte[] encodeUintBE(int value, int width) {
        byte[] out = new byte[width];
        for (int i = 0; i < width; i++) {
            out[width - 1 - i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
        return out;
    }
}
