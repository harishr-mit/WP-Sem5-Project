package com.mitpass.auth.mail;

/** Published inside the sign-up / resend transaction; {@link VerificationMailer} sends the email after commit. */
public record VerificationRequested(String email, String fullName, String token) {

    @Override
    public String toString() {
        return "VerificationRequested[email=" + email + "]";
    }
}
