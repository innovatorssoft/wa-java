package id.ja_wa.feature.messaging;

import id.ja_wa.event.ConnectionUpdate;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Increment6WorkstreamATest {

    // ---- PDO message shape (Baileys lib/Socket/messages-send.js sendPeerDataOperationMessage) ----

    @Test
    void pdoEnvelopeCarriesProtocolTypeAndRequest() {
        Wa.MessageKey key = Wa.MessageKey.newBuilder()
            .setRemoteJid("123@s.whatsapp.net").setFromMe(false).setId("MSG1").build();
        Wa.Message.PeerDataOperationRequestMessage pdo =
            PeerDataOperations.buildPlaceholderResendRequest(key);
        Wa.Message msg = PeerDataOperations.buildPeerDataOperationMessage(pdo);
        assertThat(msg.hasProtocolMessage()).isTrue();
        assertThat(msg.getProtocolMessage().getType())
            .isEqualTo(Wa.Message.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_MESSAGE);
        Wa.Message.PeerDataOperationRequestMessage out =
            msg.getProtocolMessage().getPeerDataOperationRequestMessage();
        assertThat(out.getPeerDataOperationRequestType())
            .isEqualTo(Wa.Message.PeerDataOperationRequestType.PLACEHOLDER_MESSAGE_RESEND);
        assertThat(out.getPlaceholderMessageResendRequestCount()).isEqualTo(1);
        assertThat(out.getPlaceholderMessageResendRequest(0).getMessageKey().getId()).isEqualTo("MSG1");
    }

    @Test
    void pdoStanzaAttributesAndMetaNode() {
        assertThat(PeerDataOperations.peerAttributes())
            .containsEntry("category", "peer")
            .containsEntry("push_priority", "high_force");
        BinaryNode meta = PeerDataOperations.peerMetaNode();
        assertThat(meta.tag()).isEqualTo("meta");
        assertThat(meta.attr("appdata")).isEqualTo("default");
        BinaryNode stanza = new BinaryNode("message",
            Map.of("to", "123@s.whatsapp.net", "id", "3EB0X", "type", "text"), List.of());
        BinaryNode withAttrs = MessageSender.withAdditionalAttributes(stanza, PeerDataOperations.peerAttributes());
        assertThat(withAttrs.attr("category")).isEqualTo("peer");
        assertThat(withAttrs.attr("push_priority")).isEqualTo("high_force");
        assertThat(withAttrs.attr("to")).isEqualTo("123@s.whatsapp.net");
        // Original stanza is never mutated.
        assertThat(stanza.attr("category")).isNull();
        // Supplied attributes win on conflict (Baileys spreads them over the base attrs).
        BinaryNode overridden = MessageSender.withAdditionalAttributes(stanza, Map.of("type", "peer"));
        assertThat(overridden.attr("type")).isEqualTo("peer");
        assertThat(MessageSender.withAdditionalAttributes(stanza, Map.of())).isSameAs(stanza);
        assertThat(MessageSender.withAdditionalAttributes(stanza, null)).isSameAs(stanza);
    }

    @Test
    void historySyncOnDemandBuilderMirrorsFetchMessageHistory() {
        Wa.Message.PeerDataOperationRequestMessage pdo =
            PeerDataOperations.buildHistorySyncOnDemandRequest(
                "123@s.whatsapp.net", "OLD1", true, 50, 1_700_000_000_000L);
        assertThat(pdo.getPeerDataOperationRequestType())
            .isEqualTo(Wa.Message.PeerDataOperationRequestType.HISTORY_SYNC_ON_DEMAND);
        var history = pdo.getHistorySyncOnDemandRequest();
        assertThat(history.getChatJid()).isEqualTo("123@s.whatsapp.net");
        assertThat(history.getOldestMsgId()).isEqualTo("OLD1");
        assertThat(history.getOldestMsgFromMe()).isTrue();
        assertThat(history.getOnDemandMsgCount()).isEqualTo(50);
        assertThat(history.getOldestMsgTimestampMs()).isEqualTo(1_700_000_000_000L);
    }

    // ---- Member label (Baileys lib/Socket/messages-send.js updateMemberLabel) ----

    @Test
    void memberLabelTruncatesTo30CharsAndSetsTimestamp() {
        String label31 = "abcdefghijklmnopqrstuvwxyz01234"; // 31 chars
        assertThat(label31).hasSize(31);
        Wa.Message msg = PeerDataOperations.buildMemberLabelMessage(label31, 1_700_000_000L);
        var protocol = msg.getProtocolMessage();
        assertThat(protocol.getType())
            .isEqualTo(Wa.Message.ProtocolMessage.Type.GROUP_MEMBER_LABEL_CHANGE);
        assertThat(protocol.getMemberLabel().getLabel()).isEqualTo(label31.substring(0, 30));
        assertThat(protocol.getMemberLabel().getLabel()).hasSize(30);
        assertThat(protocol.getMemberLabel().getLabelTimestamp()).isEqualTo(1_700_000_000L);
        assertThat(PeerDataOperations.truncateLabel("short")).isEqualTo("short");
        assertThat(PeerDataOperations.truncateLabel(null)).isNull();
    }

    @Test
    void memberLabelNullLeavesFieldUnsetAndMetaNodeMatches() {
        Wa.Message msg = PeerDataOperations.buildMemberLabelMessage(null, 42L);
        assertThat(msg.getProtocolMessage().getMemberLabel().hasLabel()).isFalse();
        assertThat(msg.getProtocolMessage().getMemberLabel().getLabelTimestamp()).isEqualTo(42L);
        BinaryNode meta = PeerDataOperations.memberLabelMetaNode();
        assertThat(meta.tag()).isEqualTo("meta");
        assertThat(meta.attr("tag_reason")).isEqualTo("user_update");
        assertThat(meta.attr("appdata")).isEqualTo("member_tag");
    }

    // ---- Placeholder resend cache / delay (Baileys lib/Socket/messages-recv.js) ----

    @Test
    void placeholderConstantsMatchBaileysDelays() {
        assertThat(PeerDataOperations.PLACEHOLDER_RESEND_DELAY_MS).isEqualTo(2000L);
        assertThat(PeerDataOperations.PLACEHOLDER_RESEND_TIMEOUT_MS).isEqualTo(8000L);
    }

    @Test
    void placeholderSecondClaimIsAlreadyRequestedAndSenderNotInvoked() {
        var manager = new PeerDataOperations.PlaceholderResendManager();
        assertThat(manager.tryClaim("MSG1", "original")).isTrue();
        assertThat(manager.stored("MSG1")).isEqualTo("original");
        assertThat(manager.tryClaim("MSG1", "other")).isFalse();
        AtomicBoolean senderRan = new AtomicBoolean(false);
        String result = manager.request("MSG1", null,
            millis -> CompletableFuture.completedFuture(null),
            () -> {
                senderRan.set(true);
                return CompletableFuture.completedFuture("SENT");
            }).join();
        assertThat(result).isNull();
        assertThat(senderRan).isFalse();
    }

    @Test
    void placeholderNullDataStoresTrueSentinel() {
        var manager = new PeerDataOperations.PlaceholderResendManager();
        assertThat(manager.tryClaim("MSG2", null)).isTrue();
        assertThat(manager.stored("MSG2")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void placeholderSurvivingClaimSendsAfterInjectedDelay() {
        var manager = new PeerDataOperations.PlaceholderResendManager();
        // Fake delay: completes immediately, no sleep, and records the requested delay.
        long[] seenDelay = {-1L};
        String result = manager.request("MSG3", "data",
            millis -> {
                seenDelay[0] = millis;
                return CompletableFuture.completedFuture(null);
            },
            () -> CompletableFuture.completedFuture("PDO-ID")).join();
        assertThat(seenDelay[0]).isEqualTo(2000L);
        assertThat(result).isEqualTo("PDO-ID");
        assertThat(manager.isPending("MSG3")).isTrue();
    }

    @Test
    void placeholderResolvedDuringDelayReturnsResolvedWithoutSending() {
        var manager = new PeerDataOperations.PlaceholderResendManager();
        AtomicBoolean senderRan = new AtomicBoolean(false);
        String result = manager.request("MSG4", null,
            millis -> {
                // The real message arrives while the delay elapses.
                manager.resolve("MSG4");
                return CompletableFuture.completedFuture(null);
            },
            () -> {
                senderRan.set(true);
                return CompletableFuture.completedFuture("PDO-ID");
            }).join();
        assertThat(result).isEqualTo("RESOLVED");
        assertThat(senderRan).isFalse();
    }

    @Test
    void placeholderExpireDropsOnlyOnce() {
        var manager = new PeerDataOperations.PlaceholderResendManager();
        manager.tryClaim("MSG5", null);
        assertThat(manager.expire("MSG5")).isTrue();
        assertThat(manager.isPending("MSG5")).isFalse();
        assertThat(manager.expire("MSG5")).isFalse();
    }

    // ---- Reachout timelock fetch parsing (Baileys lib/Socket/socket.js) ----

    private static final String TIMELOCK_ENVELOPE =
        "{\"data\":{\"xwa2_fetch_account_reachout_timelock\":"
            + "{\"is_active\":true,\"time_enforcement_ends\":\"1700000000\","
            + "\"enforcement_type\":\"BIZ_QUALITY\"}}}";

    @Test
    void timelockFetchParsesActiveWithSecondsToMillis() {
        ConnectionUpdate.ReachoutTimeLock lock = ReachoutTimelock.parse(TIMELOCK_ENVELOPE);
        assertThat(lock).isNotNull();
        assertThat(lock.isActive()).isTrue();
        assertThat(lock.timeEnforcementEndsEpochMs()).isEqualTo(1_700_000_000_000L);
        assertThat(lock.enforcementType()).isEqualTo("BIZ_QUALITY");
        assertThat(ReachoutTimelock.QUERY_ID).isEqualTo("23983697327930364");
        assertThat(ReachoutTimelock.DATA_PATH).isEqualTo("xwa2_fetch_account_reachout_timelock");
        assertThat(ReachoutTimelock.VARIABLES_JSON).isEqualTo("{}");
    }

    @Test
    void timelockFetchZeroEndsIsAbsentAndMissingEnforcementDefaults() {
        ConnectionUpdate.ReachoutTimeLock lock = ReachoutTimelock.parse(
            "{\"data\":{\"xwa2_fetch_account_reachout_timelock\":"
                + "{\"is_active\":false,\"time_enforcement_ends\":\"0\"}}}");
        assertThat(lock.isActive()).isFalse();
        assertThat(lock.timeEnforcementEndsEpochMs()).isNull();
        assertThat(lock.enforcementType()).isEqualTo("DEFAULT");
    }

    @Test
    void timelockFetchMissingPayloadErrorsAndBlankYieldNull() {
        assertThat(ReachoutTimelock.parse("{\"data\":{}}")).isNull();
        assertThat(ReachoutTimelock.parse(
            "{\"errors\":[{\"message\":\"boom\"}],\"data\":null}")).isNull();
        assertThat(ReachoutTimelock.parse(null)).isNull();
        assertThat(ReachoutTimelock.parse("  ")).isNull();
    }

    @Test
    void timelockFetchAcceptsUnquotedNumericEnds() {
        ConnectionUpdate.ReachoutTimeLock lock = ReachoutTimelock.parse(
            "{\"data\":{\"xwa2_fetch_account_reachout_timelock\":"
                + "{\"is_active\":true,\"time_enforcement_ends\":1700000000}}}");
        assertThat(lock.timeEnforcementEndsEpochMs()).isEqualTo(1_700_000_000_000L);
        // Fetch path carries an unknown enforcement type verbatim (no enum validation here).
        ConnectionUpdate.ReachoutTimeLock other = ReachoutTimelock.parse(
            "{\"data\":{\"xwa2_fetch_account_reachout_timelock\":"
                + "{\"is_active\":true,\"enforcement_type\":\"SOMETHING_NEW\"}}}");
        assertThat(other.enforcementType()).isEqualTo("SOMETHING_NEW");
    }

    // ---- Public USync query (Baileys lib/Socket/socket.js executeUSyncQuery) ----

    @Test
    void genericUsyncBuildsBaileysStanzaShape() {
        var query = new UsyncQuery.GenericQuery(
            List.of(new BinaryNode("contact", Map.of(), null)),
            List.of(new BinaryNode("user", Map.of(),
                List.of(BinaryNode.text("contact", Map.of(), "+123")))),
            "background", "query");
        BinaryNode iq = UsyncQuery.buildGenericQuery("IQ1", "SID1", query);
        assertThat(iq.tag()).isEqualTo("iq");
        assertThat(iq.attr("xmlns")).isEqualTo("usync");
        assertThat(iq.attr("type")).isEqualTo("get");
        assertThat(iq.attr("to")).isEqualTo("s.whatsapp.net");
        BinaryNode usync = iq.child("usync");
        assertThat(usync.attr("context")).isEqualTo("background");
        assertThat(usync.attr("mode")).isEqualTo("query");
        assertThat(usync.attr("sid")).isEqualTo("SID1");
        assertThat(usync.attr("last")).isEqualTo("true");
        assertThat(usync.attr("index")).isEqualTo("0");
        assertThat(usync.childrenList().get(0).tag()).isEqualTo("query");
        assertThat(usync.childrenList().get(1).tag()).isEqualTo("list");
        assertThat(usync.child("query").childrenList().get(0).tag()).isEqualTo("contact");
    }

    @Test
    void genericUsyncDefaultsAndEmptyProtocolValidation() {
        var query = new UsyncQuery.GenericQuery(
            List.of(new BinaryNode("lid", Map.of(), null)), List.of(), null, "  ");
        BinaryNode iq = UsyncQuery.buildGenericQuery("IQ1", "SID1", query);
        assertThat(iq.child("usync").attr("context")).isEqualTo("interactive");
        assertThat(iq.child("usync").attr("mode")).isEqualTo("query");
        assertThatThrownBy(() -> UsyncQuery.buildGenericQuery("IQ1", "SID1",
            new UsyncQuery.GenericQuery(List.of(), List.of(), null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one protocol");
    }

    @Test
    void genericUsyncParseKeepsOnlyJidCarryingUsers() {
        BinaryNode result = new BinaryNode("iq", Map.of("type", "result", "id", "IQ1"),
            List.of(new BinaryNode("usync", Map.of(),
                List.of(new BinaryNode("list", Map.of(), List.of(
                    new BinaryNode("user", Map.of("jid", "123@s.whatsapp.net"),
                        List.of(new BinaryNode("contact", Map.of("type", "in"), null))),
                    new BinaryNode("user", Map.of(),
                        List.of(new BinaryNode("contact", Map.of(), null)))
                ))))));
        UsyncQuery.GenericResult parsed = UsyncQuery.parseGenericResult(result);
        assertThat(parsed).isNotNull();
        assertThat(parsed.userNodes()).hasSize(1);
        assertThat(parsed.userNodes().get(0).attr("jid")).isEqualTo("123@s.whatsapp.net");
        assertThat(UsyncQuery.parseGenericResult(
            new BinaryNode("iq", Map.of("type", "error"), List.of()))).isNull();
        UsyncQuery.GenericResult noUsync = UsyncQuery.parseGenericResult(
            new BinaryNode("iq", Map.of("type", "result"), List.of()));
        assertThat(noUsync.usyncNode()).isNull();
        assertThat(noUsync.userNodes()).isEmpty();
    }
}
