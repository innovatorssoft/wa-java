package id.jawa.domain.store;

import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.model.SignedPreKey;
import id.jawa.protocol.crypto.Bytes;
import id.jawa.protocol.crypto.KeyPair25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Increment 5: AuthCreds.myAppStateKeyId round-trips through both auth stores. */
class MyAppStateKeyIdPersistenceTest {

    private static final String KEY_ID_B64 =
        Bytes.toBase64("mykeyid1234567890".getBytes(StandardCharsets.UTF_8));

    private static AuthCreds creds(String myAppStateKeyId) {
        AuthCreds c = new AuthCreds();
        c.noiseKey = new KeyPair25519(new byte[32], new byte[32]);
        c.signedIdentityKey = new KeyPair25519(new byte[32], new byte[32]);
        c.signedPreKey = new SignedPreKey(1, new KeyPair25519(new byte[32], new byte[32]), new byte[64]);
        c.registrationId = 12345;
        c.advSecretKey = new byte[32];
        c.meJid = "62895416602000@s.whatsapp.net";
        c.account = new byte[]{1, 2, 3, 4};
        c.myAppStateKeyId = myAppStateKeyId;
        return c;
    }

    @Test
    void fileAuthStoreRoundTripsKeyId(@TempDir Path tempDir) throws Exception {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("creds.session"));
        store.save(creds(KEY_ID_B64));
        assertThat(store.load().myAppStateKeyId).isEqualTo(KEY_ID_B64);
    }

    @Test
    void fileAuthStoreLoadsNullForLegacyCredsWithoutKeyId(@TempDir Path tempDir) throws Exception {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("creds.session"));
        store.save(creds(null));
        assertThat(store.load().myAppStateKeyId).isNull();
    }

    @Test
    void sqliteAuthStoreRoundTripsKeyId(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("creds.db");
        try (SqliteAuthStore store = new SqliteAuthStore(db)) {
            store.save(creds(KEY_ID_B64));
            assertThat(store.load().myAppStateKeyId).isEqualTo(KEY_ID_B64);
        }
        // Reopened database: the field survives a restart.
        try (SqliteAuthStore reopened = new SqliteAuthStore(db)) {
            assertThat(reopened.load().myAppStateKeyId).isEqualTo(KEY_ID_B64);
        }
    }

    @Test
    void sqliteAuthStoreLoadsNullForLegacyCredsWithoutKeyId(@TempDir Path tempDir) throws Exception {
        try (SqliteAuthStore store = new SqliteAuthStore(tempDir.resolve("creds.db"))) {
            store.save(creds(null));
            assertThat(store.load().myAppStateKeyId).isNull();
        }
    }
}
