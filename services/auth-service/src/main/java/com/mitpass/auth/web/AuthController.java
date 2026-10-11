package com.mitpass.auth.web;

import com.mitpass.auth.domain.User;
import com.mitpass.auth.keys.SigningKeyService;
import com.mitpass.auth.service.ClientInfo;
import com.mitpass.auth.service.EmailVerificationService;
import com.mitpass.auth.service.LoginService;
import com.mitpass.auth.service.RefreshTokenService;
import com.mitpass.auth.service.SignupService;
import com.mitpass.auth.service.TokenPair;
import com.mitpass.auth.web.dto.AuthRequests.LoginRequest;
import com.mitpass.auth.web.dto.AuthRequests.RefreshRequest;
import com.mitpass.auth.web.dto.AuthRequests.ResendVerificationRequest;
import com.mitpass.auth.web.dto.AuthRequests.VerifyEmailRequest;
import com.mitpass.auth.web.dto.AuthResponses.KeyRotationResponse;
import com.mitpass.auth.web.dto.AuthResponses.TokenResponse;
import com.mitpass.auth.web.dto.AuthResponses.UserResponse;
import com.mitpass.auth.web.dto.AuthResponses.VerificationResponse;
import com.mitpass.auth.web.dto.SignupRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Sign-up, email verification, login, refresh and logout")
public class AuthController {

    private final SignupService signup;
    private final EmailVerificationService verification;
    private final LoginService login;
    private final RefreshTokenService sessions;
    private final SigningKeyService signingKeys;
    private final Clock clock;

    public AuthController(
            SignupService signup,
            EmailVerificationService verification,
            LoginService login,
            RefreshTokenService sessions,
            SigningKeyService signingKeys,
            Clock clock) {
        this.signup = signup;
        this.verification = verification;
        this.login = login;
        this.sessions = sessions;
        this.signingKeys = signingKeys;
        this.clock = clock;
    }

    @PostMapping("/signup")
    @SecurityRequirements
    @Operation(
            summary = "Create a student account",
            description = "Allowed: `<register number>@student.annauniv.edu` or a Gmail address. A verification "
                    + "link is emailed (Mailpit in dev); login works after verifying.")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request) {
        User user = signup.signup(request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/users/{id}")
                .buildAndExpand(user.getId())
                .toUri();
        return ResponseEntity.created(location).body(UserResponse.from(user, clock.instant()));
    }

    @GetMapping("/verify-email")
    @SecurityRequirements
    @Operation(summary = "Verify an email address (the link in the email)")
    public VerificationResponse verifyByLink(@RequestParam String token) {
        verification.verify(token);
        return new VerificationResponse("VERIFIED");
    }

    @PostMapping("/verify-email")
    @SecurityRequirements
    @Operation(summary = "Verify an email address with the token from the link")
    public VerificationResponse verify(@Valid @RequestBody VerifyEmailRequest request) {
        verification.verify(request.token());
        return new VerificationResponse("VERIFIED");
    }

    @PostMapping("/verify-email/resend")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Send a new verification link",
            description = "Always 202, whether or not the account exists. At most one email per minute.")
    public void resend(@Valid @RequestBody ResendVerificationRequest request) {
        verification.resend(request.email());
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Log in: access token (15 min) + refresh token",
            description = "401 wrong email/password, 403 email not verified, 423 locked after 5 failures.")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return tokens(login.login(request.email(), request.password(), client(http)));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(
            summary = "Swap a refresh token for a new pair (rotation)",
            description = "Each refresh token works once. Presenting a used one ends the whole login "
                    + "(401 refresh-token-reused).")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return tokens(sessions.refresh(request.refreshToken(), client(http)));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "End this login",
            description = "Revokes the refresh token's family. An access token already issued stays valid until "
                    + "it expires (at most 15 minutes): MITPass access tokens are stateless.")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        sessions.logout(request.refreshToken());
    }

    @PostMapping("/keys/rotate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Rotate the token signing key (ADMIN)",
            description = "New tokens get the new `kid`; the old key stays in the JWKS until its tokens expire.")
    public ResponseEntity<KeyRotationResponse> rotateKey() {
        return ResponseEntity.status(HttpStatus.CREATED).body(new KeyRotationResponse(signingKeys.rotate()));
    }

    private ResponseEntity<TokenResponse> tokens(TokenPair pair) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(TokenResponse.from(pair, clock.instant()));
    }

    private static ClientInfo client(HttpServletRequest http) {
        return new ClientInfo(http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
    }
}
