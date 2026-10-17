package id.jawa.protocol.codec;


import id.jawa.domain.model.Jid;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinaryCodecTest {

    @Test
    void singleByteTokensLoaded() {
        assertThat(WaTokens.SINGLE).hasSizeGreaterThan(200);
        assertThat(WaTokens.SINGLE[3]).isEqualTo("s.whatsapp.net");
        assertThat(WaTokens.SINGLE[25]).isEqualTo("iq");
        assertThat(WaTokens.SINGLE[84]).isEqualTo("version");
    }

    @Test
    void doubleByteTokensLoaded() {
        assertThat(WaTokens.DOUBLE.length).isEqualTo(4);
        for (String[] dict : WaTokens.DOUBLE) {
            assertThat(dict).hasSize(256);
        }
    }

    @Test
    void reverseIndex() {
        int[] msg = WaTokens.INDEX.get("message");
        assertThat(msg).isNotNull();
        assertThat(msg[0]).isEqualTo(-1);
        assertThat(WaTokens.SINGLE[msg[1]]).isEqualTo("message");

        int[] reaction = WaTokens.INDEX.get("reaction");
        assertThat(reaction).isNotNull();
        assertThat(reaction[0]).isGreaterThanOrEqualTo(0);
        assertThat(WaTokens.DOUBLE[reaction[0]][reaction[1]]).isEqualTo("reaction");
    }

    @Test
    void jidParsePlain() {
        Jid j = Jid.parse("628123@s.whatsapp.net");
        assertThat(j.user()).isEqualTo("628123");
        assertThat(j.server()).isEqualTo("s.whatsapp.net");
        assertThat(j.device()).isZero();
        assertThat(j.agent()).isZero();
        assertThat(j.asString()).isEqualTo("628123@s.whatsapp.net");
    }

    @Test
    void jidParseWithDevice() {
        Jid j = Jid.parse("628123:7@s.whatsapp.net");
        assertThat(j.user()).isEqualTo("628123");
        assertThat(j.device()).isEqualTo(7);
        assertThat(j.asString()).isEqualTo("628123:7@s.whatsapp.net");
    }

    @Test
    void jidParseWithAgent() {
        Jid j = Jid.parse("628123_1:7@s.whatsapp.net");
        assertThat(j.user()).isEqualTo("628123");
        assertThat(j.agent()).isEqualTo(1);
        assertThat(j.device()).isEqualTo(7);
        assertThat(j.asString()).isEqualTo("628123_1:7@s.whatsapp.net");
    }

    @Test
    void jidParseLid() {
        Jid j = Jid.parse("123456@lid");
        assertThat(j.domainType()).isEqualTo(Jid.DOMAIN_LID);
    }

    @Test
    void jidParseInvalidReturnsNull() {
        assertThat(Jid.parse("no-at-sign")).isNull();
        assertThat(Jid.parse(null)).isNull();
    }

    @Test
    void roundTripSingleByteToken() {
        BinaryNode in = BinaryNode.of("message", Map.of("from", "628@s.whatsapp.net", "type", "text"));
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);

        assertThat(out.tag()).isEqualTo("message");
        assertThat(out.attrs()).containsEntry("from", "628@s.whatsapp.net").containsEntry("type", "text");
        assertThat(out.content()).isNull();
    }

    @Test
    void roundTripTextContent() {
        BinaryNode in = BinaryNode.text("body", Map.of(), "halo dunia");
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);

        assertThat(out.tag()).isEqualTo("body");

        assertThat(out.textContent()).isEqualTo("halo dunia");
    }

    @Test
    void roundTripBytesContent() {
        byte[] payload = "ciphertext".getBytes(StandardCharsets.UTF_8);
        BinaryNode in = BinaryNode.bytes("enc", Map.of("type", "msg", "v", "2"), payload);
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);

        assertThat(out.tag()).isEqualTo("enc");
        assertThat(out.attrs()).containsEntry("type", "msg").containsEntry("v", "2");
        assertThat(out.content()).isInstanceOf(byte[].class);
        assertThat((byte[]) out.content()).containsExactly(payload);
    }

    @Test
    void roundTripNestedChildren() {
        BinaryNode in = BinaryNode.children("iq",
            Map.of("id", "abc", "type", "get", "to", "s.whatsapp.net"),
            List.of(
                BinaryNode.of("ping"),
                BinaryNode.text("note", Map.of(), "hi")
            ));
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);

        assertThat(out.tag()).isEqualTo("iq");
        assertThat(out.attrs()).containsEntry("id", "abc").containsEntry("type", "get").containsEntry("to", "s.whatsapp.net");
        List<BinaryNode> kids = out.childrenList();
        assertThat(kids).hasSize(2);
        assertThat(kids.get(0).tag()).isEqualTo("ping");
        assertThat(kids.get(1).tag()).isEqualTo("note");
        assertThat(kids.get(1).textContent()).isEqualTo("hi");
    }

    @Test
    void roundTripNibblePacked() {
        BinaryNode in = BinaryNode.text("number", Map.of(), "628123456789");
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);
        assertThat(out.content()).isEqualTo("628123456789");
    }

    @Test
    void roundTripNibblePackedOddLength() {
        BinaryNode in = BinaryNode.text("number", Map.of(), "62812345");
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);
        assertThat(out.content()).isEqualTo("62812345");
    }

    @Test
    void roundTripHexPacked() {
        BinaryNode in = BinaryNode.text("hash", Map.of(), "DEADBEEF42");
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);
        assertThat(out.content()).isEqualTo("DEADBEEF42");
    }

    @Test
    void emptyNodeProducesKnownBytes() {

        BinaryNode ping = BinaryNode.of("ping");
        byte[] bytes = BinaryEncoder.encode(ping);
        int pingTokenIndex = WaTokens.INDEX.get("ping")[1];

        assertThat(bytes).containsExactly(0x00, 0xF8, 0x01, pingTokenIndex);
    }

    @Test
    void largeBinaryContentUsesBinary20() {
        byte[] payload = new byte[300];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);
        BinaryNode in = BinaryNode.bytes("blob", Map.of(), payload);
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);
        assertThat((byte[]) out.content()).containsExactly(payload);

        assertThat(HexFormat.of().formatHex(bytes)).contains("fd");
    }

    @Test
    void decodeEmptyThrows() {
        assertThatThrownBy(() -> BinaryDecoder.decode(new byte[0]))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodeNullTagThrows() {
        assertThatThrownBy(() -> BinaryNode.of(null))
            .isInstanceOf(NullPointerException.class);
    }
}
