package id.jawa.feature.messaging;

import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the status-mention send construction (Increment 4, Workstream B):
 * the {@link StatusMentionBuilder} plan combined with the
 * {@link MessageSender} additional-nodes stanza support. No live send
 * is attempted here — {@link id.jawa.client.JaWaClient#sendStatusMention}
 * needs a connected client with device queries and sessions; its
 * inter-target delay is injectable (0 skips sleeping) for that reason.
 * What is verified here is exactly what the send path relays: the
 * status stanza carries the {@code mentioned_users} meta node listing
 * the original targets, and each per-target mention message carries its
 * meta node and a type-25 protocol message pointing at the status key.
 */
class StatusMentionSendTest {

    private static Wa.MessageKey statusKey() {
        return Wa.MessageKey.newBuilder()
            .setRemoteJid("status@broadcast").setFromMe(true).setId("STATUS1").build();
    }

    @Test
    void statusStanzaCarriesMentionedUsersMetaForOriginalTargets() {
        StatusMentionBuilder.StatusMentionPlan plan = StatusMentionBuilder.plan(
            List.of("123@g.us", "333@s.whatsapp.net"),
            group -> "123@g.us".equals(group)
                ? List.of("111@s.whatsapp.net", "222@s.whatsapp.net") : List.of(),
            statusKey());
        assertThat(plan.statusJidList()).containsExactly(
            "111@s.whatsapp.net", "222@s.whatsapp.net", "333@s.whatsapp.net");

        BinaryNode base = BinaryNode.children("message",
            Map.of("to", "status@broadcast", "id", "STATUS1", "type", "text"),
            List.of(BinaryNode.children("participants", Map.of(), List.of())));
        BinaryNode stanza = MessageSender.withAdditionalNodes(base, List.of(plan.mentionedUsersMeta()));
        assertThat(base.childrenList()).hasSize(1);
        assertThat(stanza.childrenList()).hasSize(2);
        BinaryNode meta = stanza.childrenList().get(1);
        assertThat(meta.tag()).isEqualTo("meta");
        BinaryNode mentioned = meta.child("mentioned_users");
        assertThat(mentioned).isNotNull();
        assertThat(mentioned.children("to")).hasSize(2);
        assertThat(mentioned.children("to").get(0).attr("jid")).isEqualTo("123@g.us");
        assertThat(mentioned.children("to").get(1).attr("jid")).isEqualTo("333@s.whatsapp.net");
    }

    @Test
    void perTargetMentionsCarryMetaNodesAndType25ProtocolMessages() {
        StatusMentionBuilder.StatusMentionPlan plan = StatusMentionBuilder.plan(
            List.of("123@g.us", "333@s.whatsapp.net"),
            group -> List.of(),
            statusKey());
        StatusMentionBuilder.Mention groupMention = plan.mentions().get("123@g.us");
        StatusMentionBuilder.Mention userMention = plan.mentions().get("333@s.whatsapp.net");
        assertThat(groupMention).isNotNull();
        assertThat(userMention).isNotNull();
        assertThat(groupMention.metaNode().attr("is_group_status_mention")).isEqualTo("true");
        assertThat(userMention.metaNode().attr("is_status_mention")).isEqualTo("true");
        assertThat(groupMention.message().hasGroupStatusMentionMessage()).isTrue();
        assertThat(userMention.message().hasStatusMentionMessage()).isTrue();
        Wa.Message.ProtocolMessage groupProtocol = groupMention.message()
            .getGroupStatusMentionMessage().getMessage().getProtocolMessage();
        Wa.Message.ProtocolMessage userProtocol = userMention.message()
            .getStatusMentionMessage().getMessage().getProtocolMessage();
        assertThat(groupProtocol.getType())
            .isEqualTo(Wa.Message.ProtocolMessage.Type.STATUS_MENTION_MESSAGE);
        assertThat(userProtocol.getType())
            .isEqualTo(Wa.Message.ProtocolMessage.Type.STATUS_MENTION_MESSAGE);
        assertThat(groupProtocol.getKey().getId()).isEqualTo("STATUS1");
        assertThat(userProtocol.getKey().getId()).isEqualTo("STATUS1");
        assertThat(userMention.message().getMessageContextInfo().getMessageSecret().size())
            .isEqualTo(32);
    }

    @Test
    void withAdditionalNodesIsPureAndHandlesEdgeCases() {
        BinaryNode base = BinaryNode.children("message", Map.of("to", "x"), List.of());
        assertThat(MessageSender.withAdditionalNodes(base, List.of())).isSameAs(base);
        assertThat(MessageSender.withAdditionalNodes(base, null)).isSameAs(base);
        BinaryNode text = BinaryNode.text("message", Map.of(), "body");
        assertThat(MessageSender.withAdditionalNodes(text, List.of(BinaryNode.of("meta"))))
            .isSameAs(text);
        BinaryNode appended = MessageSender.withAdditionalNodes(base,
            List.of(BinaryNode.of("meta"), BinaryNode.of("biz")));
        assertThat(appended.childrenList()).hasSize(2);
        assertThat(base.childrenList()).isEmpty();
    }
}
