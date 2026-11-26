package com.huawei.hisi.config;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 签名密钥不再有默认值：没配就每次启动随机生成，重启前的令牌随之失效。 */
@DisplayName("JwtTokenProvider secret handling")
class JwtTokenProviderTest {

    private static JwtTokenProvider provider(String secret) {
        JwtTokenProvider provider = new JwtTokenProvider(new JwtProperties(secret, 60_000));
        provider.init();
        return provider;
    }

    @Test
    @DisplayName("配置了密钥：两个实例用同一把密钥签发、互相认得对方的令牌")
    void aConfiguredSecretIsSharedAcrossRestarts() {
        String secret = "unit-test-signing-key-zqxjvkbwtrpm";
        JwtTokenProvider first = provider(secret);
        JwtTokenProvider second = provider(secret);

        assertThat(second.validateToken(first.generateToken("root", "ADMIN")).getSubject()).isEqualTo("root");
    }

    @Test
    @DisplayName("没配密钥：照常启动并签发令牌，但两次启动的密钥互不相认")
    void aMissingSecretYieldsAnEphemeralKey() {
        JwtTokenProvider first = provider("");
        JwtTokenProvider second = provider("   ");
        JwtTokenProvider third = provider(null);

        String token = first.generateToken("root", "ADMIN");
        assertThat(first.validateToken(token).getSubject()).isEqualTo("root");
        assertThatThrownBy(() -> second.validateToken(token)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> third.validateToken(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("配置了但短于 32 字节：仍然拒绝启动")
    void aShortConfiguredSecretIsRejected() {
        assertThatThrownBy(() -> provider("too-short")).isInstanceOf(IllegalStateException.class);
    }
}
