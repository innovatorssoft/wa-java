package id.jawa.client;

import id.jawa.domain.model.Blocklist;
import id.jawa.domain.model.BotListInfo;
import id.jawa.domain.model.BusinessMessageLinkTarget;
import id.jawa.domain.model.BusinessProfile;
import id.jawa.domain.model.ContactQrTarget;
import id.jawa.domain.model.OrderDetails;
import id.jawa.domain.model.ProfilePictureInfo;
import id.jawa.domain.model.UserInfo;
import id.jawa.protocol.codec.BinaryNode;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service managing user profile details, status bio, avatars, business profiles, and contact QR links.
 */
public final class ProfileService {

    private final JaWaClient client;

    ProfileService(JaWaClient client) {
        this.client = client;
    }

    /**
     * Updates the account text status (About bio).
     *
     * @param statusText New bio string
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> updateStatus(String statusText) {
        return client.updateProfileStatus(statusText);
    }

    /**
     * Sets the account text status and returns the saved text.
     *
     * @param statusText New bio string
     * @return CompletableFuture resolving to confirmed status string
     */
    public CompletableFuture<String> setStatusMessage(String statusText) {
        return client.setStatusMessage(statusText);
    }

    /**
     * Fetches the current text status (About bio) of a contact.
     *
     * @param targetJid Target contact phone number or LID JID
     * @return CompletableFuture resolving to status text
     */
    public CompletableFuture<String> fetchStatus(String targetJid) {
        return client.getStatus(targetJid);
    }

    /**
     * Fetches the direct URL for a contact or group profile picture.
     *
     * @param targetJid Target entity JID
     * @return CompletableFuture resolving to direct photo URL string, or null
     */
    public CompletableFuture<String> fetchProfilePictureUrl(String targetJid) {
        return client.fetchProfilePictureUrl(targetJid);
    }

    /**
     * Fetches full avatar metadata (id, direct path, download URL) for a contact or group.
     *
     * @param targetJid Target entity JID
     * @param preview True to request low-resolution thumbnail preview, false for full size
     * @return CompletableFuture resolving to ProfilePictureInfo
     */
    public CompletableFuture<ProfilePictureInfo> getProfilePictureInfo(String targetJid, boolean preview) {
        return client.getProfilePictureInfo(targetJid, preview);
    }

    /**
     * Fetches full size avatar metadata for a contact or group.
     *
     * @param targetJid Target entity JID
     * @return CompletableFuture resolving to ProfilePictureInfo
     */
    public CompletableFuture<ProfilePictureInfo> getProfilePictureInfo(String targetJid) {
        return client.getProfilePictureInfo(targetJid, false);
    }

    /**
     * Updates the current account profile avatar with new image bytes.
     *
     * @param imageBytes Raw JPEG image data
     * @return CompletableFuture resolving to server response BinaryNode
     */
    public CompletableFuture<BinaryNode> updateProfilePicture(byte[] imageBytes) {
        return client.updateProfilePicture(imageBytes);
    }

    /**
     * Deletes the current account profile avatar.
     *
     * @return CompletableFuture resolving to server response BinaryNode
     */
    public CompletableFuture<BinaryNode> removeProfilePicture() {
        return client.removeProfilePicture();
    }

    /**
     * Updates the block status of a contact.
     *
     * @param targetJid Target contact JID
     * @param block True to block, false to unblock
     * @return CompletableFuture resolving to server response BinaryNode
     */
    public CompletableFuture<BinaryNode> updateBlockStatus(String targetJid, boolean block) {
        return client.updateBlockStatus(targetJid, block);
    }

    /**
     * Fetches list of blocked contact JIDs.
     *
     * @return CompletableFuture resolving to list of JID strings
     */
    public CompletableFuture<List<String>> fetchBlockList() {
        return client.fetchBlockList();
    }

    /**
     * Fetches the account blocklist model.
     *
     * @return CompletableFuture resolving to Blocklist
     */
    public CompletableFuture<Blocklist> getBlocklist() {
        return client.getBlocklist();
    }

    /**
     * Fetches the account blocklist model matching differential hash.
     *
     * @param dhash Differential hash token
     * @return CompletableFuture resolving to Blocklist
     */
    public CompletableFuture<Blocklist> getBlocklist(String dhash) {
        return client.getBlocklist(dhash);
    }

