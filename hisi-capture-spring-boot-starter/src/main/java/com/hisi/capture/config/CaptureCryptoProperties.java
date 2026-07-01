package com.hisi.capture.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 加密配置属性：hisi.capture.crypto.*
 */
@ConfigurationProperties(prefix = "hisi.capture.crypto")
public class CaptureCryptoProperties {

    /** 加密算法标识 */
    private String algorithm = "hybrid-rsa-aes-gcm";

    /** 公钥 classpath 路径 */
    private String publicKeyPath = "META-INF/capture-public-key.pem";

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public String getPublicKeyPath() { return publicKeyPath; }
    public void setPublicKeyPath(String publicKeyPath) { this.publicKeyPath = publicKeyPath; }
}
