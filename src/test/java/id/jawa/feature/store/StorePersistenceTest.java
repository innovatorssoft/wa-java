package id.jawa.feature.store;

import id.jawa.event.ContactsUpsert;
import id.jawa.event.LabelsEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorePersistenceTest {

    @Test
    void saveLoadRoundTripViaLifecycleMethods(@TempDir Path dir) throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.upsertContact(new ContactsUpsert.Contact("111@s.whatsapp.net", "Jane", "jane",
            null, "111", "https://example.invalid/pic.jpg"));
        store.ensureChat("111@s.whatsapp.net", 12345L);
        store.upsertLabel(new LabelsEdit("42", "Work", "3", false, null));

        Path file = dir.resolve("store.json");
        StorePersistence.saveStore(store, file);
        assertThat(file).exists();

        InMemoryStore loaded = StorePersistence.loadStore(file);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getContact("111@s.whatsapp.net").imgUrl())
            .isEqualTo("https://example.invalid/pic.jpg");
        assertThat(loaded.getChat("111@s.whatsapp.net")).isNotNull();
        assertThat(loaded.getChat("111@s.whatsapp.net").lastMessageTimestamp()).isEqualTo(12345L);
        assertThat(loaded.getLabel("42").name()).isEqualTo("Work");
    }

    @Test
    void loadMissingFileReturnsNullAndEmptyStoreRoundTrips(@TempDir Path dir) throws Exception {
        assertThat(StorePersistence.loadStore(dir.resolve("absent.json"))).isNull();
        Path file = dir.resolve("empty.json");
        StorePersistence.saveStore(new InMemoryStore(), file);
        InMemoryStore loaded = StorePersistence.loadStore(file);
        assertThat(loaded).isNotNull();
        assertThat(loaded.chatCount()).isZero();
        assertThat(loaded.contactCount()).isZero();
    }

    @Test
    void saveLoadRejectNulls() {
        assertThatThrownBy(() -> StorePersistence.saveStore(null, Path.of("x.json")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorePersistence.saveStore(new InMemoryStore(), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorePersistence.loadStore(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cachedContactImgUrlReturnsCachedOnly() {
        InMemoryStore store = new InMemoryStore();
        // Unknown contact: null, not a fetch (the store has no transport).
        assertThat(store.cachedContactImgUrl("999@s.whatsapp.net")).isNull();
        store.upsertContact(new ContactsUpsert.Contact("111@s.whatsapp.net", "Jane", null, null, null));
        assertThat(store.cachedContactImgUrl("111@s.whatsapp.net")).isNull();
        store.upsertContact(new ContactsUpsert.Contact("111@s.whatsapp.net", "Jane", null,
            null, null, "https://example.invalid/pic.jpg"));
        assertThat(store.cachedContactImgUrl("111@s.whatsapp.net"))
            .isEqualTo("https://example.invalid/pic.jpg");
        // 'removed' clears the cache; 'changed' keeps the old URL for the caller to refetch.
        assertThat(store.updateContactImgUrl("111@s.whatsapp.net", "removed")).isTrue();
        assertThat(store.cachedContactImgUrl("111@s.whatsapp.net")).isNull();
        assertThat(store.cachedContactImgUrl(null)).isNull();
    }
}