    /**
     * Modifies the block status of a contact in the blocklist.
     *
     * @param jid Target contact JID
     * @param block True to block, false to unblock
     * @return CompletableFuture resolving to updated Blocklist
     */
    public CompletableFuture<Blocklist> updateBlocklist(String jid, boolean block) {
        return client.updateBlocklist(jid, block);
    }

    /**
     * Blocks a contact.
     *
     * @param jid Target contact JID
     * @return CompletableFuture resolving to updated Blocklist
     */
    public CompletableFuture<Blocklist> block(String jid) {
        return client.updateBlocklist(jid, true);
    }

    /**
     * Unblocks a contact.
     *
     * @param jid Target contact JID
     * @return CompletableFuture resolving to updated Blocklist
     */
    public CompletableFuture<Blocklist> unblock(String jid) {
        return client.updateBlocklist(jid, false);
    }

    /**
     * Generates or revokes the personal contact QR code link.
     *
     * @param revoke True to revoke existing QR code and generate new link, false to retrieve current
     * @return CompletableFuture resolving to QR link code string
     */
    public CompletableFuture<String> getContactQrLink(boolean revoke) {
        return client.getContactQrLink(revoke);
    }

    /**
     * Retrieves the current personal contact QR code link.
     *
     * @return CompletableFuture resolving to QR link code string
     */
    public CompletableFuture<String> getContactQrLink() {
        return client.getContactQrLink(false);
    }

    /**
     * Resolves recipient target information from a contact QR code or short URL.
     *
     * @param codeOrUrl Scanned QR code token or wa.me URL
     * @return CompletableFuture resolving to ContactQrTarget
     */
    public CompletableFuture<ContactQrTarget> resolveContactQrLink(String codeOrUrl) {
        return client.resolveContactQrLink(codeOrUrl);
    }

    /**
     * Fetches business profile details for a WhatsApp Business account.
     *
     * @param jid Business account JID
     * @return CompletableFuture resolving to BusinessProfile
     */
    public CompletableFuture<BusinessProfile> getBusinessProfile(String jid) {
        return client.getBusinessProfile(jid);
    }

    /**
     * Resolves merchant and draft details from a business message shortlink.
     *
     * @param codeOrUrl Business shortlink code or URL
     * @return CompletableFuture resolving to BusinessMessageLinkTarget
     */
    public CompletableFuture<BusinessMessageLinkTarget> resolveBusinessMessageLink(String codeOrUrl) {
        return client.resolveBusinessMessageLink(codeOrUrl);
    }

    /**
     * Queries user information and devices in batch for multiple JIDs via USync.
     *
     * @param targetJids Collection of phone number or LID JIDs
     * @return CompletableFuture resolving to Map of JID to UserInfo
     */
    public CompletableFuture<Map<String, UserInfo>> getUserInfo(Collection<String> targetJids) {
        return client.getUserInfo(targetJids);
    }

    /**
     * Queries user information and devices for a single JID via USync.
     *
     * @param targetJid Target entity JID
     * @return CompletableFuture resolving to UserInfo
     */
    public CompletableFuture<UserInfo> getUserInfo(String targetJid) {
        return client.getUserInfo(targetJid);
    }

    /**
     * Updates the account display push name broadcast to contacts.
     *
     * @param newPushName Desired display push name
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> updatePushName(String newPushName) {
        return client.updatePushName(newPushName);
    }

    /**
     * Fetches itemized order details for a WhatsApp cart or catalog checkout.
     *
     * @param orderId WhatsApp order identifier
     * @param tokenBase64 Order security token from order message
     * @return CompletableFuture resolving to OrderDetails
     */
    public CompletableFuture<OrderDetails> getOrderDetails(String orderId, String tokenBase64) {
        return client.getOrderDetails(orderId, tokenBase64);
    }

    /**
     * Fetches list of Meta AI personas available on WhatsApp.
     *
     * @return CompletableFuture resolving to list of BotListInfo
     */
    public CompletableFuture<List<BotListInfo>> getBotList() {
        return client.getBotList();
    }
}
