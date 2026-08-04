package com.hisi.capture.context;

import java.util.Map;

public class EntryContext {
    /** 入口标签：UUID，跨进程关联 */
    private String entryTag;
    /** 入口类型 */
    private EntryType entryType;
    /** 入口 URI（HTTP 是 path，@Scheduled 是 task name，Feign 是 url） */
    private String entryUri;
    /** 入参（脱敏 + 限大小 + 加密） */
    private Map<String, Object> params;
    /** 入口开始时间 */
    private long startMillis;

    public EntryContext(String entryTag, EntryType entryType, String entryUri,
                        Map<String, Object> params, long startMillis) {
        this.entryTag = entryTag;
        this.entryType = entryType;
        this.entryUri = entryUri;
        this.params = params;
        this.startMillis = startMillis;
    }

    public String getEntryTag() { return entryTag; }
    public void setEntryTag(String entryTag) { this.entryTag = entryTag; }
    public EntryType getEntryType() { return entryType; }
    public void setEntryType(EntryType entryType) { this.entryType = entryType; }
    public String getEntryUri() { return entryUri; }
    public void setEntryUri(String entryUri) { this.entryUri = entryUri; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public long getStartMillis() { return startMillis; }
    public void setStartMillis(long startMillis) { this.startMillis = startMillis; }
}
