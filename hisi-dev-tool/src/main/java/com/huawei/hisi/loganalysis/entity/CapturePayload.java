package com.huawei.hisi.loganalysis.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Decoded HISI_CAPTURE payload from business-side SDK.
 * Meta fields are plaintext; encrypted fields are decrypted by CaptureDecoder.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapturePayload {

    private String alg;              // encryption algorithm identifier
    private String entryTag;         // meta.tag (e.g. "HTTP:/api/order/create")
    private String uri;              // meta.uri
    private String method;           // meta.method (HTTP method or annotation type)
    private long timestamp;          // meta.ts (epoch millis)
    private String exceptionType;    // exception class name if present
    private String exceptionMessage; // exception message if present

    /** Decrypted entry parameters (method args). */
    private Map<String, Object> entryParams;
    /** Decrypted spans — each span has sig, args, result, durationMs, etc. */
    private List<Map<String, Object>> spans;
    /** Decrypted feign call records. */
    private List<Map<String, Object>> feignCalls;
}
