package id.ja_wa.domain.store;

import id.ja_wa.domain.model.AuthCreds;

import java.io.IOException;

/**
 * Storage interface for persisting WhatsApp authentication credentials and registration state.
 */
public interface AuthStore {

    /**
     * Loads saved authentication credentials from persistent storage.
     *
     * @return the loaded credentials, or null if no credentials exist
     * @throws IOException if reading from persistent storage fails
     */
    AuthCreds load() throws IOException;

    /**
     * Saves or updates authentication credentials to persistent storage.
     *
     * @param creds the authentication credentials to persist
     * @throws IOException if writing to persistent storage fails
     */
    void save(AuthCreds creds) throws IOException;

    /**
     * Checks whether an existing paired registration session is stored.
     *
     * @return true if paired credentials exist, false otherwise
     * @throws IOException if checking persistent storage fails
     */
    boolean isPaired() throws IOException;

    /**
     * Clears and resets all stored authentication credentials and sessions.
     *
     * @throws IOException if clearing persistent storage fails
     */
    default void clear() throws IOException {}
}
