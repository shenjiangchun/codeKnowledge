package com.hisi.capture.format;

import com.hisi.capture.context.*;
import com.hisi.capture.crypto.CaptureCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CaptureFormatter {

    @Autowired
    private CaptureCrypto crypto;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String format(EntryContext entry, List<Span> spans,
                         List<FeignCall> feignCalls, Throwable ex) {
        // 1. 明文 JSON 构造
        Map<String, Object> entryJson = new HashMap<String, Object>();
        entryJson.put("tag", entry.getEntryTag());
        entryJson.put("type", entry.getEntryType().name());
        entryJson.put("uri", entry.getEntryUri());
        entryJson.put("params", entry.getParams());

        List<Map<String, Object>> spanJson = new ArrayList<Map<String, Object>>();
        for (Span s : spans) {
            Map<String, Object> sj = new HashMap<String, Object>();
            sj.put("sig", s.getMethodSignature());
            sj.put("args", s.getArgs());
            sj.put("ret", s.getRetVal());
            sj.put("dur", s.getEndMillis() - s.getStartMillis());
            if (s.getException() != null) {
                sj.put("exc", s.getException().toString());
            }
            spanJson.add(sj);
        }

        List<Map<String, Object>> feignJson = new ArrayList<Map<String, Object>>();
        for (FeignCall f : feignCalls) {
            Map<String, Object> fj = new HashMap<String, Object>();
            fj.put("url", f.getUrl());
            fj.put("params", f.getParams());
            fj.put("status", f.getStatus());
            fj.put("dur", f.getDuration());
            feignJson.add(fj);
        }

        Map<String, Object> entryPlain = new HashMap<String, Object>();
        entryPlain.put("entry", entryJson);
        entryPlain.put("spans", spanJson);
        entryPlain.put("feign", feignJson);

        Map<String, Object> spansPlain = new HashMap<String, Object>();
        spansPlain.put("spans", spanJson);

        Map<String, Object> feignPlain = new HashMap<String, Object>();
        feignPlain.put("feign", feignJson);

        // 2. 分别加密
        Map<String, String> enc = new HashMap<String, String>();
        enc.put("entry", crypto.encrypt(toJson(entryPlain)));
        enc.put("spans", crypto.encrypt(toJson(spansPlain)));
        if (!feignCalls.isEmpty()) {
            enc.put("feign", crypto.encrypt(toJson(feignPlain)));
        }

        // 3. meta 明文
        Map<String, Object> meta = new HashMap<String, Object>();
        meta.put("tag", entry.getEntryTag());
        meta.put("uri", entry.getEntryUri());
        meta.put("method", ex.getStackTrace().length > 0 ?
            ex.getStackTrace()[0].getClassName() + "." + ex.getStackTrace()[0].getMethodName() : "unknown");
        meta.put("ts", System.currentTimeMillis());

        // 4. 拼最终 payload
        CapturePayload payload = new CapturePayload();
        payload.setAlg("hybrid-rsa-aes-gcm");
        payload.setEnc(enc);
        payload.setMeta(meta);
        return "HISI_CAPTURE_BEGIN" + toJson(payload) + "HISI_CAPTURE_END";
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }
}
