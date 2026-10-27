package id.jawa.feature.newsletter;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * JDK-only mirror of Baileys' {@code generateProfilePicture} for the
 * newsletter picture path ({@code lib/Utils/messages-media.js} and
 * {@code lib/Socket/newsletter.js:105 newsletterUpdatePicture}, which
 * calls it with no dimensions, so Baileys' defaults apply).
 *
 * <p>Verified output spec from the Baileys tree: the default dimensions
 * are 720x720 ({@code const { width: w = 720, height: h = 720 } =
 * dimensions || {}}), and the output is a JPEG at quality 80. The
 * backends differ in how they reach the square: the sharp branch calls
 * {@code .resize(w, h)} (sharp's default fit, which centre-crops), while
 * the Jimp branch explicitly crops a {@code min(width, height)} square
 * at (0, 0) and then resizes with bilinear interpolation. This
 * implementation follows the Jimp branch exactly, because it is the
 * only branch whose crop geometry is spelled out in the tree: crop the
 * top-left {@code min(width, height)} square, scale it to 720x720 with
 * bilinear interpolation, and encode JPEG at quality 0.80. Callers that
 * need sharp's centre-crop geometry must say so; it is not claimed here.
 *
 * <p>Only JDK image APIs are used ({@code javax.imageio}); no new
 * dependency is introduced. A null or undecodable input fails with
 * {@link IllegalArgumentException}; an encode failure surfaces as
 * {@link IllegalStateException}.
 */
public final class NewsletterProfilePicture {

    /** Baileys' default profile-picture width. */
    public static final int DEFAULT_SIZE = 720;

    /** Baileys' JPEG quality for profile pictures (80 in every backend). */
    public static final float JPEG_QUALITY = 0.80f;

    private NewsletterProfilePicture() {}

    /**
     * Resizes image bytes to Baileys' default newsletter profile
     * picture: a 720x720 JPEG at quality 80.
     *
     * @param imageBytes source image bytes in any format {@code ImageIO} can read
     * @return JPEG bytes of the 720x720 picture
     */
    public static byte[] generate(byte[] imageBytes) {
        return generate(imageBytes, DEFAULT_SIZE, DEFAULT_SIZE);
    }

    /**
     * Resizes image bytes to the given square-or-rectangle size,
     * mirroring Baileys' {@code generateProfilePicture(mediaUpload,
     * dimensions)} with explicit dimensions (the path Baileys'
     * group-picture update uses; the newsletter path uses
     * {@link #generate(byte[])}).
     *
     * @param imageBytes source image bytes in any format {@code ImageIO} can read
     * @param width target width in pixels, must be positive
     * @param height target height in pixels, must be positive
     * @return JPEG bytes of the resized picture
     */
    public static byte[] generate(byte[] imageBytes, int width, int height) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes must not be null or empty");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to decode image bytes", e);
        }
        if (source == null) {
            throw new IllegalArgumentException("unsupported or corrupt image bytes");
        }
        // Jimp branch: crop a min(width, height) square at (0, 0), then
        // resize with bilinear interpolation. (When the source is
        // already square the crop is a no-op.)
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage cropped = source.getSubimage(0, 0, side, side);
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cropped, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("no JPEG writer available");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(imageOut);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(scaled, null, null), param);
            writer.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to encode profile picture as JPEG", e);
        }
    }
}
