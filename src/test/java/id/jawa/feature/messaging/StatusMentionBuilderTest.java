package id.jawa.feature.messaging;

import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusMentionBuilderTest {

    private static Wa.MessageKey statusKey() {
        return Wa.MessageKey.newBuilder()
            .setRemoteJid("status@broadcast").setFromMe(true).setId("STATUS1").build();
    }

    @Test
    void expandReplacesGroupsWithParticipantsAndDedupes() {
        Map<String, List<String>> groups = Map.of(
            "123@g.us", List.of("111@s.whatsapp.net", "222@s.whatsapp.net"));
        List<String> expanded = StatusMentionBuilder.expandMentionTargets(
            List.of("123@g.us", "222@s.whatsapp.net", "333@s.whatsapp.net", "123@g.us"),
            groups::get);
        assertThat(expanded).containsExactly(
            "111@s.whatsapp.net", "222@s.whatsapp.net", "333@s.whatsapp.net");
        assertThat(StatusMentionBuilder.expandMentionTargets(null, groups::get)).isEmpty();
        assertThat(StatusMentionBuilder.expandMentionTargets(
            List.of("999@g.us"), groups::get)).isEmpty();
    }

    @Test
    void mentionedUsersMetaListsOriginalTargetsNotExpanded() {
        BinaryNode meta = StatusMentionBuilder.mentionedUsersMetaNode(
            List.of("123@g.us", "333@s.whatsapp.net"));
        assertThat(meta.tag()).isEqualTo("meta");
        BinaryNode mentioned = meta.child("mentioned_users");
        assertThat(mentioned).isNotNull();
        assertThat(mentioned.children("to")).hasSize(2);
        assertThat(mentioned.children("to").get(0).attr("jid")).isEqualTo("123@g.us");
        assertThat(mentioned.children("to").get(1).attr("jid")).isEqualTo("333@s.whatsapp.net");
    }

    @Test
    void mentionMetaNodeDiffersForGroupsAndUsers() {
        assertThat(StatusMentionBuilder.mentionMetaNode("123@g.us").attr("is_group_status_mention"))
            .isEqualTo("true");
        assertThat(StatusMentionBuilder.mentionMetaNode("333@s.whatsapp.net").attr("is_status_mention"))
            .isEqualTo("true");
        assertThat(StatusMentionBuilder.mentionMetaNode("333@s.whatsapp.net")
            .attr("is_group_status_mention")).isNull();
    }

    @Test
    void mentionMessageWrapsStatusKeyWithType25() {
        Wa.Message userMention = StatusMentionBuilder.statusMentionMessage(
            "333@s.whatsapp.net", statusKey());
        assertThat(userMention.hasStatusMentionMessage()).isTrue();
        assertThat(userMention.hasGroupStatusMentionMessage()).isFalse();
        Wa.Message.ProtocolMessage protocol = userMention.getStatusMentionMessage()
            .getMessage().getProtocolMessage();
        assertThat(protocol.getKey().getId()).isEqualTo("STATUS1");
        assertThat(protocol.getType())
            .isEqualTo(Wa.Message.ProtocolMessage.Type.STATUS_MENTION_MESSAGE);
        assertThat(userMention.getMessageContextInfo().getMessageSecret().size()).isEqualTo(32);

        Wa.Message groupMention = StatusMentionBuilder.statusMentionMessage("123@g.us", statusKey());
        assertThat(groupMention.hasGroupStatusMentionMessage()).isTrue();
        assertThat(groupMention.hasStatusMentionMessage()).isFalse();
        assertThatThrownBy(() -> StatusMentionBuilder.statusMentionMessage("333@s.whatsapp.net", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planBundlesExpandedListMetaAndPerTargetMentions() {
        Map<String, List<String>> groups = Map.of(
            "123@g.us", List.of("111@s.whatsapp.net", "222@s.whatsapp.net"));
        StatusMentionBuilder.StatusMentionPlan plan = StatusMentionBuilder.plan(
            List.of("123@g.us", "333@s.whatsapp.net"), groups::get, statusKey());
        assertThat(plan.statusJidList()).containsExactly(
            "111@s.whatsapp.net", "222@s.whatsapp.net", "333@s.whatsapp.net");
        assertThat(plan.mentions()).containsKeys("123@g.us", "333@s.whatsapp.net");
        assertThat(plan.mentions().get("123@g.us").message().hasGroupStatusMentionMessage()).isTrue();
        assertThat(plan.mentions().get("333@s.whatsapp.net").message().hasStatusMentionMessage())
            .isTrue();
        assertThat(plan.mentions().get("123@g.us").metaNode().attr("is_group_status_mention"))
            .isEqualTo("true");
        assertThat(StatusMentionBuilder.STATUS_JID).isEqualTo("status@broadcast");
        assertThat(StatusMentionBuilder.DEFAULT_DELAY_MS).isEqualTo(1500L);
    }
}
