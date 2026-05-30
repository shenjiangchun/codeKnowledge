package com.huawei.hisi.project.remote.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GitCredentialServiceTest {

    private GitCredentialService service;

    @BeforeEach
    void setUp() {
        service = new GitCredentialService("test-key-1234567890123456");
    }

    @Test
    @DisplayName("encrypt then decrypt returns original password")
    void encryptDecrypt_roundTrip() {
        String original = "my-secret-password";
        String encrypted = service.encrypt(original);
        assertThat(encrypted).isNotEqualTo(original);
        String decrypted = service.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    @DisplayName("encrypt produces different ciphertext each time (random IV)")
    void encrypt_differentIvEachTime() {
        String encrypted1 = service.encrypt("same-password");
        String encrypted2 = service.encrypt("same-password");
        assertThat(encrypted1).isNotEqualTo(encrypted2);
    }

    @Test
    @DisplayName("null or empty input returns as-is")
    void encryptDecrypt_nullOrEmpty() {
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.encrypt("")).isEmpty();
        assertThat(service.decrypt(null)).isNull();
        assertThat(service.decrypt("")).isEmpty();
    }
}
