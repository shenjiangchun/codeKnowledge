package com.hisi.capture.crypto;

import com.hisi.capture.config.CaptureCryptoProperties;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * 由 CaptureCryptoAutoConfiguration 创建（非 @Component）。
 */
public class StaticKeyPairLoader {

    private final CaptureCryptoProperties cryptoProperties;

    public StaticKeyPairLoader(CaptureCryptoProperties cryptoProperties) {
        this.cryptoProperties = cryptoProperties;
    }

    /**
     * 加载内置公钥（classpath 路径由 hisi.capture.crypto.public-key-path 配置）。
     * 私钥不发布到业务方，仅在 codeknowledge 内部。
     */
    public PublicKey loadPublicKey() {
        InputStream is = null;
        try {
            String keyPath = cryptoProperties.getPublicKeyPath();
            is = new ClassPathResource(keyPath).getInputStream();
            byte[] allBytes = readAllBytes(is);
            String pem = new String(allBytes, java.nio.charset.StandardCharsets.UTF_8)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
            byte[] der = java.util.Base64.getDecoder().decode(pem);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(spec);

            // 校验密钥长度必须 >= 2048 位
            if (pub instanceof RSAPublicKey) {
                int keySize = ((RSAPublicKey) pub).getModulus().bitLength();
                if (keySize < 2048) {
                    throw new IllegalStateException(
                        "Capture public key too short: " + keySize + " bits (minimum 2048 required)");
                }
            }

            return pub;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load capture public key", e);
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }

    private byte[] readAllBytes(InputStream is) throws java.io.IOException {
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
