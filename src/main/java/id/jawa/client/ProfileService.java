package id.jawa.client;

import id.jawa.domain.model.Blocklist;
import id.jawa.domain.model.BusinessMessageLinkTarget;
import id.jawa.domain.model.BusinessProfile;
import id.jawa.domain.model.ContactQrTarget;
import id.jawa.domain.model.OrderDetails;
import id.jawa.domain.model.UserInfo;
import id.jawa.protocol.codec.BinaryNode;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ProfileService {

    private final JaWaClient client;

    ProfileService(JaWaClient client) {
        this.client = client;
    }

    public CompletableFuture<BinaryNode> updateStatus(String statusText) {
        return client.updateProfileStatus(statusText);
    }

    public CompletableFuture<String> setStatusMessage(String statusText) {
        return client.setStatusMessage(statusText);
    }

    public CompletableFuture<String> fetchStatus(String targetJid) {
        return client.getStatus(targetJid);
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

    public CompletableFuture<String> getContactQrLink(boolean revoke) {
        return client.getContactQrLink(revoke);
    }

    public CompletableFuture<String> getContactQrLink() {
        return client.getContactQrLink(false);
    }

    public CompletableFuture<ContactQrTarget> resolveContactQrLink(String codeOrUrl) {
        return client.resolveContactQrLink(codeOrUrl);
    }

    public CompletableFuture<BusinessProfile> getBusinessProfile(String jid) {
        return client.getBusinessProfile(jid);
    }

    public CompletableFuture<BusinessMessageLinkTarget> resolveBusinessMessageLink(String codeOrUrl) {
        return client.resolveBusinessMessageLink(codeOrUrl);
    }

    public CompletableFuture<Map<String, UserInfo>> getUserInfo(Collection<String> targetJids) {
        return client.getUserInfo(targetJids);
    }

    public CompletableFuture<UserInfo> getUserInfo(String targetJid) {
        return client.getUserInfo(targetJid);
    }

    public CompletableFuture<Void> updatePushName(String newPushName) {
        return client.updatePushName(newPushName);
    }

    public CompletableFuture<OrderDetails> getOrderDetails(String orderId, String tokenBase64) {
        return client.getOrderDetails(orderId, tokenBase64);
    }
}
