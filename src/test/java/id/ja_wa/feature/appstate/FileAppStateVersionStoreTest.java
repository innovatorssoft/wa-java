package id.ja_wa.feature.appstate;

import id.ja_wa.domain.store.SqliteAuthStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Increment 5: durable AppStateVersionStore round-trips (file + SQLite backends). */
class FileAppStateVersionStoreTest {

    private static LtHashState state(long version) {
        byte[] hash = new byte[128];
        for (int i = 0; i < 128; i++) hash[i] = (byte) (i + version);
        byte[] idxMac = new byte[32];
        idxMac[0] = (byte) version;
        byte[] valMac = new byte[32];
        valMac[0] = (byte) (version + 1);
        return new LtHashState(version, hash, Map.of(LtHashState.mapKey(idxMac), valMac));
    }

    @Test
    void fileBackedRoundTripSurvivesReopen(@TempDir Path tempDir) {
        Path dir = tempDir.resolve("appstate-versions");
        FileAppStateVersionStore store = new FileAppStateVersionStore(dir);
        assertThat(store.load("regular")).isNull();
        store.save("regular", state(7));

        FileAppStateVersionStore reopened = new FileAppStateVersionStore(dir);
        LtHashState loaded = reopened.load("regular");
        assertThat(loaded).isNotNull();
        assertThat(loaded.version()).isEqualTo(7L);
        assertThat(loaded.hash()).isEqualTo(state(7).hash());
        // Note: Map.equals on byte[] values is identity-based, so
        // compare keys and value bytes separately.
        assertThat(loaded.indexValueMap().keySet()).isEqualTo(state(7).indexValueMap().keySet());
        assertThat(loaded.indexValueMap().values().iterator().next())
            .isEqualTo(state(7).indexValueMap().values().iterator().next());

        reopened.save("regular", state(8));
        assertThat(new FileAppStateVersionStore(dir).load("regular").version()).isEqualTo(8L);
    }

    @Test
    void sqliteBackedRoundTripSurvivesReopen(@TempDir Path tempDir) {
        Path db = tempDir.resolve("auth.db");
        try (SqliteAuthStore sqlite = new SqliteAuthStore(db)) {
            FileAppStateVersionStore store = new FileAppStateVersionStore(sqlite);
            store.save("critical_unblock_low", state(3));
            assertThat(store.load("critical_unblock_low").version()).isEqualTo(3L);
        }
        try (SqliteAuthStore sqlite = new SqliteAuthStore(db)) {
            LtHashState loaded = new FileAppStateVersionStore(sqlite).load("critical_unblock_low");
            assertThat(loaded).isNotNull();
            assertThat(loaded.version()).isEqualTo(3L);
            assertThat(loaded.hash()).isEqualTo(state(3).hash());
            assertThat(loaded.indexValueMap().keySet()).isEqualTo(state(3).indexValueMap().keySet());
            assertThat(loaded.indexValueMap().values().iterator().next())
                .isEqualTo(state(3).indexValueMap().values().iterator().next());
        }
    }

    @Test
    void malformedFileLoadsAsNoState(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("appstate-versions");
        Files.createDirectories(dir);
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("regular".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(dir.resolve(encoded + ".appver"), "version=not-a-number\nhash=AAAA\n");
        assertThat(new FileAppStateVersionStore(dir).load("regular")).isNull();
    }

    @Test
    void nullCollectionLoadsNullAndNullSaveThrows(@TempDir Path tempDir) {
        FileAppStateVersionStore store = new FileAppStateVersionStore(tempDir.resolve("v"));
        assertThat(store.load(null)).isNull();
        assertThatThrownBy(() -> store.save(null, state(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save("regular", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
