package tech.migueldev.coffeewarehouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for the tokens this API issues and accepts.
 *
 * A {@code record} bound with {@code @ConfigurationProperties}: the values are
 * read once at startup and cannot change afterwards, which is what you want from
 * a signing key.
 *
 * @param secret the HMAC signing key. Must be at least 32 bytes -- HS256 is
 *               defined over a 256-bit key, and a shorter one is rejected at
 *               startup rather than silently weakening every token
 * @param issuer the {@code iss} claim, so a token minted by some other service
 *               sharing the secret is still not accepted here
 * @param ttl    how long a token stays valid
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(String secret, String issuer, Duration ttl) {

    /** HS256 is defined over a 256-bit key: anything shorter is not the algorithm. */
    public static final int MINIMUM_SECRET_BYTES = 32;
}
