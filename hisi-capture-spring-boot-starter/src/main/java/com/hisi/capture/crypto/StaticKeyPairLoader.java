package com.hisi.capture.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;

@Component
public class StaticKeyPairLoader {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * 加载内置公钥（META-INF/capture-public-key.pem）。
     * 私钥不发布到业务方，仅在 codeknowledge 内部。
     */
    public PublicKey loadPublicKey() {
        InputStream is = null;
        try {
            is = new ClassPathResource("META-INF/capture-public-key.pem").getInputStream();
            byte[] allBytes = readAllBytes(is);
            String pem = new String(allBytes, java.nio.charset.StandardCharsets.UTF_8)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
            byte[] der = java.util.Base64.getDecoder().decode(pem);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
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
