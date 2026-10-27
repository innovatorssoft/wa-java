package id.jawa.event;

import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceEventParserTest {

    @Test
    void tagPresenceUnavailableWithLastSeenAndGroupCount() {
        BinaryNode node = new BinaryNode("presence",
            Map.of("from", "123@s.whatsapp.net", "type", "unavailable",
                "last", "1700000000", "count", "3"),
            null);

        PresenceUpdate update = PresenceEventParser.parse(node);

        assertThat(update.id()).isEqualTo("123@s.whatsapp.net");
        PresenceInfo info = update.presences().get("123@s.whatsapp.net");
        assertThat(info.lastKnownPresence()).isEqualTo("unavailable");
        assertThat(info.lastSeen()).isEqualTo(1700000000L);
        assertThat(info.groupOnlineCount()).isEqualTo(3);
    }

    @Test
    void tagPresenceDefaultsToAvailable() {
        BinaryNode node = new BinaryNode("presence",
            Map.of("from", "123@s.whatsapp.net"), null);

        PresenceUpdate update = PresenceEventParser.parse(node);

        assertThat(update.presences().get("123@s.whatsapp.net").lastKnownPresence())
            .isEqualTo("available");
        assertThat(update.presences().get("123@s.whatsapp.net").lastSeen()).isNull();
        assertThat(update.presences().get("123@s.whatsapp.net").groupOnlineCount()).isNull();
    }

    @Test
    void tagPresenceDenyLastSeenIsNull() {
        BinaryNode node = new BinaryNode("presence",
            Map.of("from", "123@s.whatsapp.net", "last", "deny"), null);

        PresenceUpdate update = PresenceEventParser.parse(node);

        assertThat(update.presences().get("123@s.whatsapp.net").lastSeen()).isNull();
    }

    @Test
    void participantFallsBackToFrom() {
        BinaryNode withParticipant = new BinaryNode("presence",
            Map.of("from", "123@g.us", "participant", "456@s.whatsapp.net"), null);
        assertThat(PresenceEventParser.parse(withParticipant).presences())
            .containsOnlyKeys("456@s.whatsapp.net");

        BinaryNode withoutParticipant = new BinaryNode("presence",
            Map.of("from", "123@s.whatsapp.net"), null);
        assertThat(PresenceEventParser.parse(withoutParticipant).presences())
            .containsOnlyKeys("123@s.whatsapp.net");
    }

    @Test
    void chatPresenceChildComposing() {
        BinaryNode node = BinaryNode.children("chatstate",
            Map.of("from", "123@s.whatsapp.net", "participant", "456@s.whatsapp.net"),
            List.of(BinaryNode.of("composing")));

        PresenceUpdate update = PresenceEventParser.parse(node);

        assertThat(update.id()).isEqualTo("123@s.whatsapp.net");
        assertThat(update.presences().get("456@s.whatsapp.net").lastKnownPresence())
            .isEqualTo("composing");
    }

    @Test
    void chatPresencePausedMapsToAvailable() {
        BinaryNode node = BinaryNode.children("chatstate",
            Map.of("from", "123@s.whatsapp.net"),
            List.of(BinaryNode.of("paused")));

        assertThat(PresenceEventParser.parse(node).presences()
            .get("123@s.whatsapp.net").lastKnownPresence()).isEqualTo("available");
    }

    @Test
    void chatPresenceAudioMediaMapsToRecording() {
        BinaryNode node = BinaryNode.children("chatstate",
            Map.of("from", "123@s.whatsapp.net"),
            List.of(BinaryNode.of("composing", Map.of("media", "audio"))));

        assertThat(PresenceEventParser.parse(node).presences()
            .get("123@s.whatsapp.net").lastKnownPresence()).isEqualTo("recording");
    }

    @Test
    void invalidNodeReturnsNull() {
        // Neither a tag-presence stanza nor a list of children.
        assertThat(PresenceEventParser.parse(BinaryNode.of("other"))).isNull();
        // Empty child list is not a valid chat-presence shape either.
        assertThat(PresenceEventParser.parse(
            BinaryNode.children("chatstate", Map.of("from", "123@s.whatsapp.net"), List.of())))
            .isNull();
        assertThat(PresenceEventParser.parse(null)).isNull();
    }
}
