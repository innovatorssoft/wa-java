package id.jawa.feature.catalog;

import id.jawa.domain.model.BusinessProfile;
import id.jawa.domain.model.CatalogProduct;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogQueryTest {

    private static BinaryNode text(String tag, String value) {
        return new BinaryNode(tag, Map.of(), value);
    }

    @Test
    void catalogQueryHasBaileysShape() {
        BinaryNode iq = CatalogQuery.buildCatalogQuery("iq1", "999@s.whatsapp.net", 10, "cursor1");
        assertThat(iq.attrs()).containsEntry("xmlns", "w:biz:catalog")
            .containsEntry("type", "get")
            .containsEntry("to", "s.whatsapp.net");
        BinaryNode catalog = iq.child("product_catalog");
        assertThat(catalog.attr("jid")).isEqualTo("999@s.whatsapp.net");
        assertThat(catalog.attr("allow_shop_source")).isEqualTo("true");
        assertThat(catalog.child("limit").textContent()).isEqualTo("10");
        assertThat(catalog.child("width").textContent()).isEqualTo("100");
        assertThat(catalog.child("after").textContent()).isEqualTo("cursor1");
    }

    @Test
    void collectionsQueryHasSmaxIdAndDefaults() {
        BinaryNode iq = CatalogQuery.buildCollectionsQuery("iq1", "999@s.whatsapp.net", 0);
        assertThat(iq.attr("smax_id")).isEqualTo("35");
        assertThat(iq.child("collections").attr("biz_jid")).isEqualTo("999@s.whatsapp.net");
        assertThat(iq.child("collections").child("collection_limit").textContent()).isEqualTo("51");
    }

    @Test
    void productCreateNodeMatchesBaileysToProductNode() {
        CatalogProduct.ProductInput input = new CatalogProduct.ProductInput(
            "Widget", "A widget", "SKU-1", List.of("https://example.invalid/img.jpg"),
            12.5, "USD", false);
        BinaryNode iq = CatalogQuery.buildProductCreate("iq1", input);
        BinaryNode wrapper = iq.child("product_catalog_add");
        assertThat(wrapper.attr("v")).isEqualTo("1");
        BinaryNode product = wrapper.child("product");
        assertThat(product.child("name").textContent()).isEqualTo("Widget");
        assertThat(product.child("retailer_id").textContent()).isEqualTo("SKU-1");
        assertThat(product.child("price").textContent()).isEqualTo("12.5");
        assertThat(product.child("currency").textContent()).isEqualTo("USD");
        assertThat(product.attr("is_hidden")).isEqualTo("false");
        assertThat(product.child("media").child("image").child("url").textContent())
            .isEqualTo("https://example.invalid/img.jpg");
        assertThat(wrapper.child("width").textContent()).isEqualTo("100");
    }

    @Test
    void productUpdateIncludesIdAndOriginExempt() {
        CatalogProduct.ProductInput input = new CatalogProduct.ProductInput(
            null, null, null, List.of(), null, null, null, null, true);
        BinaryNode iq = CatalogQuery.buildProductUpdate("iq1", "prod-9", input);
        BinaryNode product = iq.child("product_catalog_edit").child("product");
        assertThat(product.child("id").textContent()).isEqualTo("prod-9");
        assertThat(product.attr("compliance_category")).isEqualTo("COUNTRY_ORIGIN_EXEMPT");
    }

    @Test
    void productDeleteListsIds() {
        BinaryNode iq = CatalogQuery.buildProductDelete("iq1", List.of("a", "b"));
        assertThat(iq.child("product_catalog_delete").attr("v")).isEqualTo("1");
        assertThat(iq.child("product_catalog_delete").children("product")).hasSize(2);
        assertThatThrownBy(() -> CatalogQuery.buildProductDelete("iq1", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void businessProfileUpdateConvertsHoursToMinutes() {
        BusinessProfile profile = new BusinessProfile("999@s.whatsapp.net", "1 Main St",
            "hi@example.invalid", "Desc", List.of("https://example.invalid"), List.of(),
            "UTC", List.of(new BusinessProfile.BusinessHourConfig("mon", "specific_hours", "09:00", "17:30")),
            Map.of());
        BinaryNode iq = CatalogQuery.buildBusinessProfileUpdate("iq1", profile);
        BinaryNode biz = iq.child("business_profile");
        assertThat(biz.attr("v")).isEqualTo("3");
        assertThat(biz.attr("mutation_type")).isEqualTo("delta");
        assertThat(biz.child("address").textContent()).isEqualTo("1 Main St");
        assertThat(biz.child("website").textContent()).isEqualTo("https://example.invalid");
        BinaryNode hours = biz.child("business_hours");
        assertThat(hours.attr("timezone")).isEqualTo("UTC");
        BinaryNode config = hours.child("business_hours_config");
        assertThat(config.attr("day_of_week")).isEqualTo("mon");
        assertThat(config.attr("open_time")).isEqualTo("540");
        assertThat(config.attr("close_time")).isEqualTo("1050");
    }

    @Test
    void businessProfileUpdateRejectsBadHourFormat() {
        BusinessProfile profile = new BusinessProfile("999@s.whatsapp.net", null, null, null, null, null,
            null, List.of(new BusinessProfile.BusinessHourConfig("mon", "specific_hours", "9am", "17:00")),
            null);
        assertThatThrownBy(() -> CatalogQuery.buildBusinessProfileUpdate("iq1", profile))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coverPhotoBuilders() {
        BinaryNode update = CatalogQuery.buildCoverPhotoUpdate("iq1", "fb1", "hmac1", 123L);
        BinaryNode cover = update.child("business_profile").child("cover_photo");
        assertThat(cover.attrs()).containsEntry("id", "fb1").containsEntry("op", "update")
            .containsEntry("token", "hmac1").containsEntry("ts", "123");
        BinaryNode del = CatalogQuery.buildCoverPhotoDelete("iq1", "fb1");
        assertThat(del.child("business_profile").child("cover_photo").attr("op")).isEqualTo("delete");
    }

    @Test
    void parseCatalogAndProductNodes() {
        BinaryNode productNode = new BinaryNode("product", Map.of("is_hidden", "true"), List.of(
            text("id", "p1"), text("name", "Widget"), text("price", "12.5"), text("currency", "USD"),
            new BinaryNode("media", Map.of(), List.of(
                new BinaryNode("image", Map.of(), List.of(
                    text("request_image_url", "https://example.invalid/req"),
                    text("original_image_url", "https://example.invalid/orig"))))),
            new BinaryNode("status_info", Map.of(), List.of(text("status", "approved")))));
        CatalogProduct product = CatalogQuery.parseProductNode(productNode);
        assertThat(product.id()).isEqualTo("p1");
        assertThat(product.price()).isEqualTo(12.5);
        assertThat(product.isHidden()).isTrue();
        assertThat(product.availability()).isEqualTo("in stock");
        assertThat(product.imageRequestedUrl()).isEqualTo("https://example.invalid/req");
        assertThat(product.reviewStatusWhatsapp()).isEqualTo("approved");

        BinaryNode result = new BinaryNode("iq", Map.of(), List.of(
            new BinaryNode("product_catalog", Map.of(), List.of(productNode,
                new BinaryNode("paging", Map.of(), List.of(text("after", "next1")))))));
        CatalogQuery.CatalogResult catalog = CatalogQuery.parseCatalogResponse(result);
        assertThat(catalog.products()).hasSize(1);
        assertThat(catalog.nextPageCursor()).isEqualTo("next1");
        assertThat(CatalogQuery.parseCatalogResponse(null).products()).isEmpty();
        assertThat(CatalogQuery.parseProductNode(null)).isNull();
    }

    @Test
    void parseCollectionsAndDeleteCount() {
        BinaryNode result = new BinaryNode("iq", Map.of(), List.of(
            new BinaryNode("collections", Map.of(), List.of(
                new BinaryNode("collection", Map.of(), List.of(
                    text("id", "c1"), text("name", "Featured"),
                    new BinaryNode("status_info", Map.of(), List.of(
                        text("status", "active"), text("can_appeal", "true")))))))));
        List<CatalogQuery.Collection> collections = CatalogQuery.parseCollectionsResponse(result);
        assertThat(collections).hasSize(1);
        assertThat(collections.get(0).id()).isEqualTo("c1");
        assertThat(collections.get(0).status()).isEqualTo("active");
        assertThat(collections.get(0).canAppeal()).isTrue();
        assertThat(CatalogQuery.parseCollectionsResponse(null)).isEmpty();

        BinaryNode del = new BinaryNode("iq", Map.of(), List.of(
            new BinaryNode("product_catalog_delete", Map.of("deleted_count", "2"), null)));
        assertThat(CatalogQuery.parseProductDeleteResponse(del)).isEqualTo(2);
        assertThat(CatalogQuery.parseProductDeleteResponse(null)).isZero();
    }
}
