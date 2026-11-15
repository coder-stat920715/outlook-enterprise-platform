package com.enterprise.outlook.mail.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Stateless delta token: base64url("seq.mac"). The MAC binds the token to (tenant, mailbox) so tokens cannot be replayed across mailboxes. */
public final class DeltaToken {
    private DeltaToken() {}

    private static String mac(UUID t, UUID m, long seq, String secret) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((t + ":" + m + ":" + seq + ":" + secret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String encode(UUID t, UUID m, long seq, String secret) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((seq + "." + mac(t, m, seq, secret)).getBytes(StandardCharsets.UTF_8));
    }

    public static long decode(String token, UUID t, UUID m, String secret) {
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\.");
            long seq = Long.parseLong(p[0]);
            if (!mac(t, m, seq, secret).equals(p[1])) throw new IllegalArgumentException();
            return seq;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid deltaToken");
        }
    }
}
