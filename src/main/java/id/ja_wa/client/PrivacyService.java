package id.ja_wa.client;

import id.ja_wa.domain.model.Blocklist;
import id.ja_wa.domain.model.PrivacySettings;
import id.ja_wa.domain.model.StatusPrivacy;
import id.ja_wa.feature.appstate.AppPatchEncoder;

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
     * <p>Pre-existing, uncached: every call sends the privacy IQ.
     * See {@link #fetchPrivacySettings(boolean)} for Baileys'
     * cached {@code fetchPrivacySettings} form.
     *
     * @return CompletableFuture resolving to current PrivacySettings
     */
    public CompletableFuture<PrivacySettings> getPrivacySettings() {
        return client.getPrivacySettings();
    }

    /**
     * Increment 7: Baileys {@code fetchPrivacySettings}
     * ({@code lib/Socket/chats.js:64}), cached form. With
     * {@code force = false} a previously fetched result is returned
     * without a network round trip; {@code force = true} always
     * refetches. Updates do not invalidate the cache (Baileys'
     * {@code privacyQuery} does not touch its cached variable
     * either), so pass {@code force = true} after an update when
     * fresh values are needed.
     *
     * @param force true to refetch even when a cached result exists
     * @return CompletableFuture resolving to current PrivacySettings
     */
    public CompletableFuture<PrivacySettings> fetchPrivacySettings(boolean force) {
        return client.fetchPrivacySettings(force);
    }

    /** Increment 7: {@link #fetchPrivacySettings(boolean)} with Baileys' default ({@code force = false}). */
    public CompletableFuture<PrivacySettings> fetchPrivacySettings() {
        return client.fetchPrivacySettings();
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
     * Increment 7: Baileys {@code updateDefaultDisappearingMode},
     * the Baileys name for {@link #setDefaultDisappearingTimer(int)}
     * (same disappearing-mode IQ). Delegates to it; the older
     * wa-java name is kept, not renamed.
     *
     * @param durationSeconds default disappearing duration in seconds (0 disables)
     * @return CompletableFuture completing when setting is saved
     */
    public CompletableFuture<Void> updateDefaultDisappearingMode(int durationSeconds) {
        return client.updateDefaultDisappearingMode(durationSeconds);
    }

    /**
     * Increment 7: Baileys {@code updateMessagesPrivacy} — privacy
     * category {@code messages}. Values are passed through verbatim
     * ({@code all}, {@code contacts}, {@code contact_blacklist},
     * {@code none}); wa-java defines no value enum.
     *
     * @param value target visibility level
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> updateMessagesPrivacy(String value) {
        return client.updateMessagesPrivacy(value);
    }

    /** Increment 7: Baileys {@code updateCallPrivacy} — category {@code calladd}. See {@link #updateMessagesPrivacy(String)}. */
    public CompletableFuture<Void> updateCallPrivacy(String value) {
        return client.updateCallPrivacy(value);
    }

    /** Increment 7: Baileys {@code updateLastSeenPrivacy} — category {@code last}. See {@link #updateMessagesPrivacy(String)}. */
    public CompletableFuture<Void> updateLastSeenPrivacy(String value) {
        return client.updateLastSeenPrivacy(value);
    }

    /** Increment 7: Baileys {@code updateOnlinePrivacy} — category {@code online}. See {@link #updateMessagesPrivacy(String)}. */
    public CompletableFuture<Void> updateOnlinePrivacy(String value) {
        return client.updateOnlinePrivacy(value);
    }

    /** Increment 7: Baileys {@code updateProfilePicturePrivacy} — category {@code profile}. See {@link #updateMessagesPrivacy(String)}. */
    public CompletableFuture<Void> updateProfilePicturePrivacy(String value) {
        return client.updateProfilePicturePrivacy(value);
    }

    /** Increment 7: Baileys {@code updateStatusPrivacy} — category {@code status}. See {@link #updateMessagesPrivacy(String)}. */
    public CompletableFuture<Void> updateStatusPrivacy(String value) {
        return client.updateStatusPrivacy(value);
    }

    /** Increment 7: Baileys {@code updateReadReceiptsPrivacy} — category {@code readreceipts}. See {@link #updateMessagesPrivacy(String)}. */
    public CompletableFuture<Void> updateReadReceiptsPrivacy(String value) {
        return client.updateReadReceiptsPrivacy(value);
    }

    /**
     * Increment 7: Baileys {@code updateGroupsAddPrivacy} — category
     * {@code groupadd} (the key {@code PrivacySettings.groupAdd}
     * reads). See {@link #updateMessagesPrivacy(String)}.
     */
    public CompletableFuture<Void> updateGroupsAddPrivacy(String value) {
        return client.updateGroupsAddPrivacy(value);
    }

    /**
     * Increment 7: Baileys {@code updateDisableLinkPreviewsPrivacy}.
     * Despite the name this is <em>not</em> a privacy IQ: Baileys
     * sends it as the {@code setting_disableLinkPreviews} app-state
     * patch (see {@code JaWaClient.updateDisableLinkPreviewsPrivacy}).
     * This delegate exists so callers looking for it under privacy
     * find it; the transport is the app-state patch.
     *
     * @param isPreviewsDisabled true to disable link previews
     * @return CompletableFuture completing with the encoder result on a successful send
     */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> updateDisableLinkPreviewsPrivacy(
            boolean isPreviewsDisabled) {
        return client.updateDisableLinkPreviewsPrivacy(isPreviewsDisabled);
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
