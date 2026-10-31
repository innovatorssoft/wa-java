package id.ja_wa.feature.store;

import id.ja_wa.event.ContactsUpsert;
import id.ja_wa.event.GroupsUpsert;
import id.ja_wa.event.LabelsAssociation;
import id.ja_wa.event.LabelsEdit;
import id.ja_wa.event.PresenceInfo;
import id.ja_wa.event.PresenceUpdate;
import id.ja_wa.feature.messaging.MessageEncoder;
import id.ja_wa.feature.messaging.MessageReceiver;
import id.ja_wa.proto.Wa;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StoreJsonTest {

    private static MessageReceiver.Decoded decoded(String senderJid, String groupJid, String msgId,
                                                   String text, long timestamp) {
        Wa.Message proto = MessageEncoder.text(text);
        return new MessageReceiver.Decoded(senderJid, groupJid, msgId, "msg", proto, text,
            new MessageReceiver.InteractiveResponse("buttons", "btn1", null, "Btn 1"), timestamp);
    }

    private static InMemoryStore populated() {
        InMemoryStore store = new InMemoryStore();
        store.upsertMessage(decoded("111@s.whatsapp.net", null, "m1", "hello", 100), false);
        store.upsertMessage(decoded("222@s.whatsapp.net", "123@g.us", "m2", "group hi", 200), false);
        store.setChatFlags("111@s.whatsapp.net", true, false);
        store.upsertContact(new ContactsUpsert.Contact("111@s.whatsapp.net", "Alice", "alice",
            null, "111", "https://example.invalid/pic.jpg"));
        store.upsertGroup(new GroupsUpsert.GroupInfo("123@g.us", "Team", "999@s.whatsapp.net",
            null, null, List.of("111@s.whatsapp.net", "222@s.whatsapp.net")));
        store.upsertLidMapping("abc@lid", "111@s.whatsapp.net");
        store.applyReaction("111@s.whatsapp.net", "m1", false, null, "👍", "222@s.whatsapp.net");
        store.upsertPresence(new PresenceUpdate("111@s.whatsapp.net",
            Map.of("222@s.whatsapp.net", new PresenceInfo("available", 123L, null))));
        store.upsertLabel(new LabelsEdit("lbl1", "Work", "#ff0000", false, null));
        store.applyLabelAssociation(new LabelsAssociation("add", "label_jid",
            "111@s.whatsapp.net", "lbl1", null));
        store.setConnectionState("open");
        return store;
    }

    @Test
    void roundTripPreservesEverything() {
        InMemoryStore restored = StoreJson.fromJson(StoreJson.toJson(populated()));

        assertThat(restored.connectionState()).isEqualTo("open");
        assertThat(restored.chatCount()).isEqualTo(2);
        InMemoryStore.StoredChat chat = restored.getChat("111@s.whatsapp.net");
        assertThat(chat.unreadCount()).isEqualTo(1);
        assertThat(chat.pinned()).isTrue();
        assertThat(chat.lastMessageTimestamp()).isEqualTo(100L);

        List<MessageReceiver.Decoded> msgs = restored.messages("111@s.whatsapp.net");
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).msgId()).isEqualTo("m1");
        assertThat(msgs.get(0).text()).isEqualTo("hello");
        assertThat(msgs.get(0).message().getConversation()).isEqualTo("hello");
        assertThat(msgs.get(0).interactive().selectedId()).isEqualTo("btn1");
        assertThat(restored.messages("123@g.us")).hasSize(1);

        assertThat(restored.getContact("111@s.whatsapp.net").name()).isEqualTo("Alice");
        assertThat(restored.getContact("111@s.whatsapp.net").imgUrl())
            .isEqualTo("https://example.invalid/pic.jpg");
        assertThat(restored.getGroup("123@g.us").subject()).isEqualTo("Team");
        assertThat(restored.getGroup("123@g.us").participantJids())
            .containsExactly("111@s.whatsapp.net", "222@s.whatsapp.net");
        assertThat(restored.pnForLid("abc@lid")).isEqualTo("111@s.whatsapp.net");
        assertThat(restored.reactionFor("111@s.whatsapp.net", "m1")).isEqualTo("👍");
        assertThat(restored.presences("111@s.whatsapp.net").get("222@s.whatsapp.net")
            .lastKnownPresence()).isEqualTo("available");
        assertThat(restored.getLabel("lbl1").name()).isEqualTo("Work");
        assertThat(restored.chatLabels("111@s.whatsapp.net")).hasSize(1);
        assertThat(restored.chatLabels("111@s.whatsapp.net").get(0).labelId()).isEqualTo("lbl1");
    }

    @Test
    void roundTripPreservesCapAndEscapedStrings() {
        InMemoryStore store = new InMemoryStore(2);
        store.upsertMessage(decoded("111@s.whatsapp.net", null, "a", "say \"hi\"\nline\\2", 1), false);
        store.upsertMessage(decoded("111@s.whatsapp.net", null, "b", "second", 2), false);
        store.upsertMessage(decoded("111@s.whatsapp.net", null, "c", "third", 3), false);

        InMemoryStore restored = StoreJson.fromJson(StoreJson.toJson(store));
        assertThat(restored.maxMessagesPerChat()).isEqualTo(2);
        assertThat(restored.messages("111@s.whatsapp.net").stream()
            .map(MessageReceiver.Decoded::msgId)).containsExactly("b", "c");

        InMemoryStore quoted = new InMemoryStore();
        quoted.upsertMessage(decoded("111@s.whatsapp.net", null, "q", "say \"hi\"\nline\\2", 1), false);
        assertThat(StoreJson.fromJson(StoreJson.toJson(quoted))
            .messages("111@s.whatsapp.net").get(0).text()).isEqualTo("say \"hi\"\nline\\2");
    }

    @Test
    void corruptEmptyAndNullInputYieldEmptyStoreWithoutThrowing() {
        assertThat(StoreJson.fromJson(null).chatCount()).isZero();
        assertThat(StoreJson.fromJson("").chatCount()).isZero();
        assertThat(StoreJson.fromJson("   ").chatCount()).isZero();
        assertThat(StoreJson.fromJson("{not json").chatCount()).isZero();
        assertThat(StoreJson.fromJson("[1,2,3]").chatCount()).isZero();
        assertThat(StoreJson.fromJson("{\"chats\": [{\"id\": 123}]}").chatCount()).isZero();
    }

    @Test
    void filePersistenceRoundTripsAndHandlesMissingAndCorrupt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("store.json");
        FileStorePersistence persistence = new FileStorePersistence(file);

        assertThat(persistence.load()).isNull();

        persistence.save(populated());
        InMemoryStore loaded = persistence.load();
        assertThat(loaded).isNotNull();
        assertThat(loaded.chatCount()).isEqualTo(2);
        assertThat(loaded.getContact("111@s.whatsapp.net").name()).isEqualTo("Alice");
        assertThat(loaded.messages("123@g.us")).hasSize(1);
        assertThat(Files.exists(dir.resolve("store.json.tmp"))).isFalse();

        Files.writeString(file, "{corrupt");
        assertThat(persistence.load().chatCount()).isZero();
        Files.writeString(file, "");
        assertThat(persistence.load().chatCount()).isZero();

        persistence.clear();
        assertThat(Files.exists(file)).isFalse();
    }
}
