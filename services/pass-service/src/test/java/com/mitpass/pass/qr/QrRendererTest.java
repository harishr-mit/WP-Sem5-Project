package com.mitpass.pass.qr;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.mitpass.pass.config.PassProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** The rendered QR decodes back to the token, with the logo badge in place and the event's colour (PROJECT.md §P4). */
public class QrRendererTest {

    /** About the size of a real pass token (ES256 JWS with our claims). */
    static final String TOKEN =
            "eyJ0eXAiOiJtaXRwYXNzLXBhc3MrandzIiwia2lkIjoicGFzcy0yMDI2MTEwMS0wNDAwMDAtNTRkNSIsImFsZyI6"
                    + "IkVTMjU2In0.eyJzdWIiOiI3ZjJjMWQ5ZS0wYjQxLTRhMmEtOGI2Ni0xYTJiM2M0ZDVlNmYiLCJlaWQiOjQyLCJpc3MiOiJtaXRwYXNz"
                    + "LXBhc3MiLCJrbmQiOiJMIiwiZXhwIjoxNzk0NzI1NjQ1LCJpYXQiOjE3OTQ3MjU2MDAsInBpZCI6IjBiN2E5YzNlLTQ1ZjEtNGMyZC05"
                    + "ZTZhLTFmOGQyYzNiNGE1ZSIsImp0aSI6ImM0ZDVlNmY3LWE4YjktNGMwZC1iMWUyLWYzYTRiNWM2ZDdlOCJ9.Qm9ndXNTaWduYXR1cmVG"
                    + "b3JSZW5kZXJpbmdUZXN0c09ubHlCb2d1c1NpZ25hdHVyZUZvclJlbmRlcmluZ1Rlc3RzT25seQ";

    final QrRenderer renderer = new QrRenderer(properties(), new DefaultResourceLoader());

    @Test
    void pngDecodesBackToTheTokenWithTheBadgeInPlace() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(renderer.png(TOKEN, "#1D4E89")));

        assertThat(decode(image)).isEqualTo(TOKEN);
        assertThat(image.getWidth()).isBetween(300, 400);
        // Inside the badge, just right of its left inner edge (left of the white "MIT" text): the event colour.
        int width = image.getWidth();
        int badge = (int) Math.round(width * 0.2);
        int innerLeft = (width - badge) / 2 + Math.max(2, badge / 8);
        assertThat(image.getRGB(innerLeft + 2, width / 2) & 0xFFFFFF).isEqualTo(0x1D4E89);
        // Kept for an eye check: target/qr-sample.png
        ImageIO.write(image, "png", new java.io.File("target", "qr-sample.png"));
        assertThat(image.getRGB(1, 1) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    @Test
    void defaultColourIsMaroonAndBadColoursFallBackToIt() {
        assertThat(renderer.colorOrDefault(null)).isEqualTo("#7A1F2B");
        assertThat(renderer.colorOrDefault("not-a-colour")).isEqualTo("#7A1F2B");
        assertThat(renderer.colorOrDefault("#1d4e89")).isEqualTo("#1D4E89");
    }

    @Test
    void svgHasTheModulesTheBadgeAndTheColour() throws Exception {
        String svg = new String(renderer.svg(TOKEN, "#5B2A86"), StandardCharsets.UTF_8);

        assertThat(svg)
                .startsWith("<svg")
                .endsWith("</svg>")
                .contains("fill=\"#5B2A86\"")
                .contains(">MIT</text>");
        int modules = renderer.matrix(TOKEN).getWidth();
        assertThat(svg).contains("viewBox=\"0 0 " + modules + " " + modules + "\"");
    }

    @Test
    void errorCorrectionIsHigh() {
        // Level H needs a larger symbol than level L for the same text: version >= 14 for ~430 characters.
        assertThat(renderer.matrix(TOKEN).getWidth()).isGreaterThanOrEqualTo(17 + 4 * 14 + 2 * 2);
    }

    private static String decode(BufferedImage image) throws Exception {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        return new QRCodeReader()
                .decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE))
                .getText();
    }

    public static PassProperties properties() {
        return new PassProperties(
                "mitpass-pass",
                "secret",
                Duration.ofSeconds(45),
                Duration.ofSeconds(30),
                Duration.ofHours(2),
                Duration.ofMinutes(150),
                Duration.ofHours(2),
                Duration.ofSeconds(60),
                ZoneId.of("Asia/Kolkata"),
                new PassProperties.Scheduler(false, Duration.ofSeconds(30)),
                new PassProperties.Qr(400, 2, "", 0.2, "#7A1F2B"));
    }
}
