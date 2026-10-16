package com.mitpass.security.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.security.test.SecurityMatrix.Rule;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityMatrixHarnessTest {

    @Test
    void verdictsComeFromTheProblemTypes() {
        SecurityMatrix matrix = new SecurityMatrix("t", List.of(SecurityMatrix.ANONYMOUS, "admin", "student"));
        matrix.record("GET /x", Rule.only("admin"), SecurityMatrix.ANONYMOUS, 401, "urn:mitpass:problem:unauthorized");
        matrix.record("GET /x", Rule.only("admin"), "admin", 404, "urn:mitpass:problem:not-found");
        matrix.record("GET /x", Rule.only("admin"), "student", 403, "urn:mitpass:problem:forbidden");

        assertThat(matrix.failures()).isEmpty();
        assertThat(matrix.size()).isEqualTo(3);
    }

    @Test
    void aServerErrorNeverPassesEvenWhereTheCallIsAllowed() {
        SecurityMatrix matrix = new SecurityMatrix("t", List.of("admin"));
        matrix.record("GET /x", Rule.only("admin"), "admin", 500, "urn:mitpass:problem:internal");

        assertThat(matrix.failures()).singleElement().asString().contains("HTTP 500, a server error");
    }
}
