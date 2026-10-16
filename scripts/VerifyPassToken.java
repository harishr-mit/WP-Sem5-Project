import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies a MITPass pass token (the text inside a pass QR code) with the JDK only, independent of pass-service's code
 * (docs/plans/phase-4.md §14, exit criterion 3). The token is a compact JWS signed with ES256 (P-256, SHA-256, raw
 * R||S signature); the public key comes from the pass JWK set, picked by the token's kid.
 *
 * <pre>
 *   .tools/jdk-21/bin/java scripts/VerifyPassToken.java &lt;token&gt; [jwks-url-or-file]
 *   default JWK set: http://localhost:8080/.well-known/pass-jwks.json (through the gateway)
 * </pre>
 *
 * Prints the header and claims, then VALID, or INVALID SIGNATURE / EXPIRED / UNKNOWN KID / MALFORMED (exit code 1).
 */
public class VerifyPassToken {

    static final String DEFAULT_JWKS = "http://localhost:8080/.well-known/pass-jwks.json";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java scripts/VerifyPassToken.java <token> [jwks-url-or-file]");
            System.exit(2);
        }
        String result = verify(args[0].strip(), load(args.length > 1 ? args[1] : DEFAULT_JWKS), Instant.now());
        System.out.println(result);
        System.exit(result.startsWith("VALID") ? 0 : 1);
    }

    static String verify(String token, String jwks, Instant now) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return "MALFORMED: a compact JWS has three dot-separated parts";
        }
        String header = decode(parts[0]);
        String claims = decode(parts[1]);
        System.out.println("header: " + header);
        System.out.println("claims: " + claims);

        if (!"ES256".equals(field(header, "alg"))) {
            return "MALFORMED: alg must be ES256, is " + field(header, "alg");
        }
        String kid = field(header, "kid");
        String key = keyObject(jwks, kid);
        if (key == null) {
            return "UNKNOWN KID: " + kid + " is not in the pass JWK set";
        }
        PublicKey publicKey = p256(field(key, "x"), field(key, "y"));
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        boolean signatureOk;
        try {
            signatureOk = verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (java.security.SignatureException | IllegalArgumentException ex) {
            signatureOk = false;
        }
        if (!signatureOk) {
            return "INVALID SIGNATURE: the token was changed or not signed by MITPass";
        }
        Instant exp = Instant.ofEpochSecond(Long.parseLong(number(claims, "exp")));
        String expText = exp.atZone(ZoneId.of("Asia/Kolkata")) + " (campus time)";
        if (!now.isBefore(exp)) {
            return "EXPIRED at " + expText;
        }
        return "VALID: pass " + field(claims, "pid") + ", event " + number(claims, "eid") + ", kind "
                + field(claims, "knd") + ", expires " + expText;
    }

    static String load(String source) throws Exception {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(source)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("GET " + source + " answered " + response.statusCode());
                }
                return response.body();
            }
        }
        return Files.readString(Path.of(source));
    }

    /** The public key from its JWK coordinates (base64url, unsigned big-endian). */
    static PublicKey p256(String x, String y) throws Exception {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(
                new BigInteger(1, Base64.getUrlDecoder().decode(x)),
                new BigInteger(1, Base64.getUrlDecoder().decode(y)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
    }

    /** The JWK object (flat JSON, no nesting) whose kid matches. */
    static String keyObject(String jwks, String kid) {
        Matcher objects = Pattern.compile("\\{[^{}]*}").matcher(jwks);
        while (objects.find()) {
            String object = objects.group();
            if (kid != null && kid.equals(field(object, "kid"))) {
                return object;
            }
        }
        return null;
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String number(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }
}
