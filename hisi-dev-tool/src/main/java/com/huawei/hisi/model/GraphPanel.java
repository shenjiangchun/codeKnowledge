package com.huawei.hisi.model;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class GraphPanel extends JPanel {
    private List<GraphNode> nodes;
    private List<GraphEdge> edges;
    private double scale = 1.0;
    private static final int MIN_NODE_SIZE = 80; // 最小节点大小
    private static final int MIN_HORIZONTAL_SPACING = 50;
    private static final int MIN_VERTICAL_SPACING = 80;
    private static final int MAX_CHARS_PER_LINE = 20; // 每行最多20个字符
    private static final String CHINESE_BASE_CHAR = "中";
    private static final int TEXT_PADDING = 12; // 减少内边距
    private Font originalNodeFont = new Font("宋体", Font.PLAIN, 12);
    private Font scaledNodeFont = originalNodeFont;

    public GraphPanel() {
        nodes = new ArrayList<>();
        edges = new ArrayList<>();
        setBackground(Color.WHITE);

        // 添加鼠标监听器支持缩放
        addMouseWheelListener(e -> {
            int notches = e.getWheelRotation();
            if (notches < 0) {
                scale *= 1.1; // 放大
            } else {
                scale /= 1.1; // 缩小
            }
            scale = Math.max(0.5, Math.min(scale, 3.0)); // 限制缩放范围

            // 更新缩放后的字体
            updateScaledFont();

            // 重新计算所有节点的文本布局
            recalculateAllNodeLayouts();

            revalidate();
            repaint();
        });
    }

    private void updateScaledFont() {
        // 根据缩放比例调整字体大小
        int scaledSize = (int)(originalNodeFont.getSize() * scale);
        scaledSize = Math.max(8, Math.min(scaledSize, 24)); // 限制字体大小范围
        scaledNodeFont = new Font(originalNodeFont.getName(), originalNodeFont.getStyle(), scaledSize);
    }

    private void recalculateAllNodeLayouts() {
        if (nodes == null) return;

        for (GraphNode node : nodes) {
            // 重新计算文本布局，考虑当前缩放比例
            calculateNodeSizeAndLayout(node, true);
        }
    }

    public void setGraphData(List<GraphNode> nodes, List<GraphEdge> edges) {
        this.nodes = nodes;
        this.edges = edges;
        this.scale = 1.0;

        // 预先计算所有节点的文本布局和大小
        for (GraphNode node : nodes) {
            calculateNodeSizeAndLayout(node);
        }

        revalidate();
        repaint();
    }

    private void calculateNodeSizeAndLayout(GraphNode node) {
        calculateNodeSizeAndLayout(node, false);
    }

    private void calculateNodeSizeAndLayout(GraphNode node, boolean isRescale) {
        String text = node.getDisplayText();
        if (text == null || text.trim().isEmpty()) {
            node.setSize((int)(MIN_NODE_SIZE * (isRescale ? 1 : scale)));
            node.setTextLines(new ArrayList<>());
            return;
        }

        BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = tempImage.createGraphics();
        g2d.setFont(isRescale ? scaledNodeFont : originalNodeFont);
        FontMetrics fm = g2d.getFontMetrics();

        // 计算文本换行
        TextLayoutResult layout = calculateOptimalTextLayout(text, fm);

        // 根据文本行数和最大行宽计算节点大小
        int charWidth = fm.stringWidth("M");
        int maxLineWidth = Math.min(layout.maxLineWidth, MAX_CHARS_PER_LINE * charWidth);
        int textWidth = maxLineWidth + TEXT_PADDING * 2;
        int textHeight = layout.lines.size() * fm.getHeight() + TEXT_PADDING * 2;

        // 计算接近正方形的最小尺寸
        int minDimension = Math.max(textWidth, textHeight);
        int maxDimension = (int)(minDimension * 1.2);

        int nodeSize;
        if (Math.abs(textWidth - textHeight) < minDimension * 0.3) {
            nodeSize = Math.max(textWidth, textHeight);
        } else if (textWidth > textHeight) {
            nodeSize = Math.min(maxDimension, Math.max(textWidth, (int)(textHeight * 1.1)));
        } else {
            nodeSize = Math.min(maxDimension, Math.max(textHeight, (int)(textWidth * 1.1)));
        }

        nodeSize = Math.max((int)(MIN_NODE_SIZE * (isRescale ? 1 : scale)), nodeSize);

        // 如果是缩放时的重新计算，需要调整节点大小
        if (isRescale) {
            // 保持原始比例，但考虑缩放
            node.setSize((int)(nodeSize * scale));
        } else {
            node.setSize(nodeSize);
        }

        node.setTextLines(layout.lines);
        node.setMaxLineWidth(maxLineWidth);
        node.setFontMetrics(fm);

        g2d.dispose();
    }

    private TextLayoutResult calculateOptimalTextLayout(String text, FontMetrics fm) {
        List<String> lines = new ArrayList<>();
        int maxLineWidth = 0;

        // 按换行符分割段落（中文同样支持\n换行）
        String[] paragraphs = text.split("\n");

        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) continue;

            // 适配中文的分词逻辑
            // 1. 保留英文标点分割，新增中文标点分割（。，！？；：、）
            // 2. 对连续非标点/非空格的字符，按"字符组"分割（避免整段中文被视为一个word）
            // (?<=[,.!?;:。，！？；：、])|(?<=\S)(?=\s)|(?<=\s)(?=\S)
            String[] words = paragraph.split("(?<=[,.!?;:。，！？；：、])");
            // 过滤空字符串（分割可能产生的空元素）
            List<String> validWords = new ArrayList<>();
            for (String word : words) {
                if (!word.trim().isEmpty()) {
                    validWords.add(word);
                }
            }

            StringBuilder currentLine = new StringBuilder();

            for (String word : validWords) {
                // 处理中文连续文本（无空格）
                // 如果当前word是长文本（无空格），尝试按字符拆分加入当前行
                if (word.length() > 1 && !word.contains(" ")) {
                    // 逐字符处理长文本，避免直接当作单个word
                    processLongNonSpaceWord(fm, word, currentLine, lines, maxLineWidth);
                    // 更新maxLineWidth（processLongNonSpaceWord中可能添加了新行）
                    if (!lines.isEmpty()) {
                        int lastLineWidth = fm.stringWidth(lines.get(lines.size() - 1));
                        maxLineWidth = Math.max(maxLineWidth, lastLineWidth);
                    }
                } else {
                    // 常规word处理（带空格或标点的片段）
                    String testLine = currentLine.length() > 0 ?
                        currentLine + word : word; // 中文可能不需要额外空格，直接拼接

                    // 【宽度基准改用中文字符】
                    int baseWidth = fm.stringWidth(CHINESE_BASE_CHAR);
                    boolean exceedsCharLimit = testLine.length() > MAX_CHARS_PER_LINE;
                    boolean exceedsWidthLimit = fm.stringWidth(testLine) > MAX_CHARS_PER_LINE * baseWidth;

                    if (exceedsCharLimit || exceedsWidthLimit) {
                        if (currentLine.length() > 0) {
                            // 保存当前行，更新最大宽度
                            lines.add(currentLine.toString());
                            maxLineWidth = Math.max(maxLineWidth, fm.stringWidth(currentLine.toString()));
                            currentLine = new StringBuilder(word);
                        } else {
                            // 单个word仍超长，强制截断（适配中文）
                            String truncated = truncateWordToFit(fm, word, MAX_CHARS_PER_LINE, baseWidth);
                            lines.add(truncated);
                            maxLineWidth = Math.max(maxLineWidth, fm.stringWidth(truncated));
                            // 处理剩余部分
                            String remaining = word.substring(truncated.length()).trim();
                            if (!remaining.isEmpty()) {
                                currentLine = new StringBuilder(remaining);
                            }
                        }
                    } else {
                        currentLine = new StringBuilder(testLine);
                    }
                }
            }

            // 段落结束后，添加剩余内容
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
                maxLineWidth = Math.max(maxLineWidth, fm.stringWidth(currentLine.toString()));
            }
        }

        return new TextLayoutResult(lines, maxLineWidth);
    }

    // 处理无空格的长文本（主要是中文）
    private void processLongNonSpaceWord(FontMetrics fm, String longWord, StringBuilder currentLine,
        List<String> lines, int currentMaxWidth) {
        int baseWidth = fm.stringWidth(CHINESE_BASE_CHAR);
        int start = 0;
        int length = longWord.length();

        while (start < length) {
            // 尝试从当前位置取字符，直到接近行限制
            int end = start;
            String candidate;
            do {
                end++;
                if (end > length) break;
                candidate = currentLine.length() > 0 ?
                    currentLine + longWord.substring(start, end) : longWord.substring(start, end);
            } while (candidate.length() <= MAX_CHARS_PER_LINE
                && fm.stringWidth(candidate) <= MAX_CHARS_PER_LINE * baseWidth
                && end <= length);

            // 回退到符合条件的位置
            end--;
            if (end <= start) {
                // 单个字符就超限（极特殊情况，如宽字符）
                end = start + 1;
            }

            String part = longWord.substring(start, end);
            String newLine = currentLine.length() > 0 ? currentLine + part : part;

            // 添加到行或新建行
            if (currentLine.length() == 0) {
                lines.add(newLine);
                currentMaxWidth = Math.max(currentMaxWidth, fm.stringWidth(newLine));
            } else {
                lines.add(newLine);
                currentMaxWidth = Math.max(currentMaxWidth, fm.stringWidth(newLine));
                currentLine.setLength(0); // 重置当前行
            }

            start = end;
        }
    }

    private String truncateWordToFit(FontMetrics fm, String word, int maxChars, int baseWidth) {
        if (word.length() <= maxChars) return word;

        // 尝试找到符合宽度的截断点（中文按字符逐个检查）
        for (int i = Math.min(maxChars, word.length()); i > 1; i--) { // 中文最小保留1个字符
            String candidate = word.substring(0, i);
            if (fm.stringWidth(candidate) <= maxChars * baseWidth) {
                return candidate;
            }
        }

        // 强制截断（至少保留1个字符）
        return word.substring(0, Math.min(maxChars, word.length()));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;

        if (nodes.isEmpty()) {
            g2d.drawString("没有数据可显示", getWidth() / 2 - 40, getHeight() / 2);
            return;
        }

        // 启用抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setFont(scaledNodeFont); // 使用缩放后的字体

        // 按深度分组节点
        Map<Integer, List<GraphNode>> nodesByDepth = new TreeMap<>();
        for (GraphNode node : nodes) {
            nodesByDepth.computeIfAbsent(node.depth, k -> new ArrayList<>()).add(node);
        }

        // 计算布局
        calculateLayout(nodesByDepth, g2d);

        // 绘制边
        g2d.setColor(Color.GRAY);
        g2d.setStroke(new BasicStroke(1.5f * (float)scale)); // 边线也随缩放调整
        for (GraphEdge edge : edges) {
            Point from = edge.from.getPosition();
            Point to = edge.to.getPosition();
            g2d.drawLine(
                (int)(from.x * scale), (int)(from.y * scale),
                (int)(to.x * scale), (int)(to.y * scale)
            );
        }

        // 绘制节点
        for (GraphNode node : nodes) {
            drawNode(g2d, node);
        }
    }

    private void drawNode(Graphics2D g2d, GraphNode node) {
        Point pos = node.getPosition();
        int scaledSize = (int)(node.getSize() * (scale > 1 ? 1 : scale)); // 缩放时保持节点大小相对稳定
        int x = (int)(pos.x * scale) - scaledSize / 2;
        int y = (int)(pos.y * scale) - scaledSize / 2;

        // 绘制节点背景
        g2d.setColor(Color.WHITE);
        g2d.fillRect(x, y, scaledSize, scaledSize);
        g2d.setColor(Color.BLUE);
        g2d.drawRect(x, y, scaledSize, scaledSize);

        // 绘制节点文本
        drawNodeText(g2d, node, x, y, scaledSize);
    }

    private void drawNodeText(Graphics2D g2d, GraphNode node, int x, int y, int size) {
        if (node.getTextLines().isEmpty()) return;

        FontMetrics fm = g2d.getFontMetrics();
        int lineHeight = fm.getHeight();
        int totalTextHeight = node.getTextLines().size() * lineHeight;

        // 精确计算文本起始位置
        int textStartY = y + (size - totalTextHeight) / 2 + fm.getAscent() - fm.getDescent() / 2;

        g2d.setColor(Color.BLACK);

        for (int i = 0; i < node.getTextLines().size(); i++) {
            String line = node.getTextLines().get(i);
            int lineWidth = fm.stringWidth(line);
            int textStartX = x + (size - lineWidth) / 2;
            g2d.drawString(line, textStartX, textStartY + i * lineHeight);
        }
    }

    private void calculateLayout(Map<Integer, List<GraphNode>> nodesByDepth, Graphics2D g2d) {
        int maxWidth = 0;

        // 计算水平布局，考虑缩放
        for (Integer depth : nodesByDepth.keySet()) {
            List<GraphNode> depthNodes = nodesByDepth.get(depth);

            // 计算这一层需要的总宽度（考虑缩放）
            int totalWidth = depthNodes.stream().mapToInt(node -> (int)(node.getSize() * (scale > 1 ? 1 : scale))).sum() +
                (int)(MIN_HORIZONTAL_SPACING * scale) * (depthNodes.size() - 1);

            maxWidth = Math.max(maxWidth, totalWidth);

            // 计算每个节点的X位置
            int startX = (getPreferredSize().width - totalWidth) / 2;
            if (startX < (int)(50 * scale)) startX = (int)(50 * scale);

            int x = startX;

            for (GraphNode node : depthNodes) {
                int nodeDisplaySize = (int)(node.getSize() * (scale > 1 ? 1 : scale));
                node.setPosition(new Point(x + nodeDisplaySize / 2, 0));
                x += nodeDisplaySize + (int)(MIN_HORIZONTAL_SPACING * scale);
            }
        }

        // 计算垂直布局和调整间距
        int previousDepthMaxBottom = 0;
        boolean firstDepth = true;

        for (Integer depth : nodesByDepth.keySet()) {
            List<GraphNode> depthNodes = nodesByDepth.get(depth);

            // 找到这一层中最大的节点高度（考虑缩放）
            int maxNodeHeight = depthNodes.stream()
                .mapToInt(node -> (int)(node.getSize() * (scale > 1 ? 1 : scale)))
                .max()
                .orElse((int)(MIN_NODE_SIZE * scale));

            // 计算这一层的Y位置
            int yPosition;
            if (firstDepth) {
                yPosition = (int)(50 * scale) + maxNodeHeight / 2;
                firstDepth = false;
            } else {
                yPosition = previousDepthMaxBottom + (int)(MIN_VERTICAL_SPACING * scale) + maxNodeHeight / 2;
                yPosition = adjustVerticalSpacing(depth, depthNodes, yPosition, nodesByDepth);
            }

            // 更新这一层所有节点的Y位置
            for (GraphNode node : depthNodes) {
                Point pos = node.getPosition();
                node.setPosition(new Point(pos.x, yPosition));
            }

            previousDepthMaxBottom = yPosition + maxNodeHeight / 2;
        }

        // 设置首选大小，考虑缩放
        int totalHeight = previousDepthMaxBottom + (int)(50 * scale);
        setPreferredSize(new Dimension(
            (int)(maxWidth + 100 * scale),
            totalHeight
        ));
    }

    private int adjustVerticalSpacing(int currentDepth, List<GraphNode> currentNodes,
        int proposedY, Map<Integer, List<GraphNode>> nodesByDepth) {
        int adjustedY = proposedY;

        Integer previousDepth = currentDepth - 1;
        if (nodesByDepth.containsKey(previousDepth)) {
            List<GraphNode> previousNodes = nodesByDepth.get(previousDepth);

            for (GraphNode currentNode : currentNodes) {
                for (GraphNode previousNode : previousNodes) {
                    int currentTop = adjustedY - currentNode.getSize() / 2;
                    int previousBottom = previousNode.getPosition().y + previousNode.getSize() / 2;

                    if (currentTop < previousBottom + MIN_VERTICAL_SPACING) {
                        int requiredSpacing = previousBottom + MIN_VERTICAL_SPACING + currentNode.getSize() / 2;
                        adjustedY = Math.max(adjustedY, requiredSpacing);
                    }
                }
            }
        }

        return adjustedY;
    }
}