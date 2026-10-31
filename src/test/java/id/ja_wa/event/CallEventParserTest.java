package id.ja_wa.event;

import id.ja_wa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CallEventParserTest {

    private static BinaryNode callNode(String infoTag, Map<String, String> infoAttrs,
                                       List<BinaryNode> infoChildren) {
        BinaryNode info = BinaryNode.children(infoTag, infoAttrs, infoChildren);
        return BinaryNode.children("call",
            Map.of("from", "123@g.us", "t", "1700000000"),
            List.of(info));
    }

    private static BinaryNode callNode(String infoTag, Map<String, String> infoAttrs) {
        return callNode(infoTag, infoAttrs, List.of());
    }

    @Test
    void offerVideoGroup() {
        BinaryNode node = callNode("offer",
            Map.of("call-id", "CALL1", "from", "456@s.whatsapp.net",
                "caller_pn", "789@s.whatsapp.net", "type", "group", "group-jid", "123@g.us"),
            List.of(BinaryNode.of("video")));

        CallEvent event = new CallEventParser().parse(node);

        assertThat(event.status()).isEqualTo(CallStatus.OFFER);
        assertThat(event.chatId()).isEqualTo("123@g.us");
        assertThat(event.from()).isEqualTo("456@s.whatsapp.net");
        assertThat(event.callerPn()).isEqualTo("789@s.whatsapp.net");
        assertThat(event.id()).isEqualTo("CALL1");
        assertThat(event.dateEpochSec()).isEqualTo(1700000000L);
        assertThat(event.dateEpochMs()).isEqualTo(1700000000L * 1000L);
        assertThat(event.offline()).isFalse();
        assertThat(event.isVideo()).isTrue();
        assertThat(event.isGroup()).isTrue();
        assertThat(event.groupJid()).isEqualTo("123@g.us");
        assertThat(event.latencyMs()).isNull();
    }

    @Test
    void offerNoticeMapsToOfferAndCallCreatorFallback() {
        BinaryNode node = callNode("offer_notice",
            Map.of("call-id", "CALL2", "call-creator", "456@s.whatsapp.net"));

        CallEvent event = new CallEventParser().parse(node);

        assertThat(event.status()).isEqualTo(CallStatus.OFFER);
        assertThat(event.from()).isEqualTo("456@s.whatsapp.net");
        assertThat(event.isVideo()).isFalse();
        assertThat(event.isGroup()).isFalse();
        assertThat(event.groupJid()).isNull();
    }

    @Test
    void terminateTimeoutVsPlain() {
        CallEvent timeout = new CallEventParser().parse(callNode("terminate",
            Map.of("call-id", "C1", "from", "456@s.whatsapp.net", "reason", "timeout")));
        assertThat(timeout.status()).isEqualTo(CallStatus.TIMEOUT);

        CallEvent plain = new CallEventParser().parse(callNode("terminate",
            Map.of("call-id", "C2", "from", "456@s.whatsapp.net")));
        assertThat(plain.status()).isEqualTo(CallStatus.TERMINATE);
    }

    @Test
    void acceptRejectPreacceptTransport() {
        assertThat(new CallEventParser().parse(callNode("accept",
            Map.of("call-id", "C1", "from", "a@s.whatsapp.net"))).status())
            .isEqualTo(CallStatus.ACCEPT);
        assertThat(new CallEventParser().parse(callNode("reject",
            Map.of("call-id", "C2", "from", "a@s.whatsapp.net"))).status())
            .isEqualTo(CallStatus.REJECT);
        assertThat(new CallEventParser().parse(callNode("preaccept",
            Map.of("call-id", "C3", "from", "a@s.whatsapp.net"))).status())
            .isEqualTo(CallStatus.PREACCEPT);
        assertThat(new CallEventParser().parse(callNode("transport",
            Map.of("call-id", "C4", "from", "a@s.whatsapp.net"))).status())
            .isEqualTo(CallStatus.TRANSPORT);
    }

    @Test
    void relayLatencyWithLatencyMsAndNonFiniteIgnored() {
        CallEvent withMs = new CallEventParser().parse(callNode("relaylatency",
            Map.of("call-id", "C1", "from", "a@s.whatsapp.net", "latency_ms", "42")));
        assertThat(withMs.status()).isEqualTo(CallStatus.RELAYLATENCY);
        assertThat(withMs.latencyMs()).isEqualTo(42L);

        CallEvent withHyphen = new CallEventParser().parse(callNode("relaylatency",
            Map.of("call-id", "C2", "from", "a@s.whatsapp.net", "latency-ms", "7")));
        assertThat(withHyphen.latencyMs()).isEqualTo(7L);

        CallEvent nonFinite = new CallEventParser().parse(callNode("relaylatency",
            Map.of("call-id", "C3", "from", "a@s.whatsapp.net", "latency", "NaN")));
        assertThat(nonFinite.latencyMs()).isNull();

        // Latency is only populated for relaylatency events.
        CallEvent offer = new CallEventParser().parse(callNode("offer",
            Map.of("call-id", "C4", "from", "a@s.whatsapp.net", "latency_ms", "99")));
        assertThat(offer.latencyMs()).isNull();
    }

    @Test
    void unknownChildMapsToRinging() {
        CallEvent event = new CallEventParser().parse(callNode("something-new",
            Map.of("call-id", "C1", "from", "a@s.whatsapp.net")));
        assertThat(event.status()).isEqualTo(CallStatus.RINGING);
    }

    @Test
    void offerBackfilledIntoLaterTerminate() {
        CallEventParser parser = new CallEventParser();
        parser.parse(callNode("offer",
            Map.of("call-id", "CALL9", "from", "456@s.whatsapp.net",
                "caller_pn", "789@s.whatsapp.net", "type", "group", "group-jid", "123@g.us"),
            List.of(BinaryNode.of("video"))));

        CallEvent terminate = parser.parse(callNode("terminate",
            Map.of("call-id", "CALL9", "from", "456@s.whatsapp.net")));

        assertThat(terminate.status()).isEqualTo(CallStatus.TERMINATE);
        assertThat(terminate.isVideo()).isTrue();
        assertThat(terminate.isGroup()).isTrue();
        assertThat(terminate.callerPn()).isEqualTo("789@s.whatsapp.net");
        assertThat(parser.cachedOfferCount()).isEqualTo(0);
    }

    @Test
    void cacheDeletedOnTerminalStatuses() {
        for (String terminalTag : List.of("reject", "accept", "terminate")) {
            CallEventParser parser = new CallEventParser();
            parser.parse(callNode("offer",
                Map.of("call-id", "CALLX", "from", "456@s.whatsapp.net"),
                List.of(BinaryNode.of("video"))));
            assertThat(parser.cachedOfferCount()).isEqualTo(1);
            parser.parse(callNode(terminalTag,
                Map.of("call-id", "CALLX", "from", "456@s.whatsapp.net")));
            assertThat(parser.cachedOfferCount()).isEqualTo(0);
            // A later event for the same id no longer backfills.
            CallEvent later = parser.parse(callNode("transport",
                Map.of("call-id", "CALLX", "from", "456@s.whatsapp.net")));
            assertThat(later.isVideo()).isNull();
            assertThat(later.isGroup()).isNull();
        }
        // timeout is terminal too.
        CallEventParser parser = new CallEventParser();
        parser.parse(callNode("offer",
            Map.of("call-id", "CALLY", "from", "456@s.whatsapp.net"),
            List.of(BinaryNode.of("video"))));
        parser.parse(callNode("terminate",
            Map.of("call-id", "CALLY", "from", "456@s.whatsapp.net", "reason", "timeout")));
        assertThat(parser.cachedOfferCount()).isEqualTo(0);
    }

    @Test
    void missingInfoChildOrIdReturnsNull() {
        CallEventParser parser = new CallEventParser();
        assertThat(parser.parse(BinaryNode.of("call"))).isNull();
        assertThat(parser.parse(callNode("offer",
            Map.of("from", "456@s.whatsapp.net")))).isNull();
        assertThat(parser.parse(null)).isNull();
    }

    @Test
    void offlineFlagFromStanza() {
        BinaryNode info = BinaryNode.children("offer",
            Map.of("call-id", "C1", "from", "a@s.whatsapp.net"), List.of());
        BinaryNode node = BinaryNode.children("call",
            Map.of("from", "123@g.us", "t", "1700000000", "offline", ""),
            List.of(info));
        assertThat(new CallEventParser().parse(node).offline()).isTrue();
    }
}
