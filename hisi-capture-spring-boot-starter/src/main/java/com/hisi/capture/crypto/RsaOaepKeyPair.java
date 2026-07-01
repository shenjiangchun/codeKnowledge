package com.hisi.capture.crypto;

import java.security.KeyPair;

/**
 * RSA 密钥对持有者。
 * 私钥不发布到业务方，仅在 codeknowledge 内部解密服务中使用。
 */
public class RsaOaepKeyPair {

    private final KeyPair keyPair;

    public RsaOaepKeyPair(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public KeyPair getKeyPair() { return keyPair; }

    public java.security.PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public java.security.PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }
}
