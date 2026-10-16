package com.mitpass.common.security.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyEncryptorTest {

    /** Written by auth-service's own KeyEncryptor (P2) before it moved here, with the dev secret. */
    private static final String STORED_BY_AUTH_SERVICE =
            "2b27982e27a10667:db561cd51704e346ddc4f314494928769ae56f6cbb0e4307"
                    + "f93e2113616e7f35db87cd33ece90964af791d7ecd483b437e0eaa7a54df134e";

    @Test
    void roundTripUsesAFreshSaltEachTime() {
        KeyEncryptor encryptor = new KeyEncryptor("s3cret");

        String first = encryptor.encrypt("{\"kty\":\"EC\"}");
        String second = encryptor.encrypt("{\"kty\":\"EC\"}");

        assertThat(first).isNotEqualTo(second).contains(":").doesNotContain("kty");
        assertThat(encryptor.decrypt(first)).isEqualTo("{\"kty\":\"EC\"}");
        assertThat(encryptor.decrypt(second)).isEqualTo("{\"kty\":\"EC\"}");
    }

    @Test
    void decryptsKeysStoredBeforeTheMove() {
        assertThat(new KeyEncryptor("dev-only-change-me").decrypt(STORED_BY_AUTH_SERVICE))
                .isEqualTo("{\"kty\":\"EC\",\"demo\":true}");
    }

    @Test
    void wrongSecretFails() {
        assertThatThrownBy(() -> new KeyEncryptor("other").decrypt(STORED_BY_AUTH_SERVICE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedValueAndEmptySecretAreRejected() {
        assertThatThrownBy(() -> new KeyEncryptor("s").decrypt("no-separator"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeyEncryptor(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
