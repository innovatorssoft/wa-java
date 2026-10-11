package id.jawa.client;

import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ProfileService {

    private final JaWaClient client;

    ProfileService(JaWaClient client) {
        this.client = client;
    }

    public CompletableFuture<BinaryNode> updateStatus(String statusText) {
        return client.updateProfileStatus(statusText);
    }

    public CompletableFuture<String> fetchProfilePictureUrl(String targetJid) {
        return client.fetchProfilePictureUrl(targetJid);
    }

    public CompletableFuture<BinaryNode> updateProfilePicture(byte[] imageBytes) {
        return client.updateProfilePicture(imageBytes);
    }

    public CompletableFuture<BinaryNode> removeProfilePicture() {
        return client.removeProfilePicture();
    }

    public CompletableFuture<BinaryNode> updateBlockStatus(String targetJid, boolean block) {
        return client.updateBlockStatus(targetJid, block);
    }

    public CompletableFuture<List<String>> fetchBlockList() {
        return client.fetchBlockList();
    }
}
