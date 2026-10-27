package id.jawa.domain.model;

import id.jawa.protocol.crypto.Bytes;
import id.jawa.protocol.crypto.Curve25519;
import id.jawa.protocol.crypto.KeyPair25519;

import java.security.SecureRandom;

/**
 * Authentication credentials, cryptographic key pairs, and registration session state for a WhatsApp account.
 */
public final class AuthCreds {

    /**
     * Noise protocol ephemeral key pair used for establishing secure transport.
     */
    public KeyPair25519 noiseKey;

    /**
     * Long-term Curve25519 identity key pair identifying this device.
     */
    public KeyPair25519 signedIdentityKey;

    /**
     * Signed prekey for asynchronous end-to-end Signal message exchange.
     */
    public SignedPreKey signedPreKey;

    /**
     * Signal protocol registration identifier (16-bit integer).
     */
    public int registrationId;

    /**
     * Companion ADV secret key used in companion device linking.
     */
    public byte[] advSecretKey;

    /**
     * Next sequence prekey identifier for rotation.
     */
    public int nextPreKeyId = 1;

    /**
     * Index of the first prekey awaiting server upload.
     */
    public int firstUnuploadedPreKeyId = 1;

    /**
     * Counter for tracking companion account synchronization state.
     */
    public int accountSyncCounter = 0;

    /**
     * Serialized ADVSignedDeviceIdentity protobuf payload.
     */
    public byte[] account;

    /**
     * Authenticated primary WhatsApp phone number JID.
     */
    public String meJid;

    /**
     * Authenticated Linked Identity (LID) JID.
     */
    public String meLid;

    /**
     * Account display push name.
     */
    public String pushName;

    /**
     * Operating platform identifier string.
     */
    public String platform;

    /**
     * Increment 5: this account's app-state-sync key id, standard-base64
     * encoded (Baileys {@code creds.myAppStateKeyId}, set from the last
     * key id in an {@code AppStateSyncKeyShare} — see Baileys
     * {@code lib/Utils/process-message.js:361}, which emits
     * {@code creds.update} with the new id, and
     * {@code lib/Socket/chats.js:714} {@code appPatch}, which throws
     * when it is absent). Nullable; null means no app-state key has
     * been shared yet, and {@code JaWaClient.appPatch} fails instead
     * of sending. Persisted by both {@code FileAuthStore} and
     * {@code SqliteAuthStore} (key {@code myAppStateKeyId}); older
     * stored creds without the key load it as null.
     */
    public String myAppStateKeyId;

    /**
     * Default constructor initializing empty credentials.
     */
    public AuthCreds() {}

    /**
     * Generates a fresh set of cryptographic keys and registration state for pairing a new session.
     *
     * @return Newly generated AuthCreds ready for WhatsApp pairing
     */
    public static AuthCreds generate() {
        AuthCreds c = new AuthCreds();
        c.noiseKey         = Curve25519.generateKeyPair();
        c.signedIdentityKey = Curve25519.generateKeyPair();
        c.signedPreKey     = SignedPreKey.generate(1, c.signedIdentityKey);

        c.registrationId   = new SecureRandom().nextInt(0xFFFF + 1);
        c.advSecretKey     = Bytes.random(32);
        return c;
    }
}
