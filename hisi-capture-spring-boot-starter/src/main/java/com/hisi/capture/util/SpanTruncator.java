package com.hisi.capture.util;

import com.hisi.capture.context.Span;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SpanTruncator {

    /**
     * N+3 = 4：保留异常抛出点（栈顶）+ 3 层上游。
     * 输入是 Deque（栈顶在前），输出按从栈底到栈顶顺序。
     */
    public List<Span> bottomN(Deque<Span> stack, int n) {
        if (stack == null || stack.isEmpty()) return Collections.emptyList();
        List<Span> list = new ArrayList<>(stack);
        // list[0] 是栈顶（异常抛出点），保留前 n 个
        if (list.size() <= n) return list;
        return list.subList(0, n);
    }
}
