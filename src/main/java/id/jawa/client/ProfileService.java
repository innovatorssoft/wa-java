// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.client;

import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service facade handling user profile and account privacy in JaWa
 * (bio/status update, profile pictures, and blocklist management).
 */
public final class ProfileService {

    private final JaWaClient client;

    ProfileService(JaWaClient client) {
        this.client = client;
    }

    /** Update account bio / status message. */
    public CompletableFuture<BinaryNode> updateStatus(String statusText) {
        return client.updateProfileStatus(statusText);
    }

    /** Fetch preview profile picture URL for a contact or group. */
    public CompletableFuture<String> fetchProfilePictureUrl(String targetJid) {
        return client.fetchProfilePictureUrl(targetJid);
    }

    /** Update account profile picture with JPEG bytes. */
    public CompletableFuture<BinaryNode> updateProfilePicture(byte[] imageBytes) {
        return client.updateProfilePicture(imageBytes);
    }

    /** Remove current account profile picture. */
    public CompletableFuture<BinaryNode> removeProfilePicture() {
        return client.removeProfilePicture();
    }

    /** Update block status for a contact. */
    public CompletableFuture<BinaryNode> updateBlockStatus(String targetJid, boolean block) {
        return client.updateBlockStatus(targetJid, block);
    }

    /** Fetch list of currently blocked contact JIDs. */
    public CompletableFuture<List<String>> fetchBlockList() {
        return client.fetchBlockList();
    }
}
