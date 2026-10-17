package id.jawa.feature.pairing;


import id.jawa.protocol.codec.BinaryNode;
import id.jawa.domain.model.AuthCreds;
import id.jawa.protocol.crypto.Bytes;
import id.jawa.protocol.crypto.Crockford;
import id.jawa.domain.model.Jid;
import id.jawa.protocol.crypto.AesCtr;
import id.jawa.protocol.crypto.AesGcm;
import id.jawa.protocol.crypto.Curve25519;
import id.jawa.protocol.crypto.Hkdf;
import id.jawa.protocol.crypto.KeyPair25519;
import id.jawa.protocol.crypto.Pbkdf2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public final class PairingCodeHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PairingCodeHandler.class);

    public static final int PBKDF2_ITERATIONS = 2 << 16;

    public static final String COMPANION_PLATFORM_ID = "1";

    private final AuthCreds creds;
    private String pairingCode;
    private KeyPair25519 pairingEphemeralKey;

    public PairingCodeHandler(AuthCreds creds) { this.creds = creds; }

    public String pairingCode() { return pairingCode; }

    public BinaryNode buildCompanionHello(String iqId, String phoneNumber, String customCode) {
        if (customCode != null && customCode.length() != 8) {
            throw new IllegalArgumentException("custom pairing code must be exactly 8 chars");
        }
        pairingCode = customCode != null ? customCode : Crockford.encode(Bytes.random(5));
        pairingEphemeralKey = Curve25519.generateKeyPair();
        creds.meJid = phoneNumber + "@" + Jid.SERVER_WHATSAPP;

        byte[] wrapped = wrapEphemeralPub(pairingCode, pairingEphemeralKey.publicKey());

        BinaryNode reg = new BinaryNode("link_code_companion_reg",
            Map.of(
                "jid",   creds.meJid,
                "stage", "companion_hello",
                "should_show_push_notification", "true"
            ),
            List.of(
                new BinaryNode("link_code_pairing_wrapped_companion_ephemeral_pub", Map.of(), wrapped),
                new BinaryNode("companion_server_auth_key_pub", Map.of(), creds.noiseKey.publicKey()),

                new BinaryNode("companion_platform_id",      Map.of(), COMPANION_PLATFORM_ID),

                new BinaryNode("companion_platform_display", Map.of(), "Chrome (Linux)"),
                new BinaryNode("link_code_pairing_nonce",    Map.of(), "0")
            ));

        return new BinaryNode("iq",
            Map.of(
                "xmlns", "md",
                "type",  "set",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    iqId
            ),
            List.of(reg));
    }

    public BinaryNode buildCompanionFinish(String iqId, BinaryNode notification) {
        BinaryNode reg = notification.child("link_code_companion_reg");
        if (reg == null) throw new IllegalStateException("missing <link_code_companion_reg> in notification");

        byte[] wrappedPrimaryEph = bytes(reg.child("link_code_pairing_wrapped_primary_ephemeral_pub"));
        byte[] primaryIdentityPub = bytes(reg.child("primary_identity_pub"));
        byte[] linkCodePairingRef = bytes(reg.child("link_code_pairing_ref"));
        if (wrappedPrimaryEph == null || wrappedPrimaryEph.length < 80) {
            throw new IllegalStateException("invalid wrapped_primary_ephemeral_pub: len="
                + (wrappedPrimaryEph == null ? -1 : wrappedPrimaryEph.length));
        }
        if (primaryIdentityPub == null || linkCodePairingRef == null) {
            throw new IllegalStateException("missing primary_identity_pub or link_code_pairing_ref");
        }
        if (pairingCode == null || pairingEphemeralKey == null) {
            throw new IllegalStateException("no pending pairing-code session — did you call buildCompanionHello?");
        }

        byte[] primarySalt = Bytes.slice(wrappedPrimaryEph, 0, 32);
        byte[] primaryIv = Bytes.slice(wrappedPrimaryEph, 32, 48);
        byte[] primaryCt = Bytes.slice(wrappedPrimaryEph, 48, 80);
        byte[] linkKey = Pbkdf2.deriveSha256(pairingCode, primarySalt, PBKDF2_ITERATIONS, 32);
        byte[] primaryEphemeralPub = AesCtr.decrypt(linkKey, primaryIv, primaryCt);

        byte[] ephSharedSecret = Curve25519.sharedKey(pairingEphemeralKey.privateKey(), primaryEphemeralPub);
        byte[] identitySharedKey = Curve25519.sharedKey(creds.signedIdentityKey.privateKey(), primaryIdentityPub);

        byte[] advSecretRandom = Bytes.random(32);
        byte[] advInput = Bytes.concat(ephSharedSecret, identitySharedKey, advSecretRandom);
        byte[] advSecret = Hkdf.derive(advInput, new byte[0], "adv_secret".getBytes(), 32);
        creds.advSecretKey = advSecret;

        byte[] keyBundleSalt = Bytes.random(32);
        byte[] keyBundleNonce = Bytes.random(12);
        byte[] keyBundleEncKey = Hkdf.derive(ephSharedSecret, keyBundleSalt,
            "link_code_pairing_key_bundle_encryption_key".getBytes(), 32);
        byte[] plaintextBundle = Bytes.concat(
            creds.signedIdentityKey.publicKey(),
            primaryIdentityPub,
            advSecretRandom
        );
        byte[] encryptedBundle = AesGcm.encrypt(keyBundleEncKey, keyBundleNonce, plaintextBundle, null);
        byte[] wrappedKeyBundle = Bytes.concat(keyBundleSalt, keyBundleNonce, encryptedBundle);

        BinaryNode finish = new BinaryNode("link_code_companion_reg",
            Map.of(
                "jid",   creds.meJid,
                "stage", "companion_finish"
            ),
            List.of(
                new BinaryNode("link_code_pairing_wrapped_key_bundle", Map.of(), wrappedKeyBundle),
                new BinaryNode("companion_identity_public",            Map.of(), creds.signedIdentityKey.publicKey()),
                new BinaryNode("link_code_pairing_ref",                Map.of(), linkCodePairingRef)
            ));

        LOG.info("Built companion_finish; advSecret derived ({}B)", advSecret.length);

        return new BinaryNode("iq",
            Map.of(
                "xmlns", "md",
                "type",  "set",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    iqId
            ),
            List.of(finish));
    }

    private static byte[] wrapEphemeralPub(String code, byte[] companionEphemeralPub) {
        byte[] salt = Bytes.random(32);
        byte[] iv = Bytes.random(16);
        byte[] key = Pbkdf2.deriveSha256(code, salt, PBKDF2_ITERATIONS, 32);
        byte[] ct = AesCtr.encrypt(key, iv, companionEphemeralPub);
        return Bytes.concat(salt, iv, ct);
    }

    private static byte[] bytes(BinaryNode n) { return n == null ? null : n.bytesContent(); }
}
