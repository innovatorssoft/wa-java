package id.jawa.client;

import id.jawa.domain.store.FileAuthStore;
import id.jawa.event.MessageUpsert;
import id.jawa.event.WaEventListener;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.feature.messaging.PeerDataOperations;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Increment 10: structured decrypt-failure kind + placeholder
 * auto-trigger for NO_MESSAGE_FOUND only (Baileys
 * {@code lib/Utils/decode-wa-message.js:208+} and
 * {@code lib/Socket/messages-recv.js:1301-1360}).
 *
 * No live server is used: decode is driven directly (its
 * no-enc / empty-enc paths touch no Signal store), and the
 * JaWaClient catch is driven through
 * {@link JaWaClient#handleStanzaForTesting} — which reaches the
 * real catch block — while disconnected, so ack / retry-receipt
 * sends are the existing disconnected no-op and only the event-bus
 * upsert, the store feed and the placeholder claim are observed.
 * The receipt itself is therefore NOT asserted by counting sends
 * here (nothing is sent while disconnected); it is asserted
 * structurally — the catch's sendAck / sendRetryReceipt lines are
 * untouched and run exactly once before the new helper call, the
 * helper adds no send() call of its own — and by the unchanged
 * retry/decrypt regression classes passing (see the report).
 */
class Increment10Test {

    private static final long NOW = System.currentTimeMillis() / 1000L;
    private static final long CAP = PeerDataOperations.PLACEHOLDER_MAX_AGE_SECONDS;

    private static JaWaClient client(Path tempDir) throws Exception {
        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        // The catch's pre-existing sendRetryReceipt dereferences
        // creds.registrationId, so a client under test needs creds
        // set (the Increment9Test pattern) — this is test setup, not
        // a production change: the retry-receipt code is untouched.
        id.jawa.domain.model.AuthCreds creds = id.jawa.domain.model.AuthCreds.generate();
        creds.meJid = "111@s.whatsapp.net";
        client.setCredsForTesting(creds);
        return client;
    }

    /** A <message> stanza with the given attrs and children. */
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

    // ---- Task 1: NO_MESSAGE_FOUND kind from the verified stanza shape ----

    @Test
    void noMessageFoundKindForUnavailableNoContentStanza() {
        BinaryNode noType = stanza(attrs("M1", "999@s.whatsapp.net", NOW),
            List.of(unavailable(null)));
        assertThatThrownBy(() -> MessageReceiver.decode(noType, null, null))
            .isInstanceOf(MessageReceiver.DecryptException.class)
            .satisfies(e -> {
                MessageReceiver.DecryptException de = (MessageReceiver.DecryptException) e;
                assertThat(de.kind())
                    .isEqualTo(MessageReceiver.DecryptFailureKind.NO_MESSAGE_FOUND);
                assertThat(de.getMessage())
                    .isEqualTo(MessageReceiver.NO_MESSAGE_FOUND_ERROR_TEXT);
                assertThat(de.isPlaceholderEligible()).isTrue();
            });

        // The <unavailable> type does not change the decode kind —
        // the fanout exclusions are an eligibility gate in the
        // receiver (messages-recv.js:1311), not in decode.
        BinaryNode fanout = stanza(attrs("M2", "999@s.whatsapp.net", NOW),
            List.of(unavailable("bot_unavailable_fanout")));
        assertThatThrownBy(() -> MessageReceiver.decode(fanout, null, null))
            .isInstanceOf(MessageReceiver.DecryptException.class)
            .satisfies(e -> assertThat(((MessageReceiver.DecryptException) e).kind())
                .isEqualTo(MessageReceiver.DecryptFailureKind.NO_MESSAGE_FOUND));

        // Baileys suppresses the stub for view-once placeholders
        // (decode-wa-message.js: isViewOnce): still a null-message
        // Decoded, exactly as before Increment 10.
        BinaryNode viewOnce = stanza(attrs("M3", "999@s.whatsapp.net", NOW),
            List.of(unavailable("view_once")));
        assertThatCodeReturnsNullMessage(viewOnce);

        // Plain no-<enc> stanza with NO <unavailable> child: preserved
        // null-message Decoded, deliberately not reclassified (see
        // MessageReceiver.decode's Increment 10 comment).
        BinaryNode plain = stanza(attrs("M4", "999@s.whatsapp.net", NOW), List.of());
        assertThatCodeReturnsNullMessage(plain);
    }

    private static void assertThatCodeReturnsNullMessage(BinaryNode stanza) {
        try {
            MessageReceiver.Decoded decoded = MessageReceiver.decode(stanza, null, null);
            assertThat(decoded.message()).isNull();
            assertThat(decoded.encType()).isNull();
            assertThat(decoded.messageStubType()).isNull();
        } catch (MessageReceiver.DecryptException e) {
            throw new AssertionError("expected null-message Decoded, got DecryptException", e);
        }
    }

    @Test
    void emptyEncPayloadStaysOtherNotNoMessageFound() {
        // Baileys counts an <enc> child as a decryptable even when its
        // decrypt then fails, so an empty <enc> is a decrypt failure
        // (OTHER), not "message absent from node".
        BinaryNode emptyEnc = stanza(attrs("M5", "999@s.whatsapp.net", NOW),
            List.of(new BinaryNode("enc", Map.of("type", "msg"), new byte[0])));
        assertThatThrownBy(() -> MessageReceiver.decode(emptyEnc, null, null))
            .isInstanceOf(MessageReceiver.DecryptException.class)
            .satisfies(e -> {
                MessageReceiver.DecryptException de = (MessageReceiver.DecryptException) e;
                assertThat(de.kind()).isEqualTo(MessageReceiver.DecryptFailureKind.OTHER);
                assertThat(de.isPlaceholderEligible()).isFalse();
            });
    }

    // ---- Task 1: other kinds are not placeholder-eligible ----

    @Test
    void otherFailureKindsAreNotPlaceholderEligible() {
        // Legacy construction sites default to OTHER.
        assertThat(new MessageReceiver.DecryptException("x").kind())
            .isEqualTo(MessageReceiver.DecryptFailureKind.OTHER);
        assertThat(new MessageReceiver.DecryptException("x",
            new RuntimeException("boom")).kind())
            .isEqualTo(MessageReceiver.DecryptFailureKind.OTHER);
        assertThat(new MessageReceiver.DecryptException("x").isPlaceholderEligible()).isFalse();

        // MISSING_KEYS classification from the cause chain (libsignal
        // text 'Key used already or never filled'), still ineligible.
        assertThat(MessageReceiver.classifyFailure(
            new RuntimeException("wrap",
                new RuntimeException("Key used already or never filled"))))
            .isEqualTo(MessageReceiver.DecryptFailureKind.MISSING_KEYS);
        assertThat(MessageReceiver.classifyFailure(null))
            .isEqualTo(MessageReceiver.DecryptFailureKind.OTHER);
        assertThat(MessageReceiver.classifyFailure(new RuntimeException("No session record")))
            .isEqualTo(MessageReceiver.DecryptFailureKind.OTHER);
        assertThat(new MessageReceiver.DecryptException("Key used already or never filled",
            MessageReceiver.DecryptFailureKind.MISSING_KEYS).isPlaceholderEligible())
            .isFalse();
        assertThat(new MessageReceiver.DecryptException("x", (MessageReceiver.DecryptFailureKind) null)
            .kind()).isEqualTo(MessageReceiver.DecryptFailureKind.OTHER);
    }

    @Test
    void placeholderEligibilityGate() {
        var kind = MessageReceiver.DecryptFailureKind.NO_MESSAGE_FOUND;
        var other = MessageReceiver.DecryptFailureKind.OTHER;
        assertThat(CAP).isEqualTo(14L * 24 * 60 * 60);

        assertThat(PeerDataOperations.isPlaceholderResendEligible(kind, null, null, NOW, NOW))
            .isTrue();
        assertThat(PeerDataOperations.isPlaceholderResendEligible(other, null, null, NOW, NOW))
            .isFalse();
        assertThat(PeerDataOperations.isPlaceholderResendEligible(null, null, null, NOW, NOW))
            .isFalse();
        assertThat(PeerDataOperations.isPlaceholderResendEligible(
            MessageReceiver.DecryptFailureKind.MISSING_KEYS, null, null, NOW, NOW)).isFalse();

        // <unavailable> type exclusions (messages-recv.js:1311-1313).
        for (String excluded : PeerDataOperations.EXCLUDED_UNAVAILABLE_TYPES) {
            assertThat(PeerDataOperations.isPlaceholderResendEligible(kind, excluded, null, NOW, NOW))
                .as("excluded type %s", excluded).isFalse();
        }
        assertThat(PeerDataOperations.isPlaceholderResendEligible(kind, "other_type", null, NOW, NOW))
            .isTrue();

        // Peer category gate (messages-recv.js:1301).
        assertThat(PeerDataOperations.isPlaceholderResendEligible(kind, null, "peer", NOW, NOW))
            .isFalse();

        // 14-day cap (Defaults PLACEHOLDER_MAX_AGE_SECONDS), boundary inclusive.
        assertThat(PeerDataOperations.isPlaceholderResendEligible(kind, null, null, NOW - CAP, NOW))
            .isTrue();
        assertThat(PeerDataOperations.isPlaceholderResendEligible(kind, null, null, NOW - CAP - 1, NOW))
            .isFalse();
        assertThat(PeerDataOperations.isPlaceholderResendEligible(kind, null, null, NOW - 15 * 24 * 60 * 60, NOW))
            .isFalse();
        // Timestamp-less messages are treated as fresh (Baileys NaN comparison).
        assertThat(PeerDataOperations.isPlaceholderResendEligible(kind, null, null, 0L, NOW))
            .isTrue();
    }

    // ---- Task 2: catch wiring through the real catch block ----

    @Test
    void catchWiringFreshUnavailableUpsertsStubAndClaims(@TempDir Path tempDir) throws Exception {
        JaWaClient client = client(tempDir);
        List<MessageUpsert> upserts = captureUpserts(client);

        BinaryNode fresh = stanza(attrs("MSG10", "999@s.whatsapp.net", NOW),
            List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(fresh)).isTrue();

        assertThat(upserts).hasSize(1);
        MessageUpsert upsert = upserts.get(0);
        assertThat(upsert.type()).isEqualTo("notify");
        assertThat(upsert.requestId()).isNull();
        MessageReceiver.Decoded stub = upsert.messages().get(0);
        assertThat(stub.msgId()).isEqualTo("MSG10");
        assertThat(stub.senderJid()).isEqualTo("999@s.whatsapp.net");
        assertThat(stub.groupJid()).isNull();
        assertThat(stub.message()).isNull();
        assertThat(stub.messageStubType())
            .isEqualTo(Wa.WebMessageInfo.StubType.CIPHERTEXT);
        assertThat(stub.messageStubParameters())
            .containsExactly("Message absent from node");

        // Store feed carries the stub, as for the Increment 9 V4 synthetic.
        List<MessageReceiver.Decoded> stored = client.store().messages("999@s.whatsapp.net");
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).messageStubType())
            .isEqualTo(Wa.WebMessageInfo.StubType.CIPHERTEXT);

        // Claim: the manager holds the stub, and a subsequent
        // requestPlaceholderResend observes the claim (already
        // requested -> null, no duplicate PDO send).
        assertThat(client.placeholderResendManagerForTesting().isPending("MSG10")).isTrue();
        assertThat(client.placeholderResendManagerForTesting().stored("MSG10"))
            .isInstanceOf(MessageReceiver.Decoded.class);
        Wa.MessageKey key = Wa.MessageKey.newBuilder()
            .setRemoteJid("999@s.whatsapp.net").setFromMe(false).setId("MSG10").build();
        assertThat(client.requestPlaceholderResend(key, null).get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void catchWiringOldExcludedAndPeerMessagesAreSkipped(@TempDir Path tempDir) throws Exception {
        JaWaClient client = client(tempDir);
        List<MessageUpsert> upserts = captureUpserts(client);

        // Older than 14 days: skipped (messages-recv.js:1319).
        BinaryNode old = stanza(attrs("OLD1", "999@s.whatsapp.net", NOW - 15L * 24 * 60 * 60),
            List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(old)).isTrue();

        // Excluded <unavailable> type: skipped (messages-recv.js:1311).
        BinaryNode excluded = stanza(attrs("EXC1", "999@s.whatsapp.net", NOW),
            List.of(unavailable("hosted_unavailable_fanout")));
        assertThat(client.handleStanzaForTesting(excluded)).isTrue();

        // Peer category: skipped (messages-recv.js:1301).
        Map<String, String> peerAttrs = attrs("PEER1", "999@s.whatsapp.net", NOW);
        peerAttrs.put("category", "peer");
        BinaryNode peer = stanza(peerAttrs, List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(peer)).isTrue();

        assertThat(upserts).isEmpty();
        assertThat(client.placeholderResendManagerForTesting().isPending("OLD1")).isFalse();
        assertThat(client.placeholderResendManagerForTesting().isPending("EXC1")).isFalse();
        assertThat(client.placeholderResendManagerForTesting().isPending("PEER1")).isFalse();
        assertThat(client.store().messages("999@s.whatsapp.net")).isEmpty();
    }

    // ---- Task 3: non-eligible failures are a pure no-op for placeholder ----

    @Test
    void nonEligibleFailureViaCatchUpsertsNothingAndClaimsNothing(@TempDir Path tempDir)
            throws Exception {
        JaWaClient client = client(tempDir);
        List<MessageUpsert> upserts = captureUpserts(client);

        // Empty <enc> -> OTHER kind -> real catch, no stub, no claim.
        BinaryNode emptyEnc = stanza(attrs("BAD1", "999@s.whatsapp.net", NOW),
            List.of(new BinaryNode("enc", Map.of("type", "msg"), new byte[0])));
        assertThat(client.handleStanzaForTesting(emptyEnc)).isTrue();
        assertThat(upserts).isEmpty();
        assertThat(client.placeholderResendManagerForTesting().isPending("BAD1")).isFalse();
        assertThat(client.store().messages("999@s.whatsapp.net")).isEmpty();
    }

    @Test
    void placeholderHelperDirectGroupDerivationAndBlankId(@TempDir Path tempDir) throws Exception {
        JaWaClient client = client(tempDir);
        List<MessageUpsert> upserts = captureUpserts(client);

        // Group derivation mirrors decode: participant is the sender,
        // from is the group.
        Map<String, String> groupAttrs = attrs("GRP1", "123@g.us", NOW);
        groupAttrs.put("participant", "999@s.whatsapp.net");
        BinaryNode groupStanza = stanza(groupAttrs, List.of(unavailable(null)));
        MessageReceiver.Decoded stub = client.handleDecryptFailurePlaceholder(groupStanza,
            new MessageReceiver.DecryptException(MessageReceiver.NO_MESSAGE_FOUND_ERROR_TEXT,
                MessageReceiver.DecryptFailureKind.NO_MESSAGE_FOUND));
        assertThat(stub).isNotNull();
        assertThat(stub.senderJid()).isEqualTo("999@s.whatsapp.net");
        assertThat(stub.groupJid()).isEqualTo("123@g.us");
        assertThat(upserts).hasSize(1);
        assertThat(client.placeholderResendManagerForTesting().isPending("GRP1")).isTrue();
        assertThat(client.store().messages("123@g.us")).hasSize(1);

        // Non-eligible kind through the helper directly: null, no claim, no upsert.
        MessageReceiver.Decoded none = client.handleDecryptFailurePlaceholder(
            stanza(attrs("GRP2", "123@g.us", NOW), List.of(unavailable(null))),
            new MessageReceiver.DecryptException("decrypt failed",
                MessageReceiver.DecryptFailureKind.OTHER));
        assertThat(none).isNull();
        assertThat(client.placeholderResendManagerForTesting().isPending("GRP2")).isFalse();
        assertThat(upserts).hasSize(1);

        // Blank id: no stub can be built or claimed.
        Map<String, String> blankAttrs = new LinkedHashMap<>();
        blankAttrs.put("from", "999@s.whatsapp.net");
        blankAttrs.put("t", Long.toString(NOW));
        assertThat(client.handleDecryptFailurePlaceholder(
            stanza(blankAttrs, List.of(unavailable(null))),
            new MessageReceiver.DecryptException(MessageReceiver.NO_MESSAGE_FOUND_ERROR_TEXT,
                MessageReceiver.DecryptFailureKind.NO_MESSAGE_FOUND))).isNull();
        assertThat(client.handleDecryptFailurePlaceholder(null,
            new MessageReceiver.DecryptException(MessageReceiver.NO_MESSAGE_FOUND_ERROR_TEXT,
                MessageReceiver.DecryptFailureKind.NO_MESSAGE_FOUND))).isNull();
    }
}
