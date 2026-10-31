package id.ja_wa.domain.model;

/**
 * Avatar photo details and retrieval URLs for a user or group chat.
 *
 * @param id Unique photo revision ID
 * @param url Full HTTPS download URL for the avatar image
 * @param type Image format or resolution indicator ("preview" or "image")
 * @param directPath Internal WhatsApp CDN storage direct path
 */
public record ProfilePictureInfo(
    String id,
    String url,
    String type,
    String directPath
) {}
