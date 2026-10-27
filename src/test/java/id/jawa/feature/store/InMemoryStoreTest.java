package id.jawa.feature.store;

import id.jawa.event.BaileysEventType;
import id.jawa.event.ChatsDelete;
import id.jawa.event.ChatsUpdate;
import id.jawa.event.ChatsUpsert;
import id.jawa.event.ContactsUpsert;
import id.jawa.event.GroupsUpsert;
import id.jawa.event.LabelsAssociation;
import id.jawa.event.LabelsEdit;
import id.jawa.event.MessagesDelete;
import id.jawa.event.MessagesReaction;
import id.jawa.event.PresenceInfo;
import id.jawa.event.PresenceUpdate;
import id.jawa.event.WaEventBus;
import id.jawa.event.WaEventListener;
import id.jawa.feature.messaging.MessageReceiver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryStoreTest {

    private static MessageReceiver.Decoded decoded(String chatJid, String senderJid, String msgId, long timestamp) {
        // groupJid is derived by the caller: pass the chat as groupJid for groups,
        // or null groupJid with senderJid == chat for DMs.
        String groupJid = chatJid.endsWith("@g.us") ? chatJid : null;
        String sender = groupJid != null ? senderJid : chatJid;
        return new MessageReceiver.Decoded(sender, groupJid, msgId, null, null, null, null, timestamp);
    }

    @Test
    void chatsOrderPinnedThenUnarchivedThenNewest() {
        InMemoryStore store = new InMemoryStore();
        store.upsertMessage(decoded("111@s.whatsapp.net", null, "m1", 100));
        store.upsertMessage(decoded("222@s.whatsapp.net", null, "m2", 300));
        store.upsertMessage(decoded("333@s.whatsapp.net", null, "m3", 200));
        store.setChatFlags("111@s.whatsapp.net", true, false);
        store.setChatFlags("222@s.whatsapp.net", false, true);
        List<String> ids = store.chats().stream().map(InMemoryStore.StoredChat::id).toList();
        assertThat(ids).containsExactly(
            "111@s.whatsapp.net", "333@s.whatsapp.net", "222@s.whatsapp.net");
    }

    @Test
    void unreadCountsOnlyInboundAndMarkReadResets() {
        InMemoryStore store = new InMemoryStore();
        store.upsertMessage(decoded("111@s.whatsapp.net", null, "m1", 100), false);
        store.upsertMessage(decoded("111@s.whatsapp.net", null, "m2", 101), true);
        assertThat(store.getChat("111@s.whatsapp.net").unreadCount()).isEqualTo(1);
        assertThat(store.markChatRead("111@s.whatsapp.net")).isTrue();
        assertThat(store.getChat("111@s.whatsapp.net").unreadCount()).isZero();
        assertThat(store.markChatRead("missing@s.whatsapp.net")).isFalse();
    }

    @Test
    void messageCapDropsOldestFirst() {
        InMemoryStore store = new InMemoryStore(3);
        for (int i = 1; i <= 5; i++) {
            store.upsertMessage(decoded("111@s.whatsapp.net", null, "m" + i, i));
        }
        assertThat(store.messages("111@s.whatsapp.net")).hasSize(3);
        assertThat(store.messages("111@s.whatsapp.net").stream()
            .map(MessageReceiver.Decoded::msgId).toList())
            .containsExactly("m3", "m4", "m5");
        assertThatThrownBy(() -> new InMemoryStore(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadMessagesIsBeforeOnly() {
        InMemoryStore store = new InMemoryStore();
        for (int i = 1; i <= 5; i++) {
            store.upsertMessage(decoded("111@s.whatsapp.net", null, "m" + i, i));
        }
        assertThat(store.loadMessages("111@s.whatsapp.net", 2, null).stream()
            .map(MessageReceiver.Decoded::msgId).toList()).containsExactly("m4", "m5");
        assertThat(store.loadMessages("111@s.whatsapp.net", 2, "m4").stream()
            .map(MessageReceiver.Decoded::msgId).toList()).containsExactly("m2", "m3");
        assertThat(store.latestMessages("111@s.whatsapp.net", 2).stream()
            .map(MessageReceiver.Decoded::msgId).toList()).containsExactly("m5", "m4");
    }

    @Test
    void deleteChatAndClearChatBehaveDifferently() {
        InMemoryStore store = new InMemoryStore();
        store.upsertMessage(decoded("111@s.whatsapp.net", null, "m1", 1));
        assertThat(store.clearChat("111@s.whatsapp.net")).isTrue();
        assertThat(store.messages("111@s.whatsapp.net")).isEmpty();
        assertThat(store.getChat("111@s.whatsapp.net")).isNotNull();
        assertThat(store.deleteChat("111@s.whatsapp.net")).isTrue();
        assertThat(store.getChat("111@s.whatsapp.net")).isNull();
        assertThat(store.deleteChat("111@s.whatsapp.net")).isFalse();
    }

    @Test
    void boundBusReceivesChatAndMessageEvents() {
        InMemoryStore store = new InMemoryStore();
        WaEventBus bus = new WaEventBus();
        store.bind(bus);
        List<String> seen = new ArrayList<>();
        bus.addListener(new WaEventListener() {
            @Override
            public void onChatsUpsert(ChatsUpsert upsert) {
                seen.add("upsert:" + upsert.chatIds());
            }

            @Override
            public void onChatsUpdate(ChatsUpdate update) {
                seen.add("update:" + update.chatIds());
            }

            @Override
            public void onChatsDelete(ChatsDelete delete) {
                seen.add("delete:" + delete.chatIds());
            }

            @Override
            public void onMessagesDelete(MessagesDelete delete) {
                seen.add("msgdelete:" + delete.keys().get(0).id());
            }

            @Override
            public void onMessagesReaction(MessagesReaction reaction) {
                seen.add("reaction:" + reaction.reactions().get(0).reactionCode());
            }
        });
        store.upsertMessage(decoded("111@s.whatsapp.net", null, "m1", 1));
        store.upsertMessage(decoded("111@s.whatsapp.net", null, "m2", 2));
        store.applyReaction("111@s.whatsapp.net", "m1", false, null, "👍", "222@s.whatsapp.net");
        assertThat(store.reactionFor("111@s.whatsapp.net", "m1")).isEqualTo("👍");
        store.applyReaction("111@s.whatsapp.net", "m1", false, null, "", "222@s.whatsapp.net");
        assertThat(store.reactionFor("111@s.whatsapp.net", "m1")).isNull();
        store.deleteMessage("111@s.whatsapp.net", "m2", false, null);
        store.deleteChat("111@s.whatsapp.net");
        assertThat(seen).containsExactly(
            "upsert:[111@s.whatsapp.net]",
            "update:[111@s.whatsapp.net]",
            "reaction:👍",
            "reaction:",
            "msgdelete:m2",
            "delete:[111@s.whatsapp.net]");
    }

    @Test
    void contactsUpsertThenUpdateAndPictureHandling() {
        InMemoryStore store = new InMemoryStore();
        store.upsertContact(new ContactsUpsert.Contact("111@s.whatsapp.net", "Alice", null, null, "111"));
        assertThat(store.getContact("111@s.whatsapp.net").name()).isEqualTo("Alice");
        // Picture change for an unknown contact is skipped, like Baileys' store.
        assertThat(store.updateContactImgUrl("999@s.whatsapp.net", "changed")).isFalse();
        assertThat(store.updateContactImgUrl("111@s.whatsapp.net", "changed")).isTrue();
        // 'changed' does not overwrite the stored URL with the marker.
        assertThat(store.getContact("111@s.whatsapp.net").imgUrl()).isNull();
        assertThat(store.updateContactImgUrl("111@s.whatsapp.net", "removed")).isTrue();
        assertThat(store.contacts()).hasSize(1);
    }

    @Test
    void groupsAndParticipantChanges() {
        InMemoryStore store = new InMemoryStore();
        store.upsertGroup(new GroupsUpsert.GroupInfo("123@g.us", "Team", "111@s.whatsapp.net",
            null, null, List.of("111@s.whatsapp.net")));
        assertThat(store.updateGroupParticipants("123@g.us", List.of("222@s.whatsapp.net"), "add")).isTrue();
        assertThat(store.getGroup("123@g.us").participantJids())
            .containsExactly("111@s.whatsapp.net", "222@s.whatsapp.net");
        assertThat(store.updateGroupParticipants("123@g.us", List.of("111@s.whatsapp.net"), "remove")).isTrue();
        assertThat(store.getGroup("123@g.us").participantJids()).containsExactly("222@s.whatsapp.net");
        assertThat(store.updateGroupParticipants("123@g.us", List.of("222@s.whatsapp.net"), "promote")).isTrue();
        assertThat(store.getGroup("123@g.us").participantJids()).containsExactly("222@s.whatsapp.net");
        assertThat(store.updateGroupParticipants("999@g.us", List.of("x"), "add")).isFalse();
    }

    @Test
    void lidMappingEmitsOnlyOnChange() {
        InMemoryStore store = new InMemoryStore();
        WaEventBus bus = new WaEventBus();
        store.bind(bus);
        List<String> seen = new ArrayList<>();
        bus.addListener(new WaEventListener() {
            @Override
            public void onEvent(BaileysEventType type, Object payload) {
                if (type == BaileysEventType.LID_MAPPING_UPDATE) seen.add("lid");
            }
        });
        assertThat(store.upsertLidMapping("1@lid", "111@s.whatsapp.net")).isTrue();
        assertThat(store.upsertLidMapping("1@lid", "111@s.whatsapp.net")).isFalse();
        assertThat(store.upsertLidMapping("1@lid", "112@s.whatsapp.net")).isTrue();
        assertThat(store.pnForLid("1@lid")).isEqualTo("112@s.whatsapp.net");
        assertThat(seen).hasSize(2);
    }

    @Test
    void labelsCapAtTwentyAndAssociationsResolve() {
        InMemoryStore store = new InMemoryStore();
        for (int i = 0; i < 20; i++) {
            assertThat(store.upsertLabel(new LabelsEdit("l" + i, "Label " + i, "red", false, null))).isTrue();
        }
        assertThat(store.upsertLabel(new LabelsEdit("l20", "Too many", "red", false, null))).isFalse();
        assertThat(store.labels()).hasSize(20);
        store.applyLabelAssociation(new LabelsAssociation("add", "label_jid",
            "111@s.whatsapp.net", "l1", null));
        store.applyLabelAssociation(new LabelsAssociation("add", "label_message",
            "111@s.whatsapp.net", "l2", "m9"));
        assertThat(store.chatLabels("111@s.whatsapp.net")).hasSize(2);
        assertThat(store.messageLabels("m9")).containsExactly("l2");
        store.applyLabelAssociation(new LabelsAssociation("remove", "label_message",
            "111@s.whatsapp.net", "l2", "m9"));
        assertThat(store.messageLabels("m9")).isEmpty();
        assertThat(store.upsertLabel(new LabelsEdit("l1", "Label 1", "red", true, null))).isTrue();
        assertThat(store.getLabel("l1")).isNull();
    }

    @Test
    void presencesMergePerParticipant() {
        InMemoryStore store = new InMemoryStore();
        store.upsertPresence(new PresenceUpdate("123@g.us",
            Map.of("111@s.whatsapp.net", new PresenceInfo("available", null, null))));
        store.upsertPresence(new PresenceUpdate("123@g.us",
            Map.of("222@s.whatsapp.net", new PresenceInfo("composing", null, null))));
        assertThat(store.presences("123@g.us")).hasSize(2);
        assertThat(store.presences("123@g.us").get("222@s.whatsapp.net").lastKnownPresence())
            .isEqualTo("composing");
    }

    @Test
    void ensureChatCreatesOnceAndConnectionStateTracks() {
        InMemoryStore store = new InMemoryStore();
        assertThat(store.ensureChat("111@s.whatsapp.net", 42)).isTrue();
        assertThat(store.ensureChat("111@s.whatsapp.net", 43)).isFalse();
        assertThat(store.getChat("111@s.whatsapp.net").lastMessageTimestamp()).isEqualTo(42);
        assertThat(store.connectionState()).isEqualTo("close");
        store.setConnectionState("open");
        assertThat(store.connectionState()).isEqualTo("open");
    }
}
