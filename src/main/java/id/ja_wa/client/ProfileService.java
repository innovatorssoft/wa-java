package id.ja_wa.client;

import id.ja_wa.domain.model.Blocklist;
import id.ja_wa.domain.model.BotListInfo;
import id.ja_wa.domain.model.BusinessMessageLinkTarget;
import id.ja_wa.domain.model.BusinessProfile;
import id.ja_wa.domain.model.CatalogProduct;
import id.ja_wa.domain.model.ContactQrTarget;
import id.ja_wa.domain.model.OrderDetails;
import id.ja_wa.domain.model.ProfilePictureInfo;
import id.ja_wa.domain.model.UserInfo;
import id.ja_wa.feature.catalog.CatalogQuery;
import id.ja_wa.feature.media.MediaConn;
import id.ja_wa.feature.media.MediaUploader;
import id.ja_wa.feature.messaging.ProfilePictureQuery;
import id.ja_wa.protocol.codec.BinaryNode;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
     * Fetches avatar metadata with an optional tcToken (Increment 3,
     * Baileys {@code profilePictureUrl}). Baileys includes a tcToken only
     * for non-self user JIDs when the server property for profile-picture
     * privacy tokens is set; building the token itself needs auth state
     * and is the caller's job — passing null omits it, matching the
     * pre-existing client query. Returns null on error / 204 / 304 /
     * missing URL, as {@link ProfilePictureQuery#parse} defines.
     *
     * @param targetJid target entity JID
     * @param preview true for low-resolution preview, false for full size
     * @param tcTokenContent tcToken child nodes, or null to omit
     * @return CompletableFuture resolving to ProfilePictureInfo, or null
     */
    public CompletableFuture<ProfilePictureInfo> getProfilePictureInfo(String targetJid, boolean preview,
            List<BinaryNode> tcTokenContent) {
        return client.sendIqAsync(ProfilePictureQuery.buildQuery(newIqId(), targetJid, preview, tcTokenContent))
            .thenApply(ProfilePictureQuery::parse);
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

    // ---- Increment 3: catalog / product / business-profile updates (Baileys lib/Socket/business.js) ----
    //
    // Sent through the existing public sendIqAsync transport. IQ ids are
    // generated locally (UUID hex) because JaWaClient.newIqId is private.
    // Business-profile fetch and order details already exist in JaWaClient
    // and are not duplicated here.

    private static String newIqId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Fetches a business catalog (Baileys {@code getCatalog}).
     * The caller supplies the business JID explicitly: Baileys defaults to
     * the own JID, which this service cannot read from JaWaClient.
     *
     * @param jid business account JID
     * @param limit page size (Baileys default 10 when non-positive)
     * @param cursor next-page cursor, or null
     * @return CompletableFuture resolving to products plus the next-page cursor
     */
    public CompletableFuture<CatalogQuery.CatalogResult> getCatalog(String jid, int limit, String cursor) {
        return client.sendIqAsync(CatalogQuery.buildCatalogQuery(newIqId(), jid, limit, cursor))
            .thenApply(CatalogQuery::parseCatalogResponse);
    }

    /**
     * Fetches a business catalog with the default page size (Baileys {@code getCatalog}).
     *
     * @param jid business account JID
     * @param limit page size
     * @return CompletableFuture resolving to products plus the next-page cursor
     */
    public CompletableFuture<CatalogQuery.CatalogResult> getCatalog(String jid, int limit) {
        return getCatalog(jid, limit, null);
    }

    /**
     * Fetches business collections (Baileys {@code getCollections}).
     *
     * @param jid business account JID
     * @param limit page size (Baileys default 51 when non-positive)
     * @return CompletableFuture resolving to the collections
     */
    public CompletableFuture<List<CatalogQuery.Collection>> getCollections(String jid, int limit) {
        return client.sendIqAsync(CatalogQuery.buildCollectionsQuery(newIqId(), jid, limit))
            .thenApply(CatalogQuery::parseCollectionsResponse);
    }

    /**
     * Fetches business collections with the default page size (Baileys {@code getCollections}).
     *
     * @param jid business account JID
     * @return CompletableFuture resolving to the collections
     */
    public CompletableFuture<List<CatalogQuery.Collection>> getCollections(String jid) {
        return getCollections(jid, 51);
    }

    /**
     * Creates a catalog product (Baileys {@code productCreate}).
     * Image URLs must already be uploaded; for raw image bytes use
     * {@link #createProduct(CatalogProduct.ProductInput, List, MediaConn)},
     * which uploads them first (Baileys
     * {@code uploadingNecessaryImagesOfProduct}).
     *
     * @param input product input
     * @return CompletableFuture resolving to the created product, or null when absent from the response
     */
    public CompletableFuture<CatalogProduct> createProduct(CatalogProduct.ProductInput input) {
        return client.sendIqAsync(CatalogQuery.buildProductCreate(newIqId(), input))
            .thenApply(resp -> CatalogQuery.parseProductResponse(resp, "product_catalog_add"));
    }

    /**
     * Creates a catalog product, uploading raw image bytes first
     * (Baileys {@code productCreate} with
     * {@code uploadingNecessaryImagesOfProduct}:
     * {@code product-catalog-image} raw uploads, one per byte array,
     * whose returned URLs become the product's image URLs). When
     * {@code imageBytes} is null or empty this is exactly
     * {@link #createProduct(CatalogProduct.ProductInput)} — URL inputs
     * in {@code input} are then used unchanged. When
     * {@code imageBytes} is non-empty, the uploaded URLs replace
     * {@code input.imageUrls()} wholesale: mixed URL-plus-bytes lists
     * are not merged (callers with already-hosted URLs should use
     * the URL-only overload). Uploads run on the common pool, as
     * with JaWaClient's other blocking media steps. The caller
     * supplies the media connection (e.g. from
     * {@code client.refreshMediaConn()}); a future no-arg variant
     * could refresh it internally.
     *
     * @param input product input (other fields; image URLs replaced when bytes are given)
     * @param imageBytes raw image bytes to upload, or null/empty for URL-only input
     * @param mediaConn media connection for the upload
     * @return CompletableFuture resolving to the created product, or null when absent from the response
     */
    public CompletableFuture<CatalogProduct> createProduct(CatalogProduct.ProductInput input,
            List<byte[]> imageBytes, MediaConn mediaConn) {
        return uploadProductImages(imageBytes, mediaConn)
            .thenCompose(urls -> {
                CatalogProduct.ProductInput effective = urls == null ? input
                    : new CatalogProduct.ProductInput(input.name(), input.description(),
                        input.retailerId(), urls, input.price(), input.currency(),
                        input.isHidden(), input.originCountryCode(),
                        input.originCountryCodePresent());
                return createProduct(effective);
            });
    }

    /**
     * Updates a catalog product (Baileys {@code productUpdate}).
     * Image URLs must already be uploaded; for raw image bytes use
     * {@link #updateProduct(String, CatalogProduct.ProductInput, List, MediaConn)}.
     *
     * @param productId product id
     * @param input product input
     * @return CompletableFuture resolving to the updated product, or null when absent from the response
     */
    public CompletableFuture<CatalogProduct> updateProduct(String productId, CatalogProduct.ProductInput input) {
        return client.sendIqAsync(CatalogQuery.buildProductUpdate(newIqId(), productId, input))
            .thenApply(resp -> CatalogQuery.parseProductResponse(resp, "product_catalog_edit"));
    }

    /**
     * Updates a catalog product, uploading raw image bytes first —
     * the update counterpart of
     * {@link #createProduct(CatalogProduct.ProductInput, List, MediaConn)},
     * with the same null/empty-bytes and wholesale-replacement rules.
     *
     * @param productId product id
     * @param input product input (other fields; image URLs replaced when bytes are given)
     * @param imageBytes raw image bytes to upload, or null/empty for URL-only input
     * @param mediaConn media connection for the upload
     * @return CompletableFuture resolving to the updated product, or null when absent from the response
     */
    public CompletableFuture<CatalogProduct> updateProduct(String productId,
            CatalogProduct.ProductInput input, List<byte[]> imageBytes, MediaConn mediaConn) {
        return uploadProductImages(imageBytes, mediaConn)
            .thenCompose(urls -> {
                CatalogProduct.ProductInput effective = urls == null ? input
                    : new CatalogProduct.ProductInput(input.name(), input.description(),
                        input.retailerId(), urls, input.price(), input.currency(),
                        input.isHidden(), input.originCountryCode(),
                        input.originCountryCodePresent());
                return updateProduct(productId, effective);
            });
    }

    /**
     * Uploads raw product images (Baileys
     * {@code uploadingNecessaryImages}). Returns null without any
     * upload when {@code imageBytes} is null or empty, so callers can
     * distinguish "URL-only input" from "uploaded URLs".
     */
    private static CompletableFuture<List<String>> uploadProductImages(
            List<byte[]> imageBytes, MediaConn mediaConn) {
        if (imageBytes == null || imageBytes.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            List<String> urls = new java.util.ArrayList<>();
            try {
                for (byte[] bytes : imageBytes) {
                    urls.add(MediaUploader.uploadProductImage(mediaConn, bytes));
                }
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
            return urls;
        });
    }

    /**
     * Deletes catalog products (Baileys {@code productDelete}).
     *
     * @param productIds product ids
     * @return CompletableFuture resolving to the deleted count
     */
    public CompletableFuture<Integer> deleteProducts(List<String> productIds) {
        return client.sendIqAsync(CatalogQuery.buildProductDelete(newIqId(), productIds))
            .thenApply(CatalogQuery::parseProductDeleteResponse);
    }

    /**
     * Updates the own business profile (Baileys {@code updateBussinesProfile},
     * delta form). Business hours in {@code HH:MM} form are converted to
     * minutes on the wire; anything else fails fast. Returns the raw
     * response node, as Baileys returns the query result.
     *
     * @param profile business profile update (only non-null fields are sent)
     * @return CompletableFuture resolving to the raw response BinaryNode
     */
    public CompletableFuture<BinaryNode> updateBusinessProfile(BusinessProfile profile) {
        return client.sendIqAsync(CatalogQuery.buildBusinessProfileUpdate(newIqId(), profile));
    }

    /**
     * Updates the business cover photo delta (Baileys {@code updateCoverPhoto},
     * delta only) from already-uploaded values. For raw photo bytes use
     * {@link #updateBusinessCoverPhoto(byte[], MediaConn)}, which uploads
     * first and derives these values from the upload response.
     *
     * @param fbid uploaded file id
     * @param metaHmac upload meta HMAC
     * @param ts upload timestamp
     * @return CompletableFuture resolving to the raw response BinaryNode
     */
    public CompletableFuture<BinaryNode> updateBusinessCoverPhoto(String fbid, String metaHmac, long ts) {
        return client.sendIqAsync(CatalogQuery.buildCoverPhotoUpdate(newIqId(), fbid, metaHmac, ts));
    }

    /**
     * Updates the business cover photo from raw photo bytes (Baileys
     * {@code updateCoverPhoto} in full: raw {@code biz-cover-photo}
     * upload, then the delta IQ with the returned {@code fbid}/
     * {@code metaHmac}/{@code ts}). Fails fast when the upload
     * response lacks any of those three values, rather than sending
     * a delta with missing attrs (Baileys would send them as
     * undefined). The caller supplies the media connection (e.g.
     * from {@code client.refreshMediaConn()}).
     *
     * @param photoBytes raw cover-photo bytes
     * @param mediaConn media connection for the upload
     * @return CompletableFuture resolving to the raw response BinaryNode
     */
    public CompletableFuture<BinaryNode> updateBusinessCoverPhoto(byte[] photoBytes, MediaConn mediaConn) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return MediaUploader.uploadBizCoverPhoto(mediaConn, photoBytes);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).thenCompose(result -> {
            if (result.fbid() == null || result.metaHmac() == null || result.ts() == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "biz-cover-photo upload response missing fbid/meta_hmac/ts"));
            }
            return updateBusinessCoverPhoto(result.fbid(), result.metaHmac(), result.ts());
        });
    }

    /**
     * Removes the business cover photo (Baileys {@code removeCoverPhoto}).
     *
     * @param coverPhotoId cover photo id
     * @return CompletableFuture resolving to the raw response BinaryNode
     */
    public CompletableFuture<BinaryNode> removeBusinessCoverPhoto(String coverPhotoId) {
        return client.sendIqAsync(CatalogQuery.buildCoverPhotoDelete(newIqId(), coverPhotoId));
    }
}
