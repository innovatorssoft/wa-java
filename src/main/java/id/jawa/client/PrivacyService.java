package id.jawa.client;

import id.jawa.domain.model.Blocklist;
import id.jawa.domain.model.PrivacySettings;
import id.jawa.domain.model.StatusPrivacy;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service managing user privacy controls, contact blocklists, and disappearing message defaults.
 */
public final class PrivacyService {

    private final JaWaClient client;

    PrivacyService(JaWaClient client) {
        this.client = client;
    }

    /**
     * Fetches current account privacy configurations across all categories.
     *
     * @return CompletableFuture resolving to current PrivacySettings
     */
    public CompletableFuture<PrivacySettings> getPrivacySettings() {
        return client.getPrivacySettings();
    }

    /**
     * Updates an individual privacy category setting.
     *
     * @param category Privacy category name ("last", "online", "profile", "status", "readreceipts", "calladd")
     * @param value Target visibility level ("all", "contacts", "contact_blacklist", "none")
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> setPrivacySetting(String category, String value) {
        return client.setPrivacySetting(category, value);
    }

    /**
     * Configures the global default disappearing message timer for new conversations.
     *
     * @param seconds Expiration period in seconds (e.g. 86400 for 24h, 604800 for 7d, 7776000 for 90d, 0 to disable)
     * @return CompletableFuture completing when setting is saved
     */
    public CompletableFuture<Void> setDefaultDisappearingTimer(int seconds) {
        return client.setDefaultDisappearingTimer(seconds);
    }

    /**
     * Fetches status (stories) privacy audience rules and whitelists/blacklists.
     *
     * @return CompletableFuture resolving to a list of StatusPrivacy configurations
     */
    public CompletableFuture<List<StatusPrivacy>> getStatusPrivacy() {
        return client.getStatusPrivacy();
    }

    /**
     * Fetches the complete account blocklist.
     *
     * @return CompletableFuture resolving to current Blocklist
     */
    public CompletableFuture<Blocklist> getBlocklist() {
        return client.getBlocklist();
    }

    /**
     * Fetches the blocklist, verifying against a previously cached differential hash (dhash).
     *
     * @param dhash Previous blocklist hash token, or null to force full fetch
     * @return CompletableFuture resolving to Blocklist
     */
    public CompletableFuture<Blocklist> getBlocklist(String dhash) {
        return client.getBlocklist(dhash);
    }

    /**
     * Modifies the block status of a contact.
     *
     * @param jid Target contact JID to block or unblock
     * @param block True to block, false to unblock
     * @return CompletableFuture resolving to updated Blocklist
     */
    public CompletableFuture<Blocklist> updateBlocklist(String jid, boolean block) {
        return client.updateBlocklist(jid, block);
    }

    /**
     * Blocks a contact from calling or messaging this account.
     *
     * @param jid Target contact JID to block
     * @return CompletableFuture resolving to updated Blocklist
     */
    public CompletableFuture<Blocklist> block(String jid) {
        return client.updateBlocklist(jid, true);
    }

    /**
     * Unblocks a previously blocked contact.
     *
     * @param jid Target contact JID to unblock
     * @return CompletableFuture resolving to updated Blocklist
     */
    public CompletableFuture<Blocklist> unblock(String jid) {
        return client.updateBlocklist(jid, false);
    }
}
