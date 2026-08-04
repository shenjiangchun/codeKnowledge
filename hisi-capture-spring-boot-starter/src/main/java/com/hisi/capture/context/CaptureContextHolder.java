package com.hisi.capture.context;

import com.alibaba.ttl.TransmittableThreadLocal;

public final class CaptureContextHolder {
    private static final TransmittableThreadLocal<CaptureContext> CTX = new TransmittableThreadLocal<>();

    public static CaptureContext get() { return CTX.get(); }
    public static void set(CaptureContext ctx) { CTX.set(ctx); }
    public static void clear() { CTX.remove(); }

    private CaptureContextHolder() {}
}
