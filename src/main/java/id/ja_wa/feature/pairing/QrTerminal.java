package id.ja_wa.feature.pairing;


import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * Renders QR codes directly into ANSI terminal text using half-block Unicode characters.
 * <p>
 * Enables terminal QR scanning for WhatsApp device pairing without external UI dependencies.
 */
public final class QrTerminal {

    private static final String ESC = "";

    private static final String INK = ESC + "[48;5;255;38;5;232m";
    private static final String RESET = ESC + "[0m";

    private static final char FULL  = '█';
    private static final char UPPER = '▀';
    private static final char LOWER = '▄';
    private static final char SPACE = ' ';

    private QrTerminal() {}

    public static String render(String text) {
        return render(text, 2);
    }

    public static String render(String text, int quiet) {
        final BitMatrix m = encode(text);
        final int w = m.getWidth();
        final int h = m.getHeight();

        StringBuilder sb = new StringBuilder((w + 2 * quiet) * (h / 2 + quiet) * 12);

        int topQuietLines = (quiet + 1) / 2;
        for (int i = 0; i < topQuietLines; i++) sb.append(blankLine(w + 2 * quiet));

        for (int y = 0; y < h; y += 2) {
            sb.append(INK);

            for (int i = 0; i < quiet; i++) sb.append(SPACE);
            for (int x = 0; x < w; x++) {
                boolean top = m.get(x, y);
                boolean bot = (y + 1 < h) && m.get(x, y + 1);
                sb.append(top && bot ? FULL : top ? UPPER : bot ? LOWER : SPACE);
            }

            for (int i = 0; i < quiet; i++) sb.append(SPACE);
            sb.append(RESET).append('\n');
        }

        int bottomQuietLines = (quiet + 1) / 2;

        for (int i = 0; i < bottomQuietLines; i++) sb.append(blankLine(w + 2 * quiet));

        return sb.toString();
    }

    private static BitMatrix encode(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.MARGIN, 0);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new IllegalStateException("QR encoding failed for input of length " + text.length(), e);
        }
    }

    private static String blankLine(int cols) {
        StringBuilder sb = new StringBuilder(cols + 16);
        sb.append(INK);
        for (int i = 0; i < cols; i++) sb.append(SPACE);
        sb.append(RESET).append('\n');
        return sb.toString();
    }
}
