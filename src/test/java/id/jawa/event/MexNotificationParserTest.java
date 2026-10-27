package id.jawa.event;

import id.jawa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MexNotificationParserTest {

    private static BinaryNode notification(String from, BinaryNode... children) {
        return BinaryNode.children("notification",
            Map.of("type", "mex", "from", from), List.of(children));
    }

    private static BinaryNode update(String opName, String json) {
        return BinaryNode.text("update", Map.of("op_name", opName), json);
    }

    private static BinaryNode updateNoOp(String json) {
        return BinaryNode.text("update", Map.of(), json);
    }

    @Test
    void cappingNotificationEmitsRawJsonVerbatim() {
        BinaryNode node = notification("s.whatsapp.net",
            update("MessageCappingInfoNotification",
                "{\"data\":{\"xwa2_notify_new_chat_messages_capping_info_update\":"
                    + "{\"cap\":5,\"status\":\"ACTIVE\"}}}"));
        List<EventDispatch> events = MexNotificationParser.parse(node);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(BaileysEventType.MESSAGE_CAPPING_UPDATE);
        assertThat(((MessageCappingUpdate) events.get(0).payload()).rawJson())
            .isEqualTo("{\"cap\":5,\"status\":\"ACTIVE\"}");
    }

    @Test
    void cappingNotificationWithoutPayloadEmitsNothing() {
        BinaryNode node = notification("s.whatsapp.net",
            update("MessageCappingInfoNotification", "{\"data\":{}}"));
        assertThat(MexNotificationParser.parse(node)).isEmpty();
    }

    @Test
    void linkedProfilesModernPathEmitsOneMappingPerAddedProfile() {
        BinaryNode node = notification("s.whatsapp.net",
            update("NotificationLinkedProfilesUpdates",
                "{\"data\":{\"xwa2_notify_linked_profiles\":{\"jid\":\"123@lid\","
                    + "\"added_profiles\":[\"456@s.whatsapp.net\",{\"pn\":\"789@s.whatsapp.net\"}]}}}"));
        List<EventDispatch> events = MexNotificationParser.parse(node);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(BaileysEventType.LID_MAPPING_UPDATE);
        assertThat(((LidMappingUpdate) events.get(0).payload()))
            .isEqualTo(new LidMappingUpdate("123@lid", "456@s.whatsapp.net"));
        assertThat(((LidMappingUpdate) events.get(1).payload()))
            .isEqualTo(new LidMappingUpdate("123@lid", "789@s.whatsapp.net"));
    }

    @Test
    void unknownOpNameIsIgnored() {
        BinaryNode node = notification("s.whatsapp.net",
            update("SomeFutureOp", "{\"data\":{\"x\":1}}"));
        assertThat(MexNotificationParser.parse(node)).isEmpty();
    }

    @Test
    void malformedJsonIsIgnoredNotThrown() {
        BinaryNode broken = notification("s.whatsapp.net",
            update("MessageCappingInfoNotification", "{not json"));
        assertThat(MexNotificationParser.parse(broken)).isEmpty();
        BinaryNode truncated = notification("s.whatsapp.net",
            update("MessageCappingInfoNotification", "{\"data\":"));
        assertThat(MexNotificationParser.parse(truncated)).isEmpty();
        BinaryNode empty = notification("s.whatsapp.net",
            update("MessageCappingInfoNotification", ""));
        assertThat(MexNotificationParser.parse(empty)).isEmpty();
    }

    @Test
    void gqlErrorsAndNullDataAreIgnored() {
        BinaryNode errors = notification("s.whatsapp.net",
            update("MessageCappingInfoNotification",
                "{\"errors\":[{\"message\":\"boom\"}],\"data\":{}}"));
        assertThat(MexNotificationParser.parse(errors)).isEmpty();
        BinaryNode nullData = notification("s.whatsapp.net",
            update("MessageCappingInfoNotification", "{\"data\":null}"));
        assertThat(MexNotificationParser.parse(nullData)).isEmpty();
    }

    @Test
    void missingOpNameFallsBackToLegacyPath() {
        BinaryNode node = notification("s.whatsapp.net",
            updateNoOp("{\"operation\":\"NotificationLinkedProfilesUpdates\","
                + "\"updates\":[{\"jid\":\"5@lid\",\"added_profiles\":[\"6@s.whatsapp.net\"]}]}"));
        List<EventDispatch> events = MexNotificationParser.parse(node);
        assertThat(events).hasSize(1);
        assertThat(((LidMappingUpdate) events.get(0).payload()))
            .isEqualTo(new LidMappingUpdate("5@lid", "6@s.whatsapp.net"));
    }

    @Test
    void legacyMexChildAdminPromoteAndNewsletterUpdate() {
        BinaryNode promote = notification("admin@s.whatsapp.net",
            BinaryNode.text("mex", Map.of(),
                "{\"operation\":\"NotificationNewsletterAdminPromote\","
                    + "\"updates\":[{\"jid\":\"chan@newsletter\",\"user\":\"9@s.whatsapp.net\"}]}"));
        List<EventDispatch> events = MexNotificationParser.parse(promote);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type())
            .isEqualTo(BaileysEventType.NEWSLETTER_PARTICIPANTS_UPDATE);
        NewsletterParticipantsUpdate upd =
            (NewsletterParticipantsUpdate) events.get(0).payload();
        assertThat(upd.id()).isEqualTo("chan@newsletter");
        assertThat(upd.author()).isEqualTo("admin@s.whatsapp.net");
        assertThat(upd.user()).isEqualTo("9@s.whatsapp.net");
        assertThat(upd.action()).isEqualTo("promote");
        assertThat(upd.newRole()).isEqualTo("ADMIN");

        BinaryNode settings = notification("s.whatsapp.net",
            BinaryNode.text("mex", Map.of(),
                "{\"operation\":\"NotificationNewsletterUpdate\","
                    + "\"updates\":[{\"jid\":\"chan@newsletter\","
                    + "\"settings\":{\"name\":\"New Name\"}},"
                    + "{\"jid\":\"other@newsletter\",\"settings\":{}}]}"));
        List<EventDispatch> settingsEvents = MexNotificationParser.parse(settings);
        assertThat(settingsEvents).hasSize(1);
        NewsletterSettingsUpdate settingsUpd =
            (NewsletterSettingsUpdate) settingsEvents.get(0).payload();
        assertThat(settingsUpd.id()).isEqualTo("chan@newsletter");
        assertThat(settingsUpd.name()).isEqualTo("New Name");
    }

    @Test
    void legacyDemoteIsUnhandledInBaileysLegacySwitch() {
        BinaryNode demote = notification("s.whatsapp.net",
            BinaryNode.text("mex", Map.of(),
                "{\"operation\":\"NotificationNewsletterAdminDemote\","
                    + "\"updates\":[{\"jid\":\"chan@newsletter\",\"user\":\"9@s.whatsapp.net\"}]}"));
        assertThat(MexNotificationParser.parse(demote)).isEmpty();
    }

    @Test
    void reachoutTimelockEmitsPartialConnectionUpdate() {
        BinaryNode active = notification("s.whatsapp.net",
            update("NotificationUserReachoutTimelockUpdate",
                "{\"data\":{\"xwa2_notify_account_reachout_timelock\":"
                    + "{\"is_active\":true,\"time_enforcement_ends\":\"1700000060\","
                    + "\"enforcement_type\":\"BIZ_QUALITY\"}}}"));
        List<EventDispatch> events = MexNotificationParser.parse(active);
        assertThat(events).hasSize(1);
        ConnectionUpdate update = (ConnectionUpdate) events.get(0).payload();
        assertThat(update.connection()).isNull();
        assertThat(update.reachoutTimeLock().isActive()).isTrue();
        assertThat(update.reachoutTimeLock().timeEnforcementEndsEpochMs())
            .isEqualTo(1700000060L * 1000L);
        assertThat(update.reachoutTimeLock().enforcementType()).isEqualTo("BIZ_QUALITY");

        BinaryNode lifted = notification("s.whatsapp.net",
            update("NotificationUserReachoutTimelockUpdate",
                "{\"data\":{\"xwa2_notify_account_reachout_timelock\":{\"is_active\":false}}}"));
        List<EventDispatch> liftedEvents = MexNotificationParser.parse(lifted);
        assertThat(liftedEvents).hasSize(1);
        ConnectionUpdate.ReachoutTimeLock lock =
            ((ConnectionUpdate) liftedEvents.get(0).payload()).reachoutTimeLock();
        assertThat(lock.isActive()).isFalse();
        assertThat(lock.enforcementType()).isEqualTo("DEFAULT");
        assertThat(lock.timeEnforcementEndsEpochMs()).isNull();

        BinaryNode unknownType = notification("s.whatsapp.net",
            update("NotificationUserReachoutTimelockUpdate",
                "{\"data\":{\"xwa2_notify_account_reachout_timelock\":"
                    + "{\"is_active\":true,\"enforcement_type\":\"NOPE\"}}}"));
        ConnectionUpdate.ReachoutTimeLock fallback =
            ((ConnectionUpdate) MexNotificationParser.parse(unknownType).get(0).payload())
                .reachoutTimeLock();
        assertThat(fallback.enforcementType()).isEqualTo("DEFAULT");
        assertThat(fallback.timeEnforcementEndsEpochMs()).isNotNull();
    }

    @Test
    void nullNodeAndEmptyNotificationYieldNothing() {
        assertThat(MexNotificationParser.parse(null)).isEmpty();
        assertThat(MexNotificationParser.parse(notification("s.whatsapp.net"))).isEmpty();
    }
}
