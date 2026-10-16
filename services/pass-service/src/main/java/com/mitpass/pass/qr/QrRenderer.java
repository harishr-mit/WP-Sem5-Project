package com.mitpass.pass.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
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
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Renders a pass token as a QR code (PROJECT.md §P4): error correction H (about 30 % of the code may be covered), a
 * centred badge covering {@code logo-ratio} of the width, dark modules in the event's colour on white. PNG is drawn
 * with Java2D; SVG is one {@code <path>} plus the badge. The badge is a drawn "MIT" monogram unless
 * {@code mitpass.passes.qr.logo} points at a PNG.
 */
@Component
public class QrRenderer {

    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final PassProperties.Qr settings;
    private final BufferedImage customLogo;

    public QrRenderer(PassProperties properties, ResourceLoader resources) {
        this.settings = properties.qr();
        this.customLogo = loadLogo(settings.logo(), resources);
    }

    /** One pixel per module, quiet zone included. */
    public BitMatrix matrix(String text) {
        try {
            return new QRCodeWriter()
                    .encode(
                            text,
                            BarcodeFormat.QR_CODE,
                            0,
                            0,
                            Map.of(
                                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                                    EncodeHintType.MARGIN, settings.margin(),
                                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name()));
        } catch (WriterException ex) {
            throw new IllegalArgumentException("Text does not fit in a QR code", ex);
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
            g.setColor(Color.decode(colorOrDefault(color)));
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
        String fill = colorOrDefault(color);
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
                            .formatted(start + pad, start + pad, badge - 2 * pad, badge - 2 * pad, pad, fill))
                    .append(
                            "<text x=\"%.2f\" y=\"%.2f\" font-family=\"Arial, Helvetica, sans-serif\" font-weight=\"bold\""
                                            .formatted(n / 2.0, n / 2.0)
                                    + " font-size=\"%.2f\" fill=\"#FFFFFF\" text-anchor=\"middle\" dominant-baseline=\"central\">MIT</text>"
                                            .formatted((badge - 2 * pad) * 0.42));
        }
        svg.append("</svg>");
        return svg.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The colour used for dark modules: the event's, or the default maroon. */
    public String colorOrDefault(String color) {
        return color != null && HEX.matcher(color).matches() ? color.toUpperCase() : settings.defaultColor();
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
