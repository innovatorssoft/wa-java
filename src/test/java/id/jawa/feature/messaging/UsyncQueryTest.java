package id.jawa.feature.messaging;


import id.jawa.domain.model.ContactStatus;
import id.jawa.domain.model.DeviceInfo;
import id.jawa.protocol.codec.BinaryDecoder;
import id.jawa.protocol.codec.BinaryEncoder;
import id.jawa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsyncQueryTest {

    @Test
    void deviceListQueryHasExpectedShape() {
        BinaryNode iq = UsyncQuery.buildDeviceListQuery("iq1", "sid1",
            List.of("628111@s.whatsapp.net", "628222@s.whatsapp.net"));

        assertThat(iq.tag()).isEqualTo("iq");
        assertThat(iq.attrs())
            .containsEntry("xmlns", "usync")
            .containsEntry("type", "get")
            .containsEntry("id", "iq1");

        BinaryNode usync = iq.child("usync");
        assertThat(usync).isNotNull();
        assertThat(usync.attr("sid")).isEqualTo("sid1");
        assertThat(usync.attr("mode")).isEqualTo("query");
        assertThat(usync.attr("last")).isEqualTo("true");

        BinaryNode query = usync.child("query");
        assertThat(query.child("devices").attr("version")).isEqualTo("2");

        BinaryNode list = usync.child("list");
        assertThat(list.children("user")).hasSize(2);
        assertThat(list.children("user").get(0).attr("jid")).isEqualTo("628111@s.whatsapp.net");
    }

    @Test
    void roundTripsThroughBinaryCodec() {
        BinaryNode iq = UsyncQuery.buildDeviceListQuery("rt", "sid",
            List.of("628aaa@s.whatsapp.net"));
        byte[] bytes = BinaryEncoder.encode(iq);
        BinaryNode decoded = BinaryDecoder.decode(bytes);

        assertThat(decoded.tag()).isEqualTo("iq");
        assertThat(decoded.child("usync").child("list").child("user").attr("jid"))
            .isEqualTo("628aaa@s.whatsapp.net");
    }

    @Test
    void parseDeviceListResultExtractsDevices() {
        BinaryNode result = new BinaryNode("iq", Map.of("type", "result", "id", "x"), List.of(
            new BinaryNode("usync", Map.of("sid", "x", "mode", "query"), List.of(
                new BinaryNode("list", Map.of(), List.of(
                    new BinaryNode("user", Map.of("jid", "628111@s.whatsapp.net"), List.of(
                        new BinaryNode("devices", Map.of(), List.of(
                            new BinaryNode("device-list", Map.of(), List.of(
                                new BinaryNode("device", Map.of("id", "0",  "key-index", "0"), null),
                                new BinaryNode("device", Map.of("id", "56", "key-index", "1"), null)
                            ))
                        ))
                    ))
                ))
            ))
        ));

        Map<String, List<DeviceInfo>> parsed = UsyncQuery.parseDeviceListResult(result);
        assertThat(parsed).containsOnlyKeys("628111@s.whatsapp.net");
        List<DeviceInfo> devices = parsed.get("628111@s.whatsapp.net");
        assertThat(devices).hasSize(2);
        assertThat(devices.get(0).id()).isZero();
        assertThat(devices.get(1).id()).isEqualTo(56);
        assertThat(devices.get(1).keyIndex()).isEqualTo(1);
    }

    @Test
    void emptyResultParsesToEmptyMap() {
        BinaryNode result = new BinaryNode("iq",
            Map.of("type", "result", "id", "x"),
            List.of(new BinaryNode("usync", Map.of(), List.of())));
        assertThat(UsyncQuery.parseDeviceListResult(result)).isEmpty();
    }

    @Test
    void contactQueryHasExpectedShape() {
        BinaryNode iq = UsyncQuery.buildContactQuery("iq2", "sid2",
            List.of("628111", "628222@s.whatsapp.net", "12345@lid"));

        assertThat(iq.tag()).isEqualTo("iq");
        BinaryNode usync = iq.child("usync");
        assertThat(usync).isNotNull();
        assertThat(usync.attr("sid")).isEqualTo("sid2");

        BinaryNode query = usync.child("query");
        assertThat(query.child("contact")).isNotNull();
        assertThat(query.child("lid")).isNotNull();

        BinaryNode list = usync.child("list");
        assertThat(list.children("user")).hasSize(3);

        // Phone numbers must be serialized as <user><contact>+number</contact></user> without jid attribute
        BinaryNode u1 = list.children("user").get(0);
        assertThat(u1.attr("jid")).isNull();
        assertThat(u1.child("contact")).isNotNull();
        assertThat(u1.child("contact").textContent()).isEqualTo("+628111");

        BinaryNode u2 = list.children("user").get(1);
        assertThat(u2.attr("jid")).isNull();
        assertThat(u2.child("contact")).isNotNull();
        assertThat(u2.child("contact").textContent()).isEqualTo("+628222");

        // LID targets must be serialized as <user jid="..."><contact/></user>
        BinaryNode u3 = list.children("user").get(2);
        assertThat(u3.attr("jid")).isEqualTo("12345@lid");
    }

    @Test
    void parseContactResultExtractsStatuses() {
        BinaryNode result = new BinaryNode("iq", Map.of("type", "result", "id", "x"), List.of(
            new BinaryNode("usync", Map.of("sid", "x", "mode", "query"), List.of(
                new BinaryNode("list", Map.of(), List.of(
                    // Registered user with jid attribute and type="in"
                    new BinaryNode("user", Map.of("jid", "628111@s.whatsapp.net"), List.of(
                        BinaryNode.text("contact", Map.of("type", "in"), "+628111")
                    )),
                    // Unregistered user with NO jid attribute and type="out"
                    new BinaryNode("user", Map.of(), List.of(
                        BinaryNode.text("contact", Map.of("type", "out"), "+628222")
                    )),
                    // Registered user with LID and pn_jid
                    new BinaryNode("user", Map.of("jid", "999999@lid", "pn_jid", "628333@s.whatsapp.net"), List.of(
                        BinaryNode.text("contact", Map.of("type", "in"), "+628333")
                    ))
                ))
            ))
        ));

        Map<String, ContactStatus> parsed = UsyncQuery.parseContactResult(result);

        // Check registered user 628111
        assertThat(parsed).containsKeys("628111@s.whatsapp.net", "628111", "+628111");
        assertThat(parsed.get("628111@s.whatsapp.net").isOnWhatsApp()).isTrue();
        assertThat(parsed.get("628111").isOnWhatsApp()).isTrue();

        // Check unregistered user 628222 (verifying it is not dropped when jid attr is missing)
        assertThat(parsed).containsKeys("628222@s.whatsapp.net", "628222", "+628222");
        assertThat(parsed.get("628222@s.whatsapp.net").isOnWhatsApp()).isFalse();
        assertThat(parsed.get("628222").isOnWhatsApp()).isFalse();
        assertThat(parsed.get("+628222").isOnWhatsApp()).isFalse();

        // Check registered user with LID and pn_jid
        assertThat(parsed).containsKeys("628333@s.whatsapp.net", "628333", "999999@lid");
        assertThat(parsed.get("628333@s.whatsapp.net").isOnWhatsApp()).isTrue();
        assertThat(parsed.get("999999@lid").isOnWhatsApp()).isTrue();
    }

    @Test
    void nonResultIqThrows() {
        BinaryNode err = new BinaryNode("iq", Map.of("type", "error"), null);
        assertThatThrownBy(() -> UsyncQuery.parseDeviceListResult(err))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UsyncQuery.parseContactResult(err))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
