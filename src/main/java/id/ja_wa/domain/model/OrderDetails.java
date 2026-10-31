package id.ja_wa.domain.model;

import java.util.List;

/**
 * Detailed order information retrieved from WhatsApp Business catalog or cart.
 *
 * @param id Unique order ID
 * @param createdAt Creation timestamp in seconds
 * @param catalogId Originating catalog identifier
 * @param price Total and subtotal pricing details
 * @param products List of purchased products and quantities
 */
public record OrderDetails(
    String id,
    long createdAt,
    String catalogId,
    OrderPrice price,
    List<OrderProduct> products
) {
    /**
     * Price summary for the order.
     *
     * @param subtotal Subtotal in minor currency units (e.g. cents)
     * @param total Final total in minor currency units
     * @param currency ISO currency code (e.g. "USD", "IDR")
     * @param priceStatus Settlement status string
     */
    public record OrderPrice(long subtotal, long total, String currency, String priceStatus) {}

    /**
     * An itemized product within the order.
     *
     * @param id Product item identifier
     * @param name Product title
     * @param price Unit price
     * @param currency Currency code
     * @param quantity Number of units ordered
     * @param imageId Media identifier for the product thumbnail
     * @param imageUrl Direct URL to product photo
     */
    public record OrderProduct(String id, String name, long price, String currency, int quantity, String imageId, String imageUrl) {}
}
