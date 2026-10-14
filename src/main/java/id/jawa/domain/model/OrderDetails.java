package id.jawa.domain.model;

import java.util.List;

public record OrderDetails(
    String id,
    long createdAt,
    String catalogId,
    OrderPrice price,
    List<OrderProduct> products
) {
    public record OrderPrice(long subtotal, long total, String currency, String priceStatus) {}
    public record OrderProduct(String id, String name, long price, String currency, int quantity, String imageId, String imageUrl) {}
}
