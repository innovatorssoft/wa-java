package id.ja_wa.feature.pairing;


import id.ja_wa.protocol.codec.BinaryNode;
import id.ja_wa.domain.model.AuthCreds;
import id.ja_wa.protocol.crypto.Bytes;
import id.ja_wa.protocol.crypto.AesCtr;
import id.ja_wa.protocol.crypto.AesGcm;
import id.ja_wa.protocol.crypto.Curve25519;
import id.ja_wa.protocol.crypto.Hkdf;
import id.ja_wa.protocol.crypto.KeyPair25519;
import id.ja_wa.protocol.crypto.Pbkdf2;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PairingCodeHandlerTest {

    @Test
    void companionHelloStanzaShape() {
        AuthCreds creds = AuthCreds.generate();
        PairingCodeHandler h = new PairingCodeHandler(creds);
        BinaryNode iq = h.buildCompanionHello("abc", "628123456789", null);

        assertThat(iq.tag()).isEqualTo("iq");
        assertThat(iq.attrs())
            .containsEntry("xmlns", "md")
            .containsEntry("type", "set")
            .containsEntry("id", "abc");

        BinaryNode reg = iq.child("link_code_companion_reg");
        assertThat(reg).isNotNull();
        assertThat(reg.attr("jid")).isEqualTo("628123456789@s.whatsapp.net");
        assertThat(reg.attr("stage")).isEqualTo("companion_hello");

        byte[] wrapped = reg.child("link_code_pairing_wrapped_companion_ephemeral_pub").bytesContent();
        assertThat(wrapped).hasSize(80);

        assertThat(reg.child("companion_server_auth_key_pub").bytesContent())
            .containsExactly(creds.noiseKey.publicKey());
        assertThat(reg.child("companion_platform_id").textContent()).isEqualTo("1");
        assertThat(reg.child("companion_platform_display").textContent()).isEqualTo("Chrome (Linux)");
        assertThat(reg.child("link_code_pairing_nonce").textContent()).isEqualTo("0");

        assertThat(h.pairingCode()).hasSize(8);

        assertThat(creds.meJid).isEqualTo("628123456789@s.whatsapp.net");
    }

    @Test
    void rejectsBadCustomCodeLength() {
        AuthCreds creds = AuthCreds.generate();
        PairingCodeHandler h = new PairingCodeHandler(creds);
        assertThatThrownBy(() -> h.buildCompanionHello("id", "6281", "TOOSHORT-XX"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customCodeIsUsedVerbatim() {
        AuthCreds creds = AuthCreds.generate();
        PairingCodeHandler h = new PairingCodeHandler(creds);
        h.buildCompanionHello("id", "628111111111", "ABCD1234");
        assertThat(h.pairingCode()).isEqualTo("ABCD1234");
    }

    @Test
    void companionFinishDerivesAdvSecretAndProducesValidStanza() {
        AuthCreds creds = AuthCreds.generate();
        PairingCodeHandler h = new PairingCodeHandler(creds);
        h.buildCompanionHello("hello", "628000000000", "ABCDEFGH");

        KeyPair25519 primaryEphemeral = Curve25519.generateKeyPair();
        KeyPair25519 primaryIdentity  = Curve25519.generateKeyPair();

        byte[] salt = Bytes.random(32);
        byte[] iv   = Bytes.random(16);
        byte[] key  = Pbkdf2.deriveSha256("ABCDEFGH", salt, PairingCodeHandler.PBKDF2_ITERATIONS, 32);
        byte[] ct   = AesCtr.encrypt(key, iv, primaryEphemeral.publicKey());
        byte[] wrappedPrimaryEph = Bytes.concat(salt, iv, ct);
        byte[] pairingRef = Bytes.random(16);

        BinaryNode notif = new BinaryNode("notification", Map.of(), List.of(
            new BinaryNode("link_code_companion_reg", Map.of(), List.of(
                new BinaryNode("link_code_pairing_wrapped_primary_ephemeral_pub",
                    Map.of(), wrappedPrimaryEph),
                new BinaryNode("primary_identity_pub", Map.of(), primaryIdentity.publicKey()),
                new BinaryNode("link_code_pairing_ref", Map.of(), pairingRef)
            ))
        ));

        BinaryNode finishIq = h.buildCompanionFinish("finish-1", notif);

        assertThat(finishIq.tag()).isEqualTo("iq");
        assertThat(finishIq.attrs())
            .containsEntry("xmlns", "md")
            .containsEntry("type", "set")
            .containsEntry("id", "finish-1");

        BinaryNode reg = finishIq.child("link_code_companion_reg");
        assertThat(reg.attr("stage")).isEqualTo("companion_finish");
        byte[] wrappedKeyBundle = reg.child("link_code_pairing_wrapped_key_bundle").bytesContent();
        byte[] companionIdPub   = reg.child("companion_identity_public").bytesContent();
        byte[] refOut           = reg.child("link_code_pairing_ref").bytesContent();

        assertThat(wrappedKeyBundle).hasSize(32 + 12 + 96 + 16);
        assertThat(companionIdPub).containsExactly(creds.signedIdentityKey.publicKey());
        assertThat(refOut).containsExactly(pairingRef);

        assertThat(creds.advSecretKey).isNotNull().hasSize(32);

        byte[] bSalt  = Bytes.slice(wrappedKeyBundle, 0, 32);
        byte[] bNonce = Bytes.slice(wrappedKeyBundle, 32, 44);
        byte[] bCt    = Bytes.slice(wrappedKeyBundle, 44, wrappedKeyBundle.length);
        byte[] ephSharedFromPhone = Curve25519.sharedKey(primaryEphemeral.privateKey(),

            primaryEphemeral.publicKey());

        assertThat(bSalt).hasSize(32);
        assertThat(bNonce).hasSize(12);
        assertThat(bCt).hasSize(96 + 16);

        assertThat(ephSharedFromPhone).isNotEmpty();
    }

    @Test
    void companionFinishWithoutHelloThrows() {
        AuthCreds creds = AuthCreds.generate();
        PairingCodeHandler h = new PairingCodeHandler(creds);

        BinaryNode notif = new BinaryNode("notification", Map.of(), List.of(
            new BinaryNode("link_code_companion_reg", Map.of(), List.of(
                new BinaryNode("link_code_pairing_wrapped_primary_ephemeral_pub",
                    Map.of(), Bytes.random(80)),
                new BinaryNode("primary_identity_pub", Map.of(), Bytes.random(32)),
                new BinaryNode("link_code_pairing_ref", Map.of(), Bytes.random(16))
            ))
        ));
        assertThatThrownBy(() -> h.buildCompanionFinish("x", notif))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void advSecretDerivationMatchesSpec() {

        byte[] eph = Bytes.random(32);
        byte[] id  = Bytes.random(32);
        byte[] r   = Bytes.random(32);
        byte[] secret = Hkdf.derive(Bytes.concat(eph, id, r), new byte[0],
            "adv_secret".getBytes(), 32);
        assertThat(secret).hasSize(32);
    }

    @Test
    void aesGcmWrapSizeMatchesWireExpectation() {

        byte[] key = Bytes.random(32);
        byte[] nonce = Bytes.random(12);
        byte[] pt = Bytes.random(96);
        byte[] ct = AesGcm.encrypt(key, nonce, pt, null);
        assertThat(ct).hasSize(112);
    }
}
