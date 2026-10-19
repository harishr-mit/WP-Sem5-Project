package com.mitpass.pass.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.mitpass.pass.config.PassProperties;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Renders a pass token as a QR code (PROJECT.md §P4, D12): error correction {@code error-correction} (M: about 15 %
 * of the code may be covered), a centred badge covering {@code logo-ratio} of the width, dark modules in the event's
 * colour on white, or in {@code dark-color} when that colour is too light to scan reliably ({@link #moduleColor}).
 * PNG is drawn with Java2D; SVG is one {@code <path>} plus the badge. The badge keeps the event colour and is a drawn
 * "MIT" monogram unless {@code mitpass.passes.qr.logo} points at a PNG.
 */
@Component
public class QrRenderer {

    private static final Logger log = LoggerFactory.getLogger(QrRenderer.class);
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final PassProperties.Qr settings;
    private final BufferedImage customLogo;

    public QrRenderer(PassProperties properties, ResourceLoader resources) {
        this.settings = properties.qr();
        this.customLogo = loadLogo(settings.logo(), resources);
    }

    /**
     * One pixel per module, quiet zone included. The code is read back with ZXing's reader (the family html5-qrcode
     * uses on the scanner): for about 2 % of tokens it can't find the code drawn with the mask the encoder picks, so
     * the other masks are tried until one reads (always the case in tests; D12, 2026-10-19).
     */
    public BitMatrix matrix(String text) {
        BitMatrix chosen = encode(text, null);
        if (readsBack(chosen, text)) {
            return chosen;
        }
        for (int mask = 0; mask < 8; mask++) {
            BitMatrix candidate = encode(text, mask);
            if (readsBack(candidate, text)) {
                return candidate;
            }
        }
        log.warn("No QR mask reads back for a {}-character text; using the encoder's choice", text.length());
        return chosen;
    }

    private BitMatrix encode(String text, Integer mask) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, settings.errorCorrection());
        hints.put(EncodeHintType.MARGIN, settings.margin());
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        if (mask != null) {
            hints.put(EncodeHintType.QR_MASK_PATTERN, mask);
        }
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException ex) {
            throw new IllegalArgumentException("Text does not fit in a QR code", ex);
        }
    }

    /** Draws the matrix in black on white with the badge area blank, as a camera would see it, and decodes it. */
    private boolean readsBack(BitMatrix matrix, String text) {
        int scale = 4;
        int width = matrix.getWidth() * scale;
        int badge = (int) Math.round(width * settings.logoRatio());
        int badgeStart = (width - badge) / 2;
        int[] pixels = new int[width * width];
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                boolean inBadge =
                        x >= badgeStart && x < badgeStart + badge && y >= badgeStart && y < badgeStart + badge;
                boolean dark = !inBadge && matrix.get(x / scale, y / scale);
                pixels[y * width + x] = dark ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, width, pixels)));
        try {
            return text.equals(new QRCodeReader().decode(bitmap).getText());
        } catch (ReaderException ex) {
            return false;
        }
    }

    public byte[] png(String text, String color) {
        BitMatrix matrix = matrix(text);
        int scale = Math.max(1, settings.size() / matrix.getWidth());
        int width = matrix.getWidth() * scale;
        BufferedImage image = new BufferedImage(width, width, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, width);
            g.setColor(Color.decode(moduleColor(color)));
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    if (matrix.get(x, y)) {
                        g.fillRect(x * scale, y * scale, scale, scale);
                    }
                }
            }
            drawBadge(g, width, Color.decode(colorOrDefault(color)));
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    public byte[] svg(String text, String color) {
        BitMatrix matrix = matrix(text);
        int n = matrix.getWidth();
        String fill = moduleColor(color);
        String badgeFill = colorOrDefault(color);
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (matrix.get(x, y)) {
                    path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        double badge = n * settings.logoRatio();
        double pad = badge * 0.12;
        double start = (n - badge) / 2;
        StringBuilder svg = new StringBuilder()
                .append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(n)
                .append(' ')
                .append(n)
                .append("\" shape-rendering=\"crispEdges\">")
                .append("<rect width=\"100%\" height=\"100%\" fill=\"#FFFFFF\"/>")
                .append("<path fill=\"")
                .append(fill)
                .append("\" d=\"")
                .append(path)
                .append("\"/>")
                .append("<rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\" rx=\"%.2f\" fill=\"#FFFFFF\"/>"
                        .formatted(start, start, badge, badge, pad));
        if (customLogo != null) {
            svg.append("<image x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\" href=\"data:image/png;base64,%s\"/>"
                    .formatted(start + pad, start + pad, badge - 2 * pad, badge - 2 * pad, logoBase64()));
        } else {
            svg.append("<rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\" rx=\"%.2f\" fill=\"%s\"/>"
                            .formatted(start + pad, start + pad, badge - 2 * pad, badge - 2 * pad, pad, badgeFill))
                    .append(
                            "<text x=\"%.2f\" y=\"%.2f\" font-family=\"Arial, Helvetica, sans-serif\" font-weight=\"bold\""
                                            .formatted(n / 2.0, n / 2.0)
                                    + " font-size=\"%.2f\" fill=\"#FFFFFF\" text-anchor=\"middle\" dominant-baseline=\"central\">MIT</text>"
                                            .formatted((badge - 2 * pad) * 0.42));
        }
        svg.append("</svg>");
        return svg.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The event's colour, or the default maroon: the badge's colour, and the modules' when it is dark enough. */
    public String colorOrDefault(String color) {
        return color != null && HEX.matcher(color).matches() ? color.toUpperCase() : settings.defaultColor();
    }

    /**
     * The colour of the dark modules: the event's colour if it contrasts with white by at least {@code min-contrast}
     * (WCAG contrast ratio), otherwise {@code dark-color}. Camera decoders need strong contrast; a pale event colour
     * would make the code unreadable on a phone screen.
     */
    public String moduleColor(String color) {
        String chosen = colorOrDefault(color);
        return contrastWithWhite(chosen) >= settings.minContrast() ? chosen : settings.darkColor();
    }

    static double contrastWithWhite(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double luminance =
                0.2126 * linear((rgb >> 16) & 0xFF) + 0.7152 * linear((rgb >> 8) & 0xFF) + 0.0722 * linear(rgb & 0xFF);
        return 1.05 / (luminance + 0.05);
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private void drawBadge(Graphics2D g, int width, Color color) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int badge = (int) Math.round(width * settings.logoRatio());
        int pad = Math.max(2, badge / 8);
        int x = (width - badge) / 2;
        g.setColor(Color.WHITE);
        g.fillRoundRect(x, x, badge, badge, pad * 2, pad * 2);
        int inner = badge - 2 * pad;
        if (customLogo != null) {
            g.drawImage(customLogo, x + pad, x + pad, inner, inner, null);
            return;
        }
        g.setColor(color);
        g.fillRoundRect(x + pad, x + pad, inner, inner, pad * 2, pad * 2);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, (int) (inner * 0.42))));
        FontMetrics metrics = g.getFontMetrics();
        String text = "MIT";
        int textX = x + pad + (inner - metrics.stringWidth(text)) / 2;
        int textY = x + pad + (inner - metrics.getHeight()) / 2 + metrics.getAscent();
        g.drawString(text, textX, textY);
    }

    private String logoBase64() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(customLogo, "png", out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static BufferedImage loadLogo(String location, ResourceLoader resources) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try (InputStream in = resources.getResource(location).getInputStream()) {
            BufferedImage logo = ImageIO.read(in);
            if (logo == null) {
                throw new IllegalStateException("mitpass.passes.qr.logo is not a readable image: " + location);
            }
            return logo;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read mitpass.passes.qr.logo " + location, ex);
        }
    }
}
