package com.hisi.capture.autoconfig;

import com.hisi.capture.crypto.CaptureCrypto;
import com.hisi.capture.crypto.HybridEncryptor;
import com.hisi.capture.crypto.StaticKeyPairLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CaptureCryptoAutoConfiguration {

    @Bean
    public StaticKeyPairLoader staticKeyPairLoader() {
        return new StaticKeyPairLoader();
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
