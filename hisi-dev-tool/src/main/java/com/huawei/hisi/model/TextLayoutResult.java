package com.huawei.hisi.model;

import java.util.List;

class TextLayoutResult {
    List<String> lines;
    int maxLineWidth;

    public TextLayoutResult(List<String> lines, int maxLineWidth) {
        this.lines = lines;
        this.maxLineWidth = maxLineWidth;
    }
}