package com.mitpass.pass.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.pass.PassFixtures;
import com.mitpass.pass.PassFixtures.TestEvent;
import com.mitpass.pass.PassIntegrationTest;
import com.mitpass.pass.TestClock;
import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.keys.PassKeyService;
import com.mitpass.pass.repository.PassRepository;
import com.mitpass.pass.service.PassLifecycleService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code scripts/VerifyPassToken.java} (JDK only, none of our code) accepts a real pass token and rejects a tampered one
 * (exit criterion 3). Runs the script the way a presenter would: {@code java scripts/VerifyPassToken.java}.
 */
@PassIntegrationTest
class VerifyPassTokenScriptTest {

    static final Path SCRIPT = Path.of("..", "..", "scripts", "VerifyPassToken.java");

    @Autowired
    PassTokenService tokens;

    @Autowired
    PassKeyService keys;

    @Autowired
    PassLifecycleService lifecycle;

    @Autowired
    PassRepository passes;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TestClock clock;

    @TempDir
    Path dir;

    @Test
    void scriptVerifiesAGoodTokenAndRejectsATamperedOne() throws Exception {
        // The script checks expiry against the real time: sign a token that is still valid now.
        clock.set(java.time.Instant.now());
        TestEvent event = PassFixtures.event(Duration.ofDays(1));
        UUID registration = UUID.randomUUID();
        Pass pass = tx.execute(status -> {
            lifecycle.issue(PassFixtures.confirmed(event, registration, UUID.randomUUID()), TestClock.START);
            Pass issued = passes.findByRegistrationId(registration).orElseThrow();
            org.hibernate.Hibernate.initialize(issued.getEvent());
            return issued;
        });
        String token = tokens.live(pass).value();
        Path jwks = dir.resolve("pass-jwks.json");
        Files.writeString(jwks, keys.publicJwkSet().toString(), StandardCharsets.UTF_8);

        Result good = run(token, jwks);
        assertThat(good.exitCode()).as(good.output()).isZero();
        assertThat(good.output()).contains("VALID: pass " + pass.getId()).contains("kind L");

        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String edited = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        payload.replace("\"knd\":\"L\"", "\"knd\":\"S\"").getBytes(StandardCharsets.UTF_8));
        Result tampered = run(parts[0] + "." + edited + "." + parts[2], jwks);
        assertThat(tampered.exitCode()).isEqualTo(1);
        assertThat(tampered.output()).contains("INVALID SIGNATURE");
    }

    record Result(int exitCode, String output) {}

    private static Result run(String token, Path jwks) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(List.of(java, SCRIPT.toString(), token, jwks.toString()))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), output);
    }
}
