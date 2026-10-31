package id.ja_wa.domain.store;

import id.ja_wa.protocol.crypto.KeyPair25519;

import java.util.Map;

/**
 * Key store interface for managing Signal Protocol pre-keys used during E2E session establishment.
 */
public interface SignalKeyStore {

    /**
     * Retrieves the Curve25519 pre-key pair associated with the specified ID.
     *
     * @param id the unique pre-key identifier
     * @return the pre-key pair, or null if not found
     */
    KeyPair25519 getPreKey(int id);

    /**
     * Stores or updates a Curve25519 pre-key pair with the specified ID.
     *
     * @param id the unique pre-key identifier
     * @param kp the pre-key pair to store
     */
    void putPreKey(int id, KeyPair25519 kp);

    /**
     * Removes the pre-key pair associated with the specified ID.
     *
     * @param id the pre-key identifier to remove
     */
    void removePreKey(int id);

    /**
     * Retrieves all existing pre-keys within the specified ID range.
     *
     * @param from the start pre-key ID (inclusive)
     * @param toExclusive the end pre-key ID (exclusive)
     * @return a sorted map of pre-key IDs to key pairs
     */
    Map<Integer, KeyPair25519> getPreKeysInRange(int from, int toExclusive);
}
