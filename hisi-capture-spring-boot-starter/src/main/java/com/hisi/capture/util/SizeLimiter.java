package com.hisi.capture.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class SizeLimiter {

    @Value("${hisi.capture.max-arg-size:1024}")     // 1KB
    private int maxArgSize;

    @Value("${hisi.capture.max-body-size:4096}")    // 4KB
    private int maxBodySize;

    public Object[] limitArgs(Object[] args) {
        if (args == null) return null;
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = truncate(args[i], maxArgSize);
        }
        return out;
    }

    public Object limitRetVal(Object ret) {
        return truncate(ret, maxArgSize);
    }

    public String limitBody(HttpServletRequest req) {
        // 读取 body（缓存以便后续 Controller 用），截断到 maxBodySize
        try {
            InputStream is = req.getInputStream();
            byte[] buf = new byte[maxBodySize];
            int totalRead = 0;
            int bytesRead;
            while (totalRead < maxBodySize &&
                   (bytesRead = is.read(buf, totalRead,
                       Math.min(buf.length - totalRead, maxBodySize - totalRead))) != -1) {
                totalRead += bytesRead;
            }
            int len = Math.min(totalRead, maxBodySize);
            return new String(buf, 0, len, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[body-read-failed]";
        }
    }

    private Object truncate(Object o, int maxBytes) {
        if (o == null) return null;
        String s = String.valueOf(o);
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return o;
        return new String(b, 0, maxBytes, StandardCharsets.UTF_8) + "...[truncated]";
    }
}
