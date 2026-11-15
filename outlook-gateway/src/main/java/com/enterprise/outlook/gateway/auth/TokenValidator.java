package com.enterprise.outlook.gateway.auth;

import com.enterprise.outlook.common.resilience.StaleWhileRevalidateCache;
import com.enterprise.outlook.common.util.Json;
import com.enterprise.outlook.gateway.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * Validates bearer tokens without ever blocking the handshake path on the Identity Provider.
 * 1. Local structural + expiry check (rejects garbage / expired tokens without touching any upstream).
 * 2. mode=hmac       : HS256 signature verified locally.
 *    mode=introspect : IdP introspection through a stale-while-revalidate cache (L1 Caffeine + L2 Redis);
 *                      when the IdP is slow/down, recently validated unexpired tokens keep working (stale-if-error).
 */
@Component
public class TokenValidator {
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String m) { super(m); }
    }

    private final GatewayProperties.Auth cfg;
    private final StaleWhileRevalidateCache<Claims> cache;
    private final WebClient idp = WebClient.create();

    public TokenValidator(GatewayProperties props, ReactiveStringRedisTemplate redis) {
        this.cfg = props.auth();
        var l2 = new StaleWhileRevalidateCache.L2Store() {
            @Override public CompletableFuture<String> get(String key) {
                return redis.opsForValue().get("auth:" + key).onErrorResume(e -> Mono.empty()).toFuture();
            }
            @Override public CompletableFuture<Void> put(String key, String value, Duration ttl) {
                return redis.opsForValue().set("auth:" + key, value, ttl).onErrorReturn(false).then().toFuture();
            }
        };
        this.cache = new StaleWhileRevalidateCache<>("token-introspection",
                new StaleWhileRevalidateCache.Policy(Duration.ofSeconds(cfg.freshSeconds()), Duration.ofSeconds(cfg.swrSeconds()),
                        Duration.ofSeconds(cfg.staleIfErrorSeconds()), 200_000, Duration.ofMillis(cfg.upstreamTimeoutMillis())),
                l2, this::introspect, Json::write, s -> Json.read(s, Claims.class), Claims::hardExpiryMillis);
    }

    public Mono<Claims> validate(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return Mono.error(new UnauthorizedException("malformed token"));
            JsonNode payload = Json.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (payload.path("exp").asLong(0) * 1000L <= System.currentTimeMillis()) {
                return Mono.error(new UnauthorizedException("token expired"));
            }
            if ("hmac".equals(cfg.mode())) return Mono.just(verifyHmac(parts, payload));
        } catch (UnauthorizedException e) {
            return Mono.error(e);
        } catch (Exception e) {
            return Mono.error(new UnauthorizedException("malformed token"));
        }
        // Key = hash of token (the raw token is only passed to the loader, never stored as a key).
        return Mono.fromFuture(() -> cache.get(sha256(token) + "|" + token))
                .flatMap(c -> c.active() ? Mono.just(c) : Mono.<Claims>error(new UnauthorizedException("inactive token")));
    }

    private Claims verifyHmac(String[] parts, JsonNode p) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(cfg.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) throw new UnauthorizedException("bad signature");
        return fromJson(p, true);
    }

    private CompletableFuture<Claims> introspect(String keyWithToken) {
        String token = keyWithToken.substring(keyWithToken.indexOf('|') + 1);
        return idp.post().uri(cfg.introspectionUrl()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("token", token)).retrieve().bodyToMono(String.class)
                .map(body -> {
                    try {
                        JsonNode n = Json.MAPPER.readTree(body);
                        return fromJson(n, n.path("active").asBoolean(false));
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }).toFuture();
    }

    private static Claims fromJson(JsonNode n, boolean active) {
        return new Claims(active, n.path("sub").asText(""), n.path("tid").asText(n.path("tenant_id").asText("")),
                n.path("mid").asText(n.path("mailbox_id").asText("")), n.path("region").asText("AMER"), n.path("exp").asLong(0));
    }

    private static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public long staleServed() { return cache.staleServed(); }
}
