package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.mail.config.MailProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Signed CDN URLs compatible with nginx ngx_http_secure_link:
 *   secure_link $arg_md5,$arg_expires;  secure_link_md5 "$secure_link_expires$uri <secret>";
 * token = base64url(md5(expires + uri + " " + secret)) without padding.
 */
@Component
public class CdnUrlSigner {
    private final MailProperties props;

    public CdnUrlSigner(MailProperties props) { this.props = props; }

    public record Signed(String url, Instant expiresAt) {}

    public Signed sign(String objectKey, Duration ttl) {
        long expires = Instant.now().plus(ttl).getEpochSecond();
        String uri = "/cdn/attachments/" + objectKey;
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest((expires + uri + " " + props.cdnSecret()).getBytes(StandardCharsets.UTF_8));
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(md5);
            return new Signed(props.cdnBaseUrl() + uri + "?md5=" + token + "&expires=" + expires, Instant.ofEpochSecond(expires));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
