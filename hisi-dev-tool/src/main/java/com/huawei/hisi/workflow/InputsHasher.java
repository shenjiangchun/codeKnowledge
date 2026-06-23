package com.huawei.hisi.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * SHA-256 hex digest of a canonical JSON encoding of an inputs map.
 *
 * <p>Two payloads that are semantically equal (same keys, same nested
 * values, regardless of map iteration order) hash to the same string —
 * used by {@link DagExecutor} for "minimum recompute" checkpoint lookup.
 */
public final class InputsHasher {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private InputsHasher() {
    }

    public static String hash(Map<String, Object> payload) {
        Map<String, Object> source = payload == null ? Map.of() : payload;
        try {
            byte[] canonical = CANONICAL.writeValueAsBytes(source);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical);
            return toHex(digest);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize payload for hashing", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
