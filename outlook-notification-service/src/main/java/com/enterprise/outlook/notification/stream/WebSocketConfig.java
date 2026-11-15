package com.enterprise.outlook.notification.stream;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.UUID;

/** WebSocket alternative to SSE: ws://host/ws/v1/notifications?mailboxId=... (identity headers injected by the gateway). */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ConnectionRegistry registry;
    private final ReconnectAdmission admission;

    public WebSocketConfig(ConnectionRegistry registry, ReconnectAdmission admission) { this.registry = registry; this.admission = admission; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry r) {
        r.addHandler(new Handler(), "/ws/v1/notifications").setAllowedOrigins("*");
    }

    private final class Handler extends TextWebSocketHandler {
        private static final String KEY = "outlook.key";
        private static final String CONN = "outlook.conn";

        @Override
        public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
            var decision = admission.admit();
            String tenant = raw.getHandshakeHeaders().getFirst("X-Tenant-ID");
            String mailbox = raw.getUri() == null || raw.getUri().getQuery() == null ? null
                    : java.util.Arrays.stream(raw.getUri().getQuery().split("&")).filter(s -> s.startsWith("mailboxId=")).map(s -> s.substring(10)).findFirst().orElse(null);
            if (!decision.admitted() || tenant == null || mailbox == null) {
                raw.close(new CloseStatus(1013, "try again later; retry-after=" + decision.retryAfterSeconds()));   // 1013 = Try Again Later
                return;
            }
            var session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 256 * 1024);
            String key = ConnectionRegistry.key(UUID.fromString(tenant), UUID.fromString(mailbox));
            ConnectionRegistry.Connection conn = new ConnectionRegistry.Connection() {
                @Override public boolean send(String eventId, String json) {
                    try { session.sendMessage(new TextMessage(json)); return true; } catch (Exception e) { return false; }
                }
                @Override public void close() { try { session.close(); } catch (Exception ignored) { } }
            };
            raw.getAttributes().put(KEY, key);
            raw.getAttributes().put(CONN, conn);
            registry.register(key, conn);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            Object k = s.getAttributes().get(KEY), c = s.getAttributes().get(CONN);
            if (k != null && c != null) registry.unregister((String) k, (ConnectionRegistry.Connection) c);
        }
    }
}
