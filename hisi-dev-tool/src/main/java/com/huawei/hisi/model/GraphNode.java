package com.huawei.hisi.model;

import java.awt.*;
import java.util.List;

public class GraphNode {
    String parentMethod;
    String methodBody;
    int depth;
    private Point position;
    private int size;
    private List<String> textLines;
    private int maxLineWidth;
    private FontMetrics fontMetrics;

    public GraphNode(String parentMethod, String methodBody, int depth) {
        this.parentMethod = parentMethod;
        this.methodBody = methodBody;
        this.depth = depth;
    }

    public String getDisplayText() {
        if (methodBody == null || methodBody.trim().isEmpty()) {
            return parentMethod;
        }
        return parentMethod + ":" + methodBody;
    }

    public Point getPosition() { return position; }
    public void setPosition(Point position) { this.position = position; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public List<String> getTextLines() { return textLines; }
    public void setTextLines(List<String> textLines) { this.textLines = textLines; }

    public void setMaxLineWidth(int maxLineWidth) { this.maxLineWidth = maxLineWidth; }
    public void setFontMetrics(FontMetrics fontMetrics) { this.fontMetrics = fontMetrics; }
}