package com.huawei.hisi.loganalysis.decoder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.loganalysis.entity.CapturePayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Security;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Decodes the HISI_CAPTURE JSON envelope produced by hisi-capture-spring-boot-starter.
 *
 * <p>Envelope format:
 * <pre>
 * { "alg": "hybrid-rsa-aes-gcm",
 *   "meta": { "tag": "...", "uri": "...", "method": "...", "ts": 123 },
 *   "enc":  { "entry": "&lt;base64&gt;", "spans": "&lt;base64&gt;", "feign": "&lt;base64&gt;" } }
 * </pre>
 *
 * <p>Encrypted field layout (binary before base64):
 * <pre>
 * rsa_wrapped_dek[256 B] || iv[12 B] || ciphertext || gcm_tag[16 B]
 * </pre>
 */
@Slf4j
@Component
public class CaptureDecoder {

    private static final int RSA_WRAPPED_LEN = 256;  // RSA-2048 ciphertext
    private static final int IV_LEN = 12;            // GCM recommended IV
    private static final int TAG_LEN_BITS = 128;     // GCM tag = 16 B

    static {
        try {
            Class<?> bcClass = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider");
            Security.addProvider((java.security.Provider) bcClass.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            // BouncyCastle not on classpath — RSA/ECB/OAEPWithSHA-256AndMGF1Padding
            // still works via default JDK provider (SunRsaSign).
            // No-op: log not available yet in static block.
        }
    }

    private final ObjectMapper objectMapper;
    private final PrivateKey privateKey;

    public CaptureDecoder(
            ObjectMapper objectMapper,
            @Value("${hisi.capture.crypto.private-key-path:}") String privateKeyPath,
            @Value("${hisi.capture.crypto.private-key-b64:}") String privateKeyB64) {
        this.objectMapper = objectMapper;
        this.privateKey = loadPrivateKey(privateKeyPath, privateKeyB64);
    }

    /**
     * Decode a HISI_CAPTURE JSON envelope string into a {@link CapturePayload}.
     *
     * @param captureJson the raw JSON between HISI_CAPTURE_BEGIN and HISI_CAPTURE_END
     * @return decoded payload, or null if parsing fails
     */
    public CapturePayload decode(String captureJson) {
        if (captureJson == null || captureJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> envelope = objectMapper.readValue(captureJson,
                    new TypeReference<Map<String, Object>>() {});

            String alg = (String) envelope.get("alg");

            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) envelope.get("meta");

            CapturePayload.CapturePayloadBuilder builder = CapturePayload.builder()
                    .alg(alg);

            if (meta != null) {
                builder.entryTag(str(meta.get("tag")))
                        .uri(str(meta.get("uri")))
                        .method(str(meta.get("method")))
                        .timestamp(toLong(meta.get("ts")));
            }

            // Decrypt encrypted fields if private key is available
            @SuppressWarnings("unchecked")
            Map<String, String> enc = (Map<String, String>) envelope.get("enc");
            if (enc != null && privateKey != null) {
                builder.entryParams(decryptJsonField(enc.get("entry")));
                builder.spans(decryptJsonList(enc.get("spans")));
                builder.feignCalls(decryptJsonList(enc.get("feign")));
            }

            return builder.build();
        } catch (Exception e) {
            log.warn("[CaptureDecoder] Failed to decode HISI_CAPTURE envelope: {}", e.getMessage());
            return null;
        }
    }

    // -- decryption helpers ------------------------------------------------

    private Map<String, Object> decryptJsonField(String base64Ciphertext) {
        if (base64Ciphertext == null || base64Ciphertext.isBlank()) {
            return null;
        }
        String plain = decrypt(base64Ciphertext);
        if (plain == null) {
            return null;
        }
        try {
            // The encrypted JSON wraps the actual data, e.g. {"entry": {...}} or {"params": {...}}
            Map<String, Object> wrapper = objectMapper.readValue(plain,
                    new TypeReference<Map<String, Object>>() {});
            // Unwrap single-key maps (entry, spans, feign wrappers from CaptureFormatter)
            if (wrapper.size() == 1) {
                Object inner = wrapper.values().iterator().next();
                if (inner instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) inner;
                    return result;
                }
            }
            return wrapper;
        } catch (IOException e) {
            log.warn("[CaptureDecoder] Failed to parse decrypted field as JSON: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> decryptJsonList(String base64Ciphertext) {
        if (base64Ciphertext == null || base64Ciphertext.isBlank()) {
            return null;
        }
        String plain = decrypt(base64Ciphertext);
        if (plain == null) {
            return null;
        }
        try {
            Map<String, Object> wrapper = objectMapper.readValue(plain,
                    new TypeReference<Map<String, Object>>() {});
            // CaptureFormatter wraps lists in single-key maps: {"spans": [...]} or {"feign": [...]}
            for (Object val : wrapper.values()) {
                if (val instanceof List) {
                    return (List<Map<String, Object>>) val;
                }
            }
            return null;
        } catch (IOException e) {
            log.warn("[CaptureDecoder] Failed to parse decrypted list field: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Decrypt a single base64-encoded hybrid ciphertext.
     * Layout: rsa_wrapped_dek[256] || iv[12] || ciphertext || gcm_tag[16]
     */
    private String decrypt(String base64) {
        try {
            byte[] blob = Base64.getDecoder().decode(base64);
            if (blob.length < RSA_WRAPPED_LEN + IV_LEN + TAG_LEN_BITS / 8) {
                log.warn("[CaptureDecoder] Ciphertext too short: {} bytes", blob.length);
                return null;
            }

            // 1. Unwrap DEK with RSA-OAEP
            byte[] wrappedDek = new byte[RSA_WRAPPED_LEN];
            System.arraycopy(blob, 0, wrappedDek, 0, RSA_WRAPPED_LEN);

            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] dek = rsaCipher.doFinal(wrappedDek);

            // 2. Decrypt with AES-GCM
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(blob, RSA_WRAPPED_LEN, iv, 0, IV_LEN);

            int ctLen = blob.length - RSA_WRAPPED_LEN - IV_LEN;
            byte[] ct = new byte[ctLen];
            System.arraycopy(blob, RSA_WRAPPED_LEN + IV_LEN, ct, 0, ctLen);

            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            aesCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] plainBytes = aesCipher.doFinal(ct);

            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[CaptureDecoder] Decryption failed: {}", e.getMessage());
            return null;
        }
    }

    // -- private key loading -----------------------------------------------

    private PrivateKey loadPrivateKey(String path, String b64) {
        String pem = null;
        if (path != null && !path.isBlank()) {
            try {
                pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
                log.info("[CaptureDecoder] Loaded private key from file: {}", path);
            } catch (IOException e) {
                log.warn("[CaptureDecoder] Cannot read private key file '{}': {}", path, e.getMessage());
            }
        }
        if (pem == null && b64 != null && !b64.isBlank()) {
            try {
                pem = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                log.info("[CaptureDecoder] Loaded private key from base64 config");
            } catch (IllegalArgumentException e) {
                log.warn("[CaptureDecoder] Invalid base64 private key: {}", e.getMessage());
            }
        }
        if (pem == null) {
            log.info("[CaptureDecoder] No private key configured — capture decryption disabled (meta-only mode)");
            return null;
        }
        try {
            String raw = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(raw);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            log.warn("[CaptureDecoder] Failed to parse private key: {}", e.getMessage());
            return null;
        }
    }

    // -- utility -----------------------------------------------------------

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }
}
