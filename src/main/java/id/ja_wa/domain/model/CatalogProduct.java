package id.ja_wa.domain.model;

import java.util.List;

/**
 * A WhatsApp Business catalog product, in Baileys' parsed shape.
 *
 * <p>Baileys source: {@code parseProductNode} in
 * {@code lib/Utils/business.js}. Baileys hardcodes
 * {@code availability: 'in stock'} and reads the review status from
 * {@code status_info > status}; both are preserved here rather than
 * inventing richer availability/status modelling.
 *
 * @param id product id
 * @param name product name, null when absent
 * @param retailerId retailer SKU, null when absent
 * @param url product URL, null when absent
 * @param description product description, null when absent
 * @param price numeric price (Baileys coerces with {@code +string}); NaN when unparseable
 * @param currency currency code, null when absent
 * @param isHidden Baileys {@code is_hidden == 'true'}
 * @param imageRequestedUrl requested image URL from {@code media > image > request_image_url}, null when absent
 * @param imageOriginalUrl original image URL from {@code media > image > original_image_url}, null when absent
 * @param reviewStatusWhatsapp WhatsApp review status string, null when absent
 */
public record CatalogProduct(
    String id,
    String name,
    String retailerId,
    String url,
    String description,
    double price,
    String currency,
    boolean isHidden,
    String imageRequestedUrl,
    String imageOriginalUrl,
    String reviewStatusWhatsapp
) {
    /** Availability is always {@code in stock} in Baileys' parsed shape. */
    public String availability() {
        return "in stock";
    }

    /**
     * Input for creating or updating a product, in Baileys'
     * {@code toProductNode} shape ({@code lib/Utils/business.js}).
     *
     * @param name product name, null to leave unset on update
     * @param description product description, null to leave unset on update
     * @param retailerId retailer SKU, null to leave unset on update
     * @param imageUrls already-uploaded image URLs (Baileys throws unless
     *                  every image already has a URL — see below)
     * @param price price, null to leave unset on update
     * @param currency currency code, null to leave unset on update
     * @param isHidden hidden flag, null to leave unset on update
     * @param originCountryCode country-of-origin code; when non-null but
     *                          empty, Baileys sends
     *                          {@code compliance_category=COUNTRY_ORIGIN_EXEMPT}
     *                          instead of a {@code compliance_info} node
     * @param originCountryCodePresent whether {@code originCountryCode} was
     *                                 supplied at all (Baileys checks
     *                                 {@code 'originCountryCode' in product})
     */
    public record ProductInput(
        String name,
        String description,
        String retailerId,
        List<String> imageUrls,
        Double price,
        String currency,
        Boolean isHidden,
        String originCountryCode,
        boolean originCountryCodePresent
    ) {
        public ProductInput {
            imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        }

        /** Convenience constructor without origin-country compliance info. */
        public ProductInput(String name, String description, String retailerId, List<String> imageUrls,
                            Double price, String currency, Boolean isHidden) {
            this(name, description, retailerId, imageUrls, price, currency, isHidden, null, false);
        }
    }
}
