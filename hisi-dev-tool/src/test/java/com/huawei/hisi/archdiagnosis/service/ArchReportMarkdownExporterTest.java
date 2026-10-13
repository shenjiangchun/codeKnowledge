package com.huawei.hisi.archdiagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告 Markdown 导出单测：检测明细展开为表格、原始聚合保持计数、扣分汇总。
 */
class ArchReportMarkdownExporterTest {

    private final ArchReportMarkdownExporter exporter = new ArchReportMarkdownExporter(new ObjectMapper());

    @Test
    @DisplayName("展开幽灵类明细为表格（不再只是计数）")
    void expandsDetectionItems() {
        Map<String, Object> report = baseReport();
        @SuppressWarnings("unchecked")
        Map<String, Object> chapters = (Map<String, Object>) report.get("chapters");
        Map<String, Object> classChapter = new LinkedHashMap<>();
        classChapter.put("ghostClasses", Map.of("items", List.of(
            Map.of("className", "a.Ghost", "packageName", "a", "reason", "no_method"))));
        classChapter.put("anemicDataClasses", Map.of("items", List.of()));
        chapters.put("class", classChapter);

        String md = exporter.toMarkdown(report);

        assertThat(md).contains("### 幽灵类");
        assertThat(md).contains("a.Ghost");
        assertThat(md).doesNotContain("ghostClasses: 1 项");
    }

    @Test
    @DisplayName("原始聚合数据保持计数")
    void keepsAggregationsAsCounts() {
        Map<String, Object> report = baseReport();
        @SuppressWarnings("unchecked")
        Map<String, Object> chapters = (Map<String, Object>) report.get("chapters");
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("domains", Map.of("a", 1, "b", 2));
        chapters.put("project", project);

        String md = exporter.toMarkdown(report);

        assertThat(md).contains("**domains**: 2 项");
    }

    @Test
    @DisplayName("扣分汇总按严重度计数并折算原始扣分")
    void summarizesDeductions() {
        Map<String, Object> report = baseReport();
        report.put("deductions", List.of(
            Map.of("severity", "HIGH", "points", 10, "reason", "r1"),
            Map.of("severity", "HIGH", "points", 10, "reason", "r2"),
            Map.of("severity", "LOW", "points", 2, "reason", "r3")));

        String md = exporter.toMarkdown(report);

        assertThat(md).contains("HIGH: 2 项");
        assertThat(md).contains("LOW: 1 项");
        assertThat(md).contains("原始扣分合计: -22");
    }

    @Test
    @DisplayName("去冗余空表：空检测章节不输出标题与空表占位")
    void skipsEmptyTables() {
        String md = exporter.toMarkdown(baseReport());

        assertThat(md).doesNotContain("### 模块枢纽");
        assertThat(md).doesNotContain("### 幽灵类");
        assertThat(md).doesNotContain("_未发现。_");
    }

    @Test
    @DisplayName("折叠重复行：完全相同的明细行合并为一条并标注 ×N")
    void collapsesDuplicateRows() {
        Map<String, Object> report = baseReport();
        @SuppressWarnings("unchecked")
        Map<String, Object> chapters = (Map<String, Object>) report.get("chapters");
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("moduleHubs", List.of(
            Map.of("module", "m1", "fanIn", 5, "fanOut", 3),
            Map.of("module", "m1", "fanIn", 5, "fanOut", 3)));
        chapters.put("module", module);

        String md = exporter.toMarkdown(report);

        assertThat(md).contains("m1");
        assertThat(md).contains("×2");
        assertThat(md).containsOnlyOnce("m1");
    }

    @Test
    @DisplayName("同源计数聚合：同包多个幽灵类折叠为「包名（N 项）」")
    void aggregatesBySource() {
        Map<String, Object> report = baseReport();
        @SuppressWarnings("unchecked")
        Map<String, Object> chapters = (Map<String, Object>) report.get("chapters");
        Map<String, Object> classChapter = new LinkedHashMap<>();
        classChapter.put("ghostClasses", Map.of("items", List.of(
            Map.of("className", "a.G1", "packageName", "a", "reason", "no_method"),
            Map.of("className", "a.G2", "packageName", "a", "reason", "isolated"))));
        chapters.put("class", classChapter);

        String md = exporter.toMarkdown(report);

        assertThat(md).contains("### 幽灵类");
        assertThat(md).contains("a（2 项）");
        assertThat(md).doesNotContain("a.G1");
    }

    private Map<String, Object> baseReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("healthScore", 100);
        report.put("grade", "A");
        report.put("generatedAt", "2026-01-01 00:00:00");
        report.put("projectPaths", List.of("/test"));
        report.put("deductions", List.of());
        report.put("issues", List.of());
        Map<String, Object> chapters = new LinkedHashMap<>();
        chapters.put("project", new LinkedHashMap<>());
        chapters.put("module", new LinkedHashMap<>());
        chapters.put("class", new LinkedHashMap<>());
        chapters.put("method", new LinkedHashMap<>());
        report.put("chapters", chapters);
        return report;
    }
}
