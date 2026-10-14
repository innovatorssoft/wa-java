package id.jawa.client;

import id.jawa.domain.model.PrivacySettings;

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
}
