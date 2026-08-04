package com.hisi.capture.format;

import java.util.List;
import java.util.Map;

public class CapturePayload {
    private String alg;
    private Map<String, String> enc;  // entry / spans / feign → 密文 base64
    private Map<String, Object> meta; // tag / uri / method / ts

    public String getAlg() { return alg; }
    public void setAlg(String alg) { this.alg = alg; }
    public Map<String, String> getEnc() { return enc; }
    public void setEnc(Map<String, String> enc) { this.enc = enc; }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta; }
}
