package com.hisi.capture.context;

import java.util.Map;

public class FeignCall {
    private String url;
    private Map<String, Object> params;  // 加密
    private int status;
    private long duration;

    public FeignCall(String url, Map<String, Object> params, int status, long duration) {
        this.url = url;
        this.params = params;
        this.status = status;
        this.duration = duration;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }
}
