package id.ja_wa.client;

import id.ja_wa.domain.model.AuthCreds;
import id.ja_wa.domain.store.FileAuthStore;
import id.ja_wa.event.MessageUpsert;
import id.ja_wa.event.WaEventListener;
import id.ja_wa.feature.messaging.MessageReceiver;
import id.ja_wa.feature.messaging.PeerDataOperations;
import id.ja_wa.feature.signal.JaWaProtocolStore;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Increment 10b: the two open items left by Increment 10, against
 * Baileys {@code lib/Socket/messages-recv.js:1301-1360}.
 *
 * <p>Task 1 — the decrypt-failure catch now auto-requests the
 * placeholder resend: an eligible fresh NO_MESSAGE_FOUND failure
 * driven through the real catch
 * ({@link JaWaClient#handleStanzaForTesting}) must trigger the
 * existing {@code requestPlaceholderResend} flow (claim + 2-second
 * delay + PDO send), not merely claim. What is asserted here, stated
 * plainly: the PDO send is asserted as <em>attempted and captured</em>
 * at the peer-mode send seam — with a companion client (device 5) and
 * a real Signal session for device 0, the PDO stanza is captured by
 * {@code setMessageSenderForTesting} with category {@code peer} /
 * {@code push_priority high_force} and an encrypted {@code <enc>}
 * child. The stanza payload itself is Signal-encrypted, so its inner
 * PLACEHOLDER_MESSAGE_RESEND request bytes are not decoded here; the
 * captured stanza proves the send path ran for this message id, and
 * the recorded 2000 ms delay proves the delay seam (not a bypass)
 * was exercised. No live server or socket is used: the transport is
 * disconnected, and the peer-mode seam replaces {@code send()}.
 * A subsequent manual {@code requestPlaceholderResend} must still
 * return null (already claimed), a duplicate arrival must still
 * upsert but send no second PDO stanza, and non-eligible messages
 * must attempt nothing (the fake delay is never even invoked).
 *
 * <p>Task 2 — a decrypt failure on a client with null creds must not
 * throw out of the catch block (the pre-existing
 * {@code sendRetryReceipt} NPE on {@code creds.registrationId}).
 * Receipt behaviour with creds set is unchanged and is covered by
 * the existing tests named in the report (Increment10Test's catch
 * tests, which drive the same catch with creds set); this file
 * asserts the null-creds catch completes, still upserts the stub
 * and claims it, and sends no PDO stanza.
 */
class Increment10bTest {

    private static final long NOW = System.currentTimeMillis() / 1000L;

    private static BinaryNode stanza(Map<String, String> attrs, List<BinaryNode> children) {
        return new BinaryNode("message", attrs, children);
    }

    private static Map<String, String> attrs(String id, String from, long t) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("from", from);
        m.put("t", Long.toString(t));
        return m;
    }

    private static BinaryNode unavailable(String type) {
        return type == null
            ? new BinaryNode("unavailable", Map.of(), null)
            : new BinaryNode("unavailable", Map.of("type", type), null);
    }

    private static List<MessageUpsert> captureUpserts(JaWaClient client) {
        List<MessageUpsert> upserts = new ArrayList<>();
        client.addEventListener(new WaEventListener() {
            @Override
            public void onMessageUpsert(MessageUpsert upsert) {
                upserts.add(upsert);
            }
        });
        return upserts;
    }

    /**
     * Companion client (device 5, as in Increment8Test's peer-mode
     * test) with a real in-memory Signal session for device 0, so the
     * auto-send's PDO stanza is captured at the peer-mode seam
     * instead of going to a socket. The fake delay completes
     * immediately and records the requested delay, so the auto-send
     * runs synchronously inside the catch (no sleeping, no 2-second
     * delayed task left behind for this client).
     */
    private static JaWaClient companionClient(Path tempDir, List<BinaryNode> captured,
            long[] seenDelay) throws Exception {
        AuthCreds creds = AuthCreds.generate();
        creds.meJid = "111:5@s.whatsapp.net";
        creds.account = null;
        JaWaProtocolStore store = new JaWaProtocolStore(creds);
        IdentityKeyPair remoteId = KeyHelper.generateIdentityKeyPair();
        SignedPreKeyRecord signed = KeyHelper.generateSignedPreKey(remoteId, 1);
        PreKeyRecord pre = KeyHelper.generatePreKeys(1, 1).get(0);
        PreKeyBundle bundle = new PreKeyBundle(4242, 0, pre.getId(), pre.getKeyPair().getPublicKey(),
            signed.getId(), signed.getKeyPair().getPublicKey(), signed.getSignature(),
            remoteId.getPublicKey());
        new SessionBuilder(store, new SignalProtocolAddress("111", 0)).process(bundle);

        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        client.setCredsForTesting(creds);
        client.setProtocolStoreForTesting(store);
        client.setMessageSenderForTesting(captured::add);
        client.setPlaceholderResendDelayForTesting(millis -> {
            seenDelay[0] = millis;
            return CompletableFuture.completedFuture(null);
        });
        return client;
    }

    @Test
    void catchAutoSendsPlaceholderResendForEligibleFreshMessage(@TempDir Path tempDir)
            throws Exception {
        List<BinaryNode> captured = new ArrayList<>();
        long[] seenDelay = {-1L};
        JaWaClient client = companionClient(tempDir, captured, seenDelay);
        List<MessageUpsert> upserts = captureUpserts(client);

        BinaryNode fresh = stanza(attrs("MSG10B", "999@s.whatsapp.net", NOW),
            List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(fresh)).isTrue();

        // Stub upsert still dispatched + store fed (Increment 10 behaviour, kept).
        assertThat(upserts).hasSize(1);
        assertThat(upserts.get(0).type()).isEqualTo("notify");
        MessageReceiver.Decoded stub = upserts.get(0).messages().get(0);
        assertThat(stub.msgId()).isEqualTo("MSG10B");
        assertThat(stub.messageStubType())
            .isEqualTo(Wa.WebMessageInfo.StubType.CIPHERTEXT);
        assertThat(stub.messageStubParameters())
            .containsExactly("Message absent from node");
        assertThat(client.store().messages("999@s.whatsapp.net")).hasSize(1);

        // Claim held, storing the stub, and the 2-second delay seam was exercised.
        assertThat(client.placeholderResendManagerForTesting().isPending("MSG10B")).isTrue();
        assertThat(client.placeholderResendManagerForTesting().stored("MSG10B"))
            .isInstanceOf(MessageReceiver.Decoded.class);
        assertThat(seenDelay[0]).isEqualTo(PeerDataOperations.PLACEHOLDER_RESEND_DELAY_MS);

        // PDO send attempted and captured at the peer-mode seam.
        assertThat(captured).hasSize(1);
        BinaryNode pdoStanza = captured.get(0);
        assertThat(pdoStanza.tag()).isEqualTo("message");
        assertThat(pdoStanza.attr("to")).isEqualTo("111@s.whatsapp.net");
        assertThat(pdoStanza.attr("category")).isEqualTo("peer");
        assertThat(pdoStanza.attr("push_priority")).isEqualTo("high_force");
        assertThat(pdoStanza.child("participants")).isNull();
        List<BinaryNode> children = pdoStanza.childrenList();
        assertThat(children.get(0).tag()).isEqualTo("enc");
        assertThat(children.get(0).bytesContent()).isNotEmpty();
        assertThat(children.get(children.size() - 1).tag()).isEqualTo("meta");
        assertThat(children.get(children.size() - 1).attr("appdata")).isEqualTo("default");

        // Duplicate arrival: still upserts, but sends no second PDO stanza.
        assertThat(client.handleStanzaForTesting(fresh)).isTrue();
        assertThat(upserts).hasSize(2);
        assertThat(captured).hasSize(1);
        assertThat(client.placeholderResendManagerForTesting().isPending("MSG10B")).isTrue();

        // Subsequent manual request still observes already-claimed (null).
        Wa.MessageKey key = Wa.MessageKey.newBuilder()
            .setRemoteJid("999@s.whatsapp.net").setFromMe(false).setId("MSG10B").build();
        assertThat(client.requestPlaceholderResend(key, null).get(5, TimeUnit.SECONDS)).isNull();
        assertThat(captured).hasSize(1);
    }

    @Test
    void catchAutoSendSkipsNonEligibleMessages(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> captured = new ArrayList<>();
        long[] seenDelay = {-1L};
        JaWaClient client = companionClient(tempDir, captured, seenDelay);
        List<MessageUpsert> upserts = captureUpserts(client);

        BinaryNode old = stanza(attrs("OLD10B", "999@s.whatsapp.net", NOW - 15L * 24 * 60 * 60),
            List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(old)).isTrue();

        BinaryNode excluded = stanza(attrs("EXC10B", "999@s.whatsapp.net", NOW),
            List.of(unavailable("hosted_unavailable_fanout")));
        assertThat(client.handleStanzaForTesting(excluded)).isTrue();

        Map<String, String> peerAttrs = attrs("PEER10B", "999@s.whatsapp.net", NOW);
        peerAttrs.put("category", "peer");
        BinaryNode peer = stanza(peerAttrs, List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(peer)).isTrue();

        // OTHER kind (empty <enc>): no stub, no claim, no send.
        BinaryNode other = stanza(attrs("BAD10B", "999@s.whatsapp.net", NOW),
            List.of(new BinaryNode("enc", Map.of("type", "msg"), new byte[0])));
        assertThat(client.handleStanzaForTesting(other)).isTrue();

        assertThat(upserts).isEmpty();
        assertThat(captured).isEmpty();
        // The delay seam was never invoked: no auto-send was even attempted.
        assertThat(seenDelay[0]).isEqualTo(-1L);
        for (String id : List.of("OLD10B", "EXC10B", "PEER10B", "BAD10B")) {
            assertThat(client.placeholderResendManagerForTesting().isPending(id))
                .as("pending %s", id).isFalse();
        }
        assertThat(client.store().messages("999@s.whatsapp.net")).isEmpty();
    }

    @Test
    void decryptFailureWithNullCredsDoesNotThrowAndStillUpserts(@TempDir Path tempDir)
            throws Exception {
        // Deliberately no setCredsForTesting: creds stays null (unpaired client).
        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        List<BinaryNode> captured = new ArrayList<>();
        client.setMessageSenderForTesting(captured::add);
        long[] seenDelay = {-1L};
        client.setPlaceholderResendDelayForTesting(millis -> {
            seenDelay[0] = millis;
            return CompletableFuture.completedFuture(null);
        });
        List<MessageUpsert> upserts = captureUpserts(client);

        BinaryNode fresh = stanza(attrs("NULLCRED1", "999@s.whatsapp.net", NOW),
            List.of(unavailable(null)));
        // Passing this call (rather than erroring with NPE) is the
        // assertion that the catch completes: a throw would fail the test.
        assertThat(client.handleStanzaForTesting(fresh)).isTrue();

        // Catch flow continued past the skipped retry receipt: the
        // stub was still upserted, stored and claimed.
        assertThat(upserts).hasSize(1);
        assertThat(upserts.get(0).messages().get(0).messageStubType())
            .isEqualTo(Wa.WebMessageInfo.StubType.CIPHERTEXT);
        assertThat(client.store().messages("999@s.whatsapp.net")).hasSize(1);
        assertThat(client.placeholderResendManagerForTesting().isPending("NULLCRED1")).isTrue();
        // The auto-send was attempted (delay seam ran) but failed fast
        // on "Not authenticated" and was swallowed: no PDO stanza, no throw.
        assertThat(seenDelay[0]).isEqualTo(PeerDataOperations.PLACEHOLDER_RESEND_DELAY_MS);
        assertThat(captured).isEmpty();
    }
}
