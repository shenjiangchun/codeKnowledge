package com.hisi.capture.crypto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 混合加密（JWE 风格）：
 *   1. 随机生成 32B DEK（AES-256 密钥）
 *   2. AES-256-GCM(DEK, IV, plaintext) → 密文 + tag
 *   3. RSA-OAEP-2048(公钥, DEK) → 加密后的 DEK
 *   4. 输出：base64(rsa_wrapped_dek[256B] || iv[12B] || ciphertext || gcm_tag[16B])
 */
@Component
public class HybridEncryptor {

    private static final int DEK_LEN = 32;       // AES-256
    private static final int IV_LEN = 12;        // GCM 推荐 12B
    private static final int TAG_LEN_BITS = 128; // GCM tag 16B
    private static final int RSA_WRAPPED_LEN = 256; // RSA-2048 密文 256B

    @Autowired
    private StaticKeyPairLoader keyLoader;

    private final SecureRandom random = new SecureRandom();

    public String encrypt(String plaintext) {
        try {
            // 1. 生成 DEK
            byte[] dek = new byte[DEK_LEN];
            random.nextBytes(dek);

            // 2. AES-GCM 加密
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"),
                           new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] ct = aesCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 3. RSA-OAEP 加密 DEK
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsaCipher.init(Cipher.ENCRYPT_MODE, keyLoader.loadPublicKey());
            byte[] wrappedDek = rsaCipher.doFinal(dek);
            if (wrappedDek.length != RSA_WRAPPED_LEN) {
                throw new IllegalStateException("RSA wrapped DEK length mismatch");
            }

            // 4. 拼接 + base64
            byte[] out = new byte[RSA_WRAPPED_LEN + IV_LEN + ct.length];
            System.arraycopy(wrappedDek, 0, out, 0, RSA_WRAPPED_LEN);
            System.arraycopy(iv, 0, out, RSA_WRAPPED_LEN, IV_LEN);
            System.arraycopy(ct, 0, out, RSA_WRAPPED_LEN + IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encrypt failed", e);
        }
    }
}
