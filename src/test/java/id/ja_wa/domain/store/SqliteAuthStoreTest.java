package id.ja_wa.domain.store;

import id.ja_wa.domain.model.AuthCreds;
import id.ja_wa.domain.model.SignedPreKey;
import id.ja_wa.feature.appstate.AppStateKey;
import id.ja_wa.feature.appstate.FileAppStateKeyStorage;
import id.ja_wa.protocol.crypto.KeyPair25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.groups.SenderKeyName;
import org.whispersystems.libsignal.state.SessionRecord;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class SqliteAuthStoreTest {

    @Test
    public void testPureSqliteSessionStoreAllTables(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("pure_session.db");

        AuthCreds c = new AuthCreds();
        var kp1 = new KeyPair25519(new byte[32], new byte[32]);
        var kp2 = new KeyPair25519(new byte[32], new byte[32]);
        var kp3 = new KeyPair25519(new byte[32], new byte[32]);

        c.noiseKey = kp1;
        c.signedIdentityKey = kp2;
        c.signedPreKey = new SignedPreKey(1, kp3, new byte[64]);
        c.registrationId = 12345;
        c.advSecretKey = new byte[32];
        c.meJid = "62895416602000@s.whatsapp.net";
        c.meLid = "113112896245886@lid";
        c.pushName = "TestOwner";
        c.account = new byte[]{1, 2, 3, 4};
        c.platform = "android";

        try (SqliteAuthStore store = new SqliteAuthStore(dbPath)) {
            assertThat(store.load()).isNull();
            assertThat(store.isPaired()).isFalse();

            store.save(c);

            assertThat(store.isPaired()).isTrue();
            AuthCreds loaded = store.load();
            assertThat(loaded).isNotNull();
            assertThat(loaded.registrationId).isEqualTo(12345);

            FileSessionStorage sessionStorage = new FileSessionStorage(store);
            SignalProtocolAddress addr = new SignalProtocolAddress("628123456789", 0);
            SessionRecord sessionRecord = new SessionRecord();
            sessionStorage.put(addr, sessionRecord);
            assertThat(sessionStorage.contains(addr)).isTrue();
            assertThat(sessionStorage.size()).isEqualTo(1);

            FilePreKeyStorage preKeyStorage = new FilePreKeyStorage(store);
            KeyPair25519 preKey = new KeyPair25519(new byte[32], new byte[32]);
            preKeyStorage.put(101, preKey);
            assertThat(preKeyStorage.get(101)).isNotNull();
            assertThat(preKeyStorage.size()).isEqualTo(1);

            FileSenderKeyStorage senderKeyStorage = new FileSenderKeyStorage(store);
            SenderKeyName senderKeyName = new SenderKeyName("group123@g.us", addr);
            senderKeyStorage.put(senderKeyName, new byte[]{10, 20, 30});
            assertThat(senderKeyStorage.get(senderKeyName)).containsExactly((byte)10, (byte)20, (byte)30);
            assertThat(senderKeyStorage.size()).isEqualTo(1);

            FileAppStateKeyStorage appStateKeyStorage = new FileAppStateKeyStorage(store);
            byte[] appKeyId = new byte[]{1, 2, 3, 4};
            AppStateKey appStateKey = new AppStateKey(appKeyId, new byte[32]);
            appStateKeyStorage.put(appStateKey);
            assertThat(appStateKeyStorage.get(appKeyId)).isNotNull();
            assertThat(appStateKeyStorage.size()).isEqualTo(1);
        }

        try (SqliteAuthStore store2 = new SqliteAuthStore(dbPath)) {
            assertThat(store2.isPaired()).isTrue();

            FileSessionStorage sessionStorage2 = new FileSessionStorage(store2);
            SignalProtocolAddress addr = new SignalProtocolAddress("628123456789", 0);
            assertThat(sessionStorage2.contains(addr)).isTrue();

            FilePreKeyStorage preKeyStorage2 = new FilePreKeyStorage(store2);
            assertThat(preKeyStorage2.get(101)).isNotNull();

            FileSenderKeyStorage senderKeyStorage2 = new FileSenderKeyStorage(store2);
            SenderKeyName senderKeyName = new SenderKeyName("group123@g.us", addr);
            assertThat(senderKeyStorage2.get(senderKeyName)).containsExactly((byte)10, (byte)20, (byte)30);

            FileAppStateKeyStorage appStateKeyStorage2 = new FileAppStateKeyStorage(store2);
            byte[] appKeyId = new byte[]{1, 2, 3, 4};
            assertThat(appStateKeyStorage2.get(appKeyId)).isNotNull();
        }
    }

    @Test
    public void testMaintenanceMethods(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("maint.db");
        try (SqliteAuthStore store = new SqliteAuthStore(dbPath)) {
            assertThat(store.checkIntegrity()).isTrue();
            store.vacuum();

            var stats = store.getStats();
            assertThat(stats).containsKey("credentials");
            assertThat(stats.get("credentials")).isEqualTo(0);

            store.savePreKey(1, new KeyPair25519(new byte[32], new byte[32]));
            store.savePreKey(2, new KeyPair25519(new byte[32], new byte[32]));
            assertThat(store.getStats().get("prekeys")).isEqualTo(2);

            store.clearAll();
            assertThat(store.getStats().get("prekeys")).isEqualTo(0);
        }
    }
}
