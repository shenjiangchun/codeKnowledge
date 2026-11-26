package com.huawei.hisi.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final JwtProperties properties;
    private SecretKey key;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.key = Keys.hmacShaKeyFor(secretBytes(properties.secret()));
    }

    /**
     * 配置了密钥就用配置的；没配则每次启动随机生成一个。
     * 仓库是公开的，配置文件里不放默认密钥——随机密钥意味着重启后已签发的令牌全部失效（本机开发重新登录即可），
     * 需要跨重启保持登录的环境必须通过 {@code JWT_SECRET} 配置。
     */
    private static byte[] secretBytes(String configured) {
        if (configured != null && !configured.isBlank()) {
            byte[] keyBytes = configured.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < 32) {
                throw new IllegalStateException("JWT secret must be at least 32 bytes, current: " + keyBytes.length);
            }
            return keyBytes;
        }
        byte[] random = new byte[48];
        new SecureRandom().nextBytes(random);
        log.warn("[Jwt] 未配置 jwt.secret（环境变量 JWT_SECRET）：本次启动使用随机密钥，重启后已签发的令牌会全部失效");
        return Base64.getEncoder().encode(random);
    }

    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + properties.expirationMs());
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
