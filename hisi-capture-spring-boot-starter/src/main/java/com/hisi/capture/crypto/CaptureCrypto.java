package com.hisi.capture.crypto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CaptureCrypto {

    @Autowired
    private HybridEncryptor encryptor;

    public String encrypt(String plaintext) {
        return encryptor.encrypt(plaintext);
    }
}
