package com.mitpass.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.auth.config.AuthProperties;
import com.mitpass.auth.config.AuthProperties.DomainRule;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailPolicyTest {

    static final EmailPolicy POLICY = new EmailPolicy(properties());

    static AuthProperties properties() {
        return new AuthProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new AuthProperties.Signup(List.of(
                        new DomainRule("student.annauniv.edu", "\\d{10}(\\d{2})?", true, false),
                        new DomainRule("gmail.com", null, false, true))));
    }

    @ParameterizedTest
    @CsvSource({
        "2026509032@student.annauniv.edu,   2026509032",
        "202650903201@student.annauniv.edu, 202650903201",
        "  2026509032@Student.AnnaUniv.edu ,2026509032"
    })
    void collegeAddressesAreAllowedAndCarryTheRegisterNumber(String email, String registerNumber) {
        EmailPolicy.Evaluation result = POLICY.evaluate(email);

        assertThat(result.allowed()).isTrue();
        assertThat(result.registerNumber()).isEqualTo(registerNumber);
        assertThat(result.email()).isEqualTo(result.email().toLowerCase()).doesNotContain(" ");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "202650903@student.annauniv.edu", // 9 digits
                "20265090321@student.annauniv.edu", // 11 digits
                "priya.raman@student.annauniv.edu", // not a register number
                "2026509032a@student.annauniv.edu"
            })
    void collegeAddressMustBeARegisterNumber(String email) {
        EmailPolicy.Evaluation result = POLICY.evaluate(email);

        assertThat(result.allowed()).isFalse();
        assertThat(result.error()).contains("register number");
    }

    @ParameterizedTest
    @ValueSource(strings = {"priya@yahoo.com", "priya@annauniv.edu", "priya@gmail.com.evil.io", "x@outlook.com"})
    void otherDomainsAreRejected(String email) {
        EmailPolicy.Evaluation result = POLICY.evaluate(email);

        assertThat(result.allowed()).isFalse();
        assertThat(result.error()).contains("@student.annauniv.edu or @gmail.com");
    }

    @ParameterizedTest
    @CsvSource({
        "Priya.Raman@gmail.com,     priyaraman@gmail.com",
        "priya.raman+fest@gmail.com, priyaraman@gmail.com",
        "priyaraman@gmail.com,      priyaraman@gmail.com"
    })
    void gmailDotsAndTagsAreIgnoredForUniqueness(String email, String canonical) {
        EmailPolicy.Evaluation result = POLICY.evaluate(email);

        assertThat(result.allowed()).isTrue();
        assertThat(result.canonicalEmail()).isEqualTo(canonical);
        assertThat(result.registerNumber()).isNull();
        assertThat(POLICY.canonicalize(email)).isEqualTo(canonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "@gmail.com", "priya@"})
    void malformedAddressesAreLeftToTheEmailConstraint(String email) {
        assertThat(POLICY.evaluate(email).allowed()).isTrue();
    }
}
