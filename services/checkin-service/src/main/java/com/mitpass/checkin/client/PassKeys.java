package com.mitpass.checkin.client;

import com.nimbusds.jose.jwk.ECKey;
import java.util.Optional;

/** pass-service's published ES256 keys ({@code /.well-known/pass-jwks.json}), by {@code kid} (D12). */
public interface PassKeys {

    Optional<ECKey> key(String kid);
}
