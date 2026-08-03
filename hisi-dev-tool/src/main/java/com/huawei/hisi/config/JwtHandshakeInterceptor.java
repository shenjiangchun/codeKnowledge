package com.huawei.hisi.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * WebSocket handshake interceptor that validates a JWT token from the
 * {@code token} query parameter before allowing the WebSocket upgrade.
 *
 * <p>Used by {@link WebSocketConfig} for endpoints that require authentication.
 * On success, stores the {@link SecurityContext} in the WebSocket session
 * attributes so the handler can access it via
 * {@code session.getAttributes().get(SecurityContext.ATTR_NAME)}.
 */
@Slf4j
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final String TOKEN_PARAM = "token";

    private final JwtTokenProvider tokenProvider;

    public JwtHandshakeInterceptor(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        URI uri = request.getURI();
        if (uri == null) {
            log.warn("[JwtWS] handshake rejected: no URI");
            return false;
        }

        String token = UriComponentsBuilder.fromUri(uri).build()
                .getQueryParams().getFirst(TOKEN_PARAM);
        if (token == null || token.isBlank()) {
            log.warn("[JwtWS] handshake rejected: missing token param");
            return false;
        }

        try {
            Claims claims = tokenProvider.validateToken(token);
            String username = claims.getSubject();
            String role = claims.get("role", String.class);
            attributes.put(SecurityContext.ATTR_NAME, new SecurityContext(username, role));
            log.debug("[JwtWS] handshake ok: user={} role={}", username, role);
            return true;
        } catch (JwtException e) {
            log.warn("[JwtWS] handshake rejected: invalid token — {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
