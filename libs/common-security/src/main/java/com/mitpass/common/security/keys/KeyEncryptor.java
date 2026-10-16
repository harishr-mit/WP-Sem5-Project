package com.mitpass.common.security.keys;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;

/**
 * Encrypts private signing keys at rest (auth-service's RS256 keys, pass-service's ES256 keys; docs/DECISIONS.md D7,
 * D12): AES-256-GCM with a key derived (PBKDF2-HMAC-SHA256) from a service secret and a random per-key salt. Stored as
 * {@code <salt>:<ciphertext>}, both hex. A plain class: each service creates the bean from its own property.
 */
public class KeyEncryptor {

    private static final HexFormat HEX = HexFormat.of();

    private final String secret;

    public KeyEncryptor(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("The key secret must not be empty");
        }
        this.secret = secret;
    }

    public String encrypt(String plaintext) {
        String salt = KeyGenerators.string().generateKey();
        byte[] cipher = encryptor(salt).encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
        return salt + ":" + HEX.formatHex(cipher);
    }

    public String decrypt(String stored) {
        int separator = stored.indexOf(':');
        if (separator < 0) {
            throw new IllegalStateException("Stored key is not in <salt>:<ciphertext> form");
        }
        byte[] cipher = HEX.parseHex(stored.substring(separator + 1));
        return new String(encryptor(stored.substring(0, separator)).decrypt(cipher), StandardCharsets.UTF_8);
    }

    private BytesEncryptor encryptor(String salt) {
        return AesGcmBytesEncryptor.withPassword(secret, salt).build();
    }
}
