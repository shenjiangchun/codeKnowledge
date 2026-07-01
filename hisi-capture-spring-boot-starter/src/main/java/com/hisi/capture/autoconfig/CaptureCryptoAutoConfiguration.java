package com.hisi.capture.autoconfig;

import com.hisi.capture.config.CaptureCryptoProperties;
import com.hisi.capture.crypto.CaptureCrypto;
import com.hisi.capture.crypto.HybridEncryptor;
import com.hisi.capture.crypto.StaticKeyPairLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CaptureCryptoAutoConfiguration {

    @Bean
    public StaticKeyPairLoader staticKeyPairLoader(CaptureCryptoProperties cryptoProperties) {
        return new StaticKeyPairLoader(cryptoProperties);
    }

    @Bean
    public HybridEncryptor hybridEncryptor() {
        return new HybridEncryptor();
    }

    @Bean
    public CaptureCrypto captureCrypto() {
        return new CaptureCrypto();
    }
}
