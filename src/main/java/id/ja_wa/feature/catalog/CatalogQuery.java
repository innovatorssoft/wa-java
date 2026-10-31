package id.ja_wa.feature.catalog;

import id.ja_wa.domain.model.BusinessProfile;
import id.ja_wa.domain.model.CatalogProduct;
import id.ja_wa.domain.model.Jid;
import id.ja_wa.protocol.codec.BinaryNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure builders (and response parsers) for WhatsApp Business catalog,
 * collection, product, and business-profile IQs.
 *
 * <p>Baileys sources: {@code lib/Socket/business.js} (query shapes) and
 * {@code lib/Utils/business.js} ({@code toProductNode},
 * {@code parseProductNode}, {@code parseCatalogNode},
 * {@code parseCollectionsNode}). Builders perform no I/O. Business-profile
 * <em>fetch</em> and order-details already exist in JaWaClient and are not
 * duplicated here.
 *
 * <p>Known gaps, stated rather than papered over:
 * <ul>
 *   <li>Image upload: Baileys {@code productCreate}/{@code productUpdate}
 *       first run {@code uploadingNecessaryImagesOfProduct}, which uploads
 *       any not-yet-uploaded image via {@code waUploadToServer} and throws
 *       in {@code toProductNode} unless every image already has a URL.
 *       These builders still accept only already-uploaded image URLs;
 *       the upload step itself lives in {@code MediaUploader}
 *       (raw {@code product-catalog-image} uploads) and is wired into
 *       the {@code ProfileService} bytes overloads, which supply the
 *       uploaded URLs to these builders.</li>
 *   <li>Cover photo: Baileys {@code updateCoverPhoto} uploads with media
 *       type {@code biz-cover-photo} and then sends the delta with the
 *       returned {@code fbid}/{@code meta_hmac}/{@code ts}. The delta
 *       builders are here; the upload step is in
 *       {@code MediaUploader.uploadBizCoverPhoto} and wired into the
 *       {@code ProfileService} bytes overload.</li>
 *   <li>Business hours on the wire are minutes ({@code open_time} /
 *       {@code close_time} in minutes, {@code day_of_week}, {@code mode}).
 *       wa-java's {@link BusinessProfile.BusinessHourConfig} stores clock
 *       strings ({@code "09:00"}), so
 *       {@link #buildBusinessProfileUpdate(String, BusinessProfile)} converts
 *       {@code HH:MM} to minutes and rejects anything else.</li>
 * </ul>
 */
public final class CatalogQuery {

    private CatalogQuery() {}

    /** Baileys {@code parseCatalogNode}: products plus the next-page cursor, null when absent. */
    public record CatalogResult(List<CatalogProduct> products, String nextPageCursor) {
        public CatalogResult {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }

    /** Baileys {@code parseCollectionsNode}: one collection with its products and status. */
    public record Collection(String id, String name, List<CatalogProduct> products,
                             String status, boolean canAppeal) {
        public Collection {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }

    /** Baileys {@code getCatalog}: {@code w:biz:catalog} get, defaults limit 10, image 100x100. */
    public static BinaryNode buildCatalogQuery(String iqId, String jid, int limit, String cursor) {
        require(iqId, "iqId");
        require(jid, "jid");
        List<BinaryNode> params = new ArrayList<>();
        params.add(textNode("limit", String.valueOf(limit > 0 ? limit : 10)));
        params.add(textNode("width", "100"));
        params.add(textNode("height", "100"));
        if (cursor != null && !cursor.isEmpty()) params.add(textNode("after", cursor));
        BinaryNode catalog = new BinaryNode("product_catalog",
            Map.of("jid", jid, "allow_shop_source", "true"), params);
        return new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "w:biz:catalog", "id", iqId),
            List.of(catalog));
    }

    /** Baileys {@code getCollections}: {@code w:biz:catalog} get with {@code smax_id 35}, defaults limit 51. */
    public static BinaryNode buildCollectionsQuery(String iqId, String jid, int limit) {
        require(iqId, "iqId");
        require(jid, "jid");
        int effectiveLimit = limit > 0 ? limit : 51;
        List<BinaryNode> params = List.of(
            textNode("collection_limit", String.valueOf(effectiveLimit)),
            textNode("item_limit", String.valueOf(effectiveLimit)),
            textNode("width", "100"),
            textNode("height", "100"));
        BinaryNode collections = new BinaryNode("collections", Map.of("biz_jid", jid), params);
        return new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "get", "xmlns", "w:biz:catalog",
                "smax_id", "35", "id", iqId),
            List.of(collections));
    }

    /** Baileys {@code productCreate}: {@code product_catalog_add v=1} with width/height 100. */
    public static BinaryNode buildProductCreate(String iqId, CatalogProduct.ProductInput input) {
        require(iqId, "iqId");
        if (input == null) throw new IllegalArgumentException("input must not be null");
        BinaryNode product = toProductNode(null, input);
        return catalogEditIq(iqId, "product_catalog_add", product);
    }

    /** Baileys {@code productUpdate}: {@code product_catalog_edit v=1} with width/height 100. */
    public static BinaryNode buildProductUpdate(String iqId, String productId, CatalogProduct.ProductInput input) {
        require(iqId, "iqId");
        require(productId, "productId");
        if (input == null) throw new IllegalArgumentException("input must not be null");
        BinaryNode product = toProductNode(productId, input);
        return catalogEditIq(iqId, "product_catalog_edit", product);
    }

    /** Baileys {@code productDelete}: {@code product_catalog_delete v=1} with one {@code <product><id>} per id. */
    public static BinaryNode buildProductDelete(String iqId, List<String> productIds) {
        require(iqId, "iqId");
        if (productIds == null || productIds.isEmpty()) {
            throw new IllegalArgumentException("productIds must not be empty");
        }
        List<BinaryNode> children = new ArrayList<>();
        for (String id : productIds) {
            children.add(new BinaryNode("product", Map.of(),
                List.of(textNode("id", id))));
        }
        BinaryNode del = new BinaryNode("product_catalog_delete", Map.of("v", "1"), children);
        return new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "w:biz:catalog", "id", iqId),
            List.of(del));
    }

    /**
     * Baileys {@code updateBussinesProfile}: {@code w:biz} set,
     * {@code business_profile v=3 mutation_type=delta}. Only the simple
     * fields present (non-null) in {@code profile} are sent, then websites,
     * then business hours. Categories and profile options are read-only in
     * Baileys' update shape and are not sent.
     */
    public static BinaryNode buildBusinessProfileUpdate(String iqId, BusinessProfile profile) {
        require(iqId, "iqId");
        if (profile == null) throw new IllegalArgumentException("profile must not be null");
        List<BinaryNode> node = new ArrayList<>();
        if (profile.address() != null) node.add(new BinaryNode("address", Map.of(), profile.address()));
        if (profile.email() != null) node.add(new BinaryNode("email", Map.of(), profile.email()));
        if (profile.description() != null) node.add(new BinaryNode("description", Map.of(), profile.description()));
        if (profile.websites() != null) {
            for (String website : profile.websites()) {
                node.add(new BinaryNode("website", Map.of(), website));
            }
        }
        if (profile.businessHours() != null && !profile.businessHours().isEmpty()) {
            List<BinaryNode> configs = new ArrayList<>();
            for (BusinessProfile.BusinessHourConfig h : profile.businessHours()) {
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("day_of_week", h.dayOfWeek());
                attrs.put("mode", h.mode());
                if ("specific_hours".equals(h.mode())) {
                    attrs.put("open_time", String.valueOf(toMinutes(h.dayOfWeek(), h.openTime())));
                    attrs.put("close_time", String.valueOf(toMinutes(h.dayOfWeek(), h.closeTime())));
                }
                configs.add(new BinaryNode("business_hours_config", attrs, null));
            }
            Map<String, String> hoursAttrs = new LinkedHashMap<>();
            if (profile.businessHoursTimeZone() != null) hoursAttrs.put("timezone", profile.businessHoursTimeZone());
            node.add(new BinaryNode("business_hours", hoursAttrs, configs));
        }
        BinaryNode bizProfile = new BinaryNode("business_profile",
            Map.of("v", "3", "mutation_type", "delta"), node);
        return new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "w:biz", "id", iqId),
            List.of(bizProfile));
    }

    /** Baileys {@code updateCoverPhoto} delta (upload step excluded — see class Javadoc). */
    public static BinaryNode buildCoverPhotoUpdate(String iqId, String fbid, String metaHmac, long ts) {
        require(iqId, "iqId");
        require(fbid, "fbid");
        require(metaHmac, "metaHmac");
        BinaryNode cover = new BinaryNode("cover_photo",
            Map.of("id", fbid, "op", "update", "token", metaHmac, "ts", String.valueOf(ts)), null);
        return coverPhotoIq(iqId, cover);
    }

    /** Baileys {@code removeCoverPhoto} delta. */
    public static BinaryNode buildCoverPhotoDelete(String iqId, String coverPhotoId) {
        require(iqId, "iqId");
        require(coverPhotoId, "coverPhotoId");
        BinaryNode cover = new BinaryNode("cover_photo", Map.of("op", "delete", "id", coverPhotoId), null);
        return coverPhotoIq(iqId, cover);
    }

    /** Baileys {@code parseCatalogNode}: null result or missing catalog node yields an empty result. */
    public static CatalogResult parseCatalogResponse(BinaryNode result) {
        if (result == null) return new CatalogResult(List.of(), null);
        BinaryNode catalog = result.child("product_catalog");
        if (catalog == null) return new CatalogResult(List.of(), null);
        List<CatalogProduct> products = new ArrayList<>();
        for (BinaryNode p : catalog.children("product")) {
            CatalogProduct parsed = parseProductNode(p);
            if (parsed != null) products.add(parsed);
        }
        BinaryNode paging = catalog.child("paging");
        String cursor = paging == null ? null : childText(paging, "after");
        return new CatalogResult(products, cursor);
    }

    /** Baileys {@code parseCollectionsNode}: null result or missing node yields an empty list. */
    public static List<Collection> parseCollectionsResponse(BinaryNode result) {
        List<Collection> out = new ArrayList<>();
        if (result == null) return out;
        BinaryNode collections = result.child("collections");
        if (collections == null) return out;
        for (BinaryNode c : collections.children("collection")) {
            List<CatalogProduct> products = new ArrayList<>();
            for (BinaryNode p : c.children("product")) {
                CatalogProduct parsed = parseProductNode(p);
                if (parsed != null) products.add(parsed);
            }
            BinaryNode statusNode = c.child("status_info");
            out.add(new Collection(childText(c, "id"), childText(c, "name"), products,
                statusNode == null ? null : childText(statusNode, "status"),
                statusNode != null && "true".equals(childText(statusNode, "can_appeal"))));
        }
        return out;
    }

    /** Baileys {@code parseProductNode}: null node yields null; missing text fields yield nulls, price NaN. */
    public static CatalogProduct parseProductNode(BinaryNode productNode) {
        if (productNode == null) return null;
        BinaryNode media = productNode.child("media");
        BinaryNode image = media == null ? null : media.child("image");
        BinaryNode statusInfo = productNode.child("status_info");
        double price = Double.NaN;
        String priceText = childText(productNode, "price");
        if (priceText != null) {
            try {
                price = Double.parseDouble(priceText);
            } catch (NumberFormatException ignored) {
                // Baileys' unary + yields NaN for unparseable prices; preserved here.
            }
        }
        return new CatalogProduct(
            childText(productNode, "id"),
            childText(productNode, "name"),
            childText(productNode, "retailer_id"),
            childText(productNode, "url"),
            childText(productNode, "description"),
            price,
            childText(productNode, "currency"),
            "true".equals(productNode.attr("is_hidden")),
            image == null ? null : childText(image, "request_image_url"),
            image == null ? null : childText(image, "original_image_url"),
            statusInfo == null ? null : childText(statusInfo, "status"));
    }

    /** Extracts the product node from a create/edit response and parses it, or null when absent. */
    public static CatalogProduct parseProductResponse(BinaryNode result, String wrapperTag) {
        if (result == null || wrapperTag == null) return null;
        BinaryNode wrapper = result.child(wrapperTag);
        if (wrapper == null) return null;
        return parseProductNode(wrapper.child("product"));
    }

    /** Baileys {@code productDelete} result: {@code deleted_count} attr, 0 when absent/unparseable. */
    public static int parseProductDeleteResponse(BinaryNode result) {
        if (result == null) return 0;
        BinaryNode del = result.child("product_catalog_delete");
        if (del == null) return 0;
        String raw = del.attr("deleted_count");
        if (raw == null) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Baileys {@code toProductNode} (without the image-upload step — see class Javadoc). */
    static BinaryNode toProductNode(String productId, CatalogProduct.ProductInput product) {
        Map<String, String> attrs = new LinkedHashMap<>();
        List<BinaryNode> content = new ArrayList<>();
        if (productId != null) content.add(textNode("id", productId));
        if (product.name() != null) content.add(textNode("name", product.name()));
        if (product.description() != null) content.add(textNode("description", product.description()));
        if (product.retailerId() != null) content.add(textNode("retailer_id", product.retailerId()));
        if (!product.imageUrls().isEmpty()) {
            List<BinaryNode> images = new ArrayList<>();
            for (String url : product.imageUrls()) {
                images.add(new BinaryNode("image", Map.of(),
                    List.of(textNode("url", url))));
            }
            content.add(new BinaryNode("media", Map.of(), images));
        }
        if (product.price() != null) content.add(textNode("price", String.valueOf(product.price())));
        if (product.currency() != null) content.add(textNode("currency", product.currency()));
        if (product.originCountryCodePresent()) {
            if (product.originCountryCode() == null || product.originCountryCode().isEmpty()) {
                // Baileys: undefined originCountryCode -> COUNTRY_ORIGIN_EXEMPT.
                // An explicitly empty string is treated the same here, since
                // Baileys' undefined check cannot be expressed with a null
                // Java field without the presence flag.
                attrs.put("compliance_category", "COUNTRY_ORIGIN_EXEMPT");
            } else {
                content.add(new BinaryNode("compliance_info", Map.of(),
                    List.of(textNode("country_code_origin", product.originCountryCode()))));
            }
        }
        if (product.isHidden() != null) attrs.put("is_hidden", String.valueOf(product.isHidden()));
        return new BinaryNode("product", attrs, content);
    }

    private static BinaryNode catalogEditIq(String iqId, String wrapperTag, BinaryNode product) {
        BinaryNode wrapper = new BinaryNode(wrapperTag, Map.of("v", "1"),
            List.of(product, new BinaryNode("width", Map.of(), "100"),
                new BinaryNode("height", Map.of(), "100")));
        return new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "w:biz:catalog", "id", iqId),
            List.of(wrapper));
    }

    private static BinaryNode coverPhotoIq(String iqId, BinaryNode cover) {
        BinaryNode bizProfile = new BinaryNode("business_profile",
            Map.of("v", "3", "mutation_type", "delta"), List.of(cover));
        return new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "set", "xmlns", "w:biz", "id", iqId),
            List.of(bizProfile));
    }

    private static BinaryNode textNode(String tag, String text) {
        return new BinaryNode(tag, Map.of(), text == null ? "" : text.getBytes(StandardCharsets.UTF_8));
    }

    private static String childText(BinaryNode parent, String tag) {
        BinaryNode child = parent.child(tag);
        if (child == null) return null;
        String text = child.textContent();
        return text == null || text.isEmpty() ? null : text;
    }

    private static int toMinutes(String day, String hhmm) {
        if (hhmm == null) throw new IllegalArgumentException("business hour time for " + day + " must not be null");
        String[] parts = hhmm.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("business hour time for " + day + " must be HH:MM, got: " + hhmm);
        }
        try {
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) throw new NumberFormatException();
            return h * 60 + m;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("business hour time for " + day + " must be HH:MM, got: " + hhmm);
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
