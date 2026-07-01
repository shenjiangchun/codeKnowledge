package com.hisi.capture.context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class CaptureContext {
    private EntryContext entry;
    /** span 栈，最大 50（栈深保护） */
    private final Deque<Span> spanStack = new ArrayDeque<>();
    private final List<FeignCall> feignCalls = new ArrayList<>();

    public EntryContext getEntry() { return entry; }
    public void setEntry(EntryContext entry) { this.entry = entry; }
    public Deque<Span> getSpanStack() { return spanStack; }
    public List<FeignCall> getFeignCalls() { return feignCalls; }

    public void pushSpan(Span span) { spanStack.push(span); }
    public Span popSpan() { return spanStack.poll(); }
    public void addFeignCall(FeignCall call) { feignCalls.add(call); }
}
