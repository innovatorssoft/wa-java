package id.jawa.client;

import id.jawa.domain.model.Blocklist;
import id.jawa.domain.model.PrivacySettings;
import id.jawa.domain.model.StatusPrivacy;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class PrivacyService {

    private final JaWaClient client;

    PrivacyService(JaWaClient client) {
        this.client = client;
    }

    public CompletableFuture<PrivacySettings> getPrivacySettings() {
        return client.getPrivacySettings();
    }

    public CompletableFuture<Void> setPrivacySetting(String category, String value) {
        return client.setPrivacySetting(category, value);
    }

    public CompletableFuture<Void> setDefaultDisappearingTimer(int seconds) {
        return client.setDefaultDisappearingTimer(seconds);
    }

    public CompletableFuture<List<StatusPrivacy>> getStatusPrivacy() {
        return client.getStatusPrivacy();
    }

    public CompletableFuture<Blocklist> getBlocklist() {
        return client.getBlocklist();
    }

    public CompletableFuture<Blocklist> getBlocklist(String dhash) {
        return client.getBlocklist(dhash);
    }

    public CompletableFuture<Blocklist> updateBlocklist(String jid, boolean block) {
        return client.updateBlocklist(jid, block);
    }

    public CompletableFuture<Blocklist> block(String jid) {
        return client.updateBlocklist(jid, true);
    }

    public CompletableFuture<Blocklist> unblock(String jid) {
        return client.updateBlocklist(jid, false);
    }
}
