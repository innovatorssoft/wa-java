package id.jawa.event;

import id.jawa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationParsersTest {

    private static BinaryNode notification(String type, String from, BinaryNode... children) {
        return BinaryNode.children("notification",
            Map.of("type", type, "from", from),
            List.of(children));
    }

    private static BinaryNode notificationWithParticipant(String type, String from, String participant,
                                                          String participantPn, BinaryNode... children) {
        return BinaryNode.children("notification",
            Map.of("type", type, "from", from, "participant", participant,
                "participant_pn", participantPn),
            List.of(children));
    }

    @Test
    void groupCreateEmitsChatsUpsertThenGroupsUpsert() {
        BinaryNode group = BinaryNode.children("group",
            Map.of("id", "123", "subject", "Team", "creation", "1700000000"),
            List.of(BinaryNode.of("participant", Map.of("jid", "111@s.whatsapp.net"))));
        BinaryNode node = notificationWithParticipant("w:gp2", "123@g.us",
            "999@s.whatsapp.net", "999@s.whatsapp.net",
            BinaryNode.children("create", Map.of(), List.of(group)));
        List<EventDispatch> events = GroupNotificationParser.parse(node);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(BaileysEventType.CHATS_UPSERT);
        assertThat(((ChatsUpsert) events.get(0).payload()).chatIds()).containsExactly("123@g.us");
        assertThat(events.get(1).type()).isEqualTo(BaileysEventType.GROUPS_UPSERT);
        GroupsUpsert.GroupInfo info = ((GroupsUpsert) events.get(1).payload()).groups().get(0);
        assertThat(info.subject()).isEqualTo("Team");
        assertThat(info.author()).isEqualTo("999@s.whatsapp.net");
        assertThat(info.participantJids()).containsExactly("111@s.whatsapp.net");
    }

    @Test
    void groupParticipantActionsMapLeaveToRemove() {
        BinaryNode add = BinaryNode.children("add", Map.of(),
            List.of(BinaryNode.of("participant", Map.of("jid", "222@s.whatsapp.net"))));
        List<EventDispatch> added = GroupNotificationParser.parse(
            notificationWithParticipant("w:gp2", "123@g.us", "111@s.whatsapp.net",
                "111@s.whatsapp.net", add));
        GroupParticipantsUpdate upd = (GroupParticipantsUpdate) added.get(0).payload();
        assertThat(upd.action()).isEqualTo("add");
        assertThat(upd.participants()).containsExactly("222@s.whatsapp.net");

        BinaryNode leave = BinaryNode.children("leave", Map.of(),
            List.of(BinaryNode.of("participant", Map.of("jid", "222@s.whatsapp.net"))));
        List<EventDispatch> left = GroupNotificationParser.parse(
            notificationWithParticipant("w:gp2", "123@g.us", "222@s.whatsapp.net",
                "222@s.whatsapp.net", leave));
        assertThat(((GroupParticipantsUpdate) left.get(0).payload()).action()).isEqualTo("remove");
    }

    @Test
    void groupJoinRequestActionsFollowActorRule() {
        BinaryNode created = BinaryNode.children("created_membership_requests",
            Map.of("request_method", "invite_link"),
            List.of(BinaryNode.of("participant", Map.of("jid", "5@lid", "phone_number", "555@s.whatsapp.net"))));
        List<EventDispatch> events = GroupNotificationParser.parse(
            notificationWithParticipant("w:gp2", "123@g.us", "111@s.whatsapp.net",
                "111@s.whatsapp.net", created));
        GroupJoinRequest req = (GroupJoinRequest) events.get(0).payload();
        assertThat(req.action()).isEqualTo("created");
        assertThat(req.participant()).isEqualTo("5@lid");
        assertThat(req.participantPn()).isEqualTo("555@s.whatsapp.net");
        assertThat(req.method()).isEqualTo("invite_link");

        BinaryNode revoked = BinaryNode.children("revoked_membership_requests", Map.of(),
            List.of(BinaryNode.of("participant", Map.of("jid", "111@s.whatsapp.net"))));
        List<EventDispatch> revokedEvents = GroupNotificationParser.parse(
            notificationWithParticipant("w:gp2", "123@g.us", "111@s.whatsapp.net",
                "111@s.whatsapp.net", revoked));
        assertThat(((GroupJoinRequest) revokedEvents.get(0).payload()).action()).isEqualTo("revoked");
    }

    @Test
    void groupChangeChildrenMapToGroupsUpdateFields() {
        List<EventDispatch> subject = GroupNotificationParser.parse(
            notification("w:gp2", "123@g.us", BinaryNode.of("subject", Map.of("subject", "New name"))));
        assertThat(((GroupsUpdate) subject.get(0).payload()).updates().get(0).subject())
            .isEqualTo("New name");

        List<EventDispatch> announce = GroupNotificationParser.parse(
            notification("w:gp2", "123@g.us", BinaryNode.of("announcement", Map.of())));
        assertThat(((GroupsUpdate) announce.get(0).payload()).updates().get(0).announce()).isTrue();

        List<EventDispatch> locked = GroupNotificationParser.parse(
            notification("w:gp2", "123@g.us", BinaryNode.of("locked", Map.of())));
        assertThat(((GroupsUpdate) locked.get(0).payload()).updates().get(0).restrict()).isTrue();

        List<EventDispatch> invite = GroupNotificationParser.parse(
            notification("w:gp2", "123@g.us", BinaryNode.of("invite", Map.of("code", "AbC123"))));
        assertThat(((GroupsUpdate) invite.get(0).payload()).updates().get(0).inviteCode())
            .isEqualTo("AbC123");

        BinaryNode approval = BinaryNode.children("membership_approval_mode", Map.of(),
            List.of(BinaryNode.of("group_join", Map.of("state", "on"))));
        List<EventDispatch> approvalEvents = GroupNotificationParser.parse(
            notification("w:gp2", "123@g.us", approval));
        assertThat(((GroupsUpdate) approvalEvents.get(0).payload()).updates().get(0).joinApprovalMode())
            .isTrue();

        List<EventDispatch> ephemeral = GroupNotificationParser.parse(
            notification("w:gp2", "123@g.us", BinaryNode.of("ephemeral", Map.of("expiration", "86400"))));
        assertThat(ephemeral).isEmpty();
    }

    @Test
    void newsletterChildrenMapToNewsletterEvents() {
        BinaryNode reaction = BinaryNode.children("reaction", Map.of("message_id", "42"),
            List.of(BinaryNode.text("reaction", Map.of(), "❤️")));
        BinaryNode view = BinaryNode.text("view", Map.of("message_id", "43"), "17");
        BinaryNode participant = BinaryNode.of("participant",
            Map.of("jid", "222@s.whatsapp.net", "action", "promote", "role", "ADMIN"));
        BinaryNode settings = BinaryNode.children("settings", Map.of(),
            List.of(BinaryNode.text("name", Map.of(), "Channel"),
                BinaryNode.text("description", Map.of(), "About")));
        BinaryNode update = BinaryNode.children("update", Map.of(), List.of(settings));
        BinaryNode node = BinaryNode.children("notification",
            Map.of("type", "newsletter", "from", "777@newsletter", "participant", "111@s.whatsapp.net"),
            List.of(reaction, view, participant, update));
        List<EventDispatch> events = NewsletterNotificationParser.parse(node);
        assertThat(events).hasSize(4);
        assertThat(((NewsletterReaction) events.get(0).payload()).reactionCode()).isEqualTo("❤️");
        assertThat(((NewsletterReaction) events.get(0).payload()).count()).isEqualTo(1);
        assertThat(((NewsletterView) events.get(1).payload()).count()).isEqualTo(17);
        assertThat(((NewsletterParticipantsUpdate) events.get(2).payload()).newRole()).isEqualTo("ADMIN");
        assertThat(((NewsletterParticipantsUpdate) events.get(2).payload()).author())
            .isEqualTo("111@s.whatsapp.net");
        assertThat(((NewsletterSettingsUpdate) events.get(3).payload()).name()).isEqualTo("Channel");
        assertThat(((NewsletterSettingsUpdate) events.get(3).payload()).description()).isEqualTo("About");
    }

    @Test
    void mediaRetryParsesErrorAndSuccessShapes() {
        BinaryNode withError = BinaryNode.children("notification",
            Map.of("type", "mediaretry", "id", "MSG1"),
            List.of(BinaryNode.of("rmr", Map.of("jid", "111@s.whatsapp.net", "from_me", "true")),
                BinaryNode.of("error", Map.of("code", "404"))));
        MessagesMediaUpdate errorUpdate = MediaRetryParser.parse(withError);
        assertThat(errorUpdate.updates().get(0).errorCode()).isEqualTo(404);
        assertThat(errorUpdate.updates().get(0).fromMe()).isTrue();

        BinaryNode success = BinaryNode.children("notification",
            Map.of("type", "mediaretry", "id", "MSG2"),
            List.of(BinaryNode.of("rmr", Map.of("jid", "123@g.us", "from_me", "false",
                "participant", "222@s.whatsapp.net"))));
        MessagesMediaUpdate successUpdate = MediaRetryParser.parse(success);
        assertThat(successUpdate.updates().get(0).errorCode()).isNull();
        assertThat(successUpdate.updates().get(0).participant()).isEqualTo("222@s.whatsapp.net");

        assertThat(MediaRetryParser.parse(BinaryNode.of("notification", Map.of()))).isNull();
    }

    @Test
    void blocklistParsesOneEventPerItem() {
        BinaryNode items = BinaryNode.children("blocklist", Map.of(),
            List.of(BinaryNode.of("item", Map.of("jid", "111@s.whatsapp.net", "action", "block")),
                BinaryNode.of("item", Map.of("jid", "222@s.whatsapp.net", "action", "unblock"))));
        BinaryNode node = BinaryNode.children("notification",
            Map.of("type", "account_sync"), List.of(items));
        List<BlocklistUpdate> updates = BlocklistParser.parse(node);
        assertThat(updates).hasSize(2);
        assertThat(updates.get(0).blocklist()).containsExactly("111@s.whatsapp.net");
        assertThat(updates.get(0).type()).isEqualTo("add");
        assertThat(updates.get(1).type()).isEqualTo("remove");
        assertThat(BlocklistParser.parse(BinaryNode.of("notification", Map.of()))).isEmpty();
    }

    @Test
    void pictureNotificationMapsSetAndDelete() {
        BinaryNode setNode = BinaryNode.children("notification",
            Map.of("type", "picture", "from", "111@s.whatsapp.net"),
            List.of(BinaryNode.of("set", Map.of("id", "pic1"))));
        assertThat(PictureNotificationParser.parse(setNode).updates().get(0).imgUrl())
            .isEqualTo("changed");
        BinaryNode delNode = BinaryNode.children("notification",
            Map.of("type", "picture", "from", "111@s.whatsapp.net"),
            List.of(BinaryNode.of("delete", Map.of())));
        assertThat(PictureNotificationParser.parse(delNode).updates().get(0).imgUrl())
            .isEqualTo("removed");
        assertThat(PictureNotificationParser.parse(BinaryNode.of("notification", Map.of()))).isNull();
    }
}
