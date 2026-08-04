# 报告导出功能实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为日志分析、需求分析（RAM）、项目现状分析添加 MD 格式导出功能，日志分析支持时间筛选批量导出为 ZIP。

**Architecture:** 
- 后端新增导出端点，生成 MD 内容并返回文件流
- 日志批量导出使用 ZIP 打包，每个 MD 文件以报告 ID 命名
- 前端调用 API 后触发浏览器下载

**Tech Stack:** Spring Boot (后端) + Vue 3/Element Plus (前端) + JSZip (前端 ZIP 解压可选)

---

## Task 1: 后端 - 日志分析单报告导出 MD

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/ReportExportService.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/LogAnalysisController.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/controller/LogAnalysisControllerTest.java`

**Step 1: Write the failing test**

```java
// In LogAnalysisControllerTest.java
@Test
void exportReportMd_returnsMarkdownContent() throws Exception {
    // Given: a completed report exists
    Long reportId = 1L;
    
    // When: request export
    mockMvc.perform(get("/api/log/report/{id}/export/md", reportId))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "text/markdown; charset=utf-8"))
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"report-1.md\""))
        .andExpect(content().string(containsString("# 日志分析报告")));
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LogAnalysisControllerTest#exportReportMd_returnsMarkdownContent -pl hisi-dev-tool`
Expected: FAIL with 404 (endpoint not found)

**Step 3: Create ReportExportService**

```java
package com.huawei.hisi.service;

import com.huawei.hisi.model.LogAnalysisReport;
import com.huawei.hisi.repository.LogAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class ReportExportService {
    
    private final LogAnalysisRepository logAnalysisRepository;
    
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    public String exportLogReportAsMd(Long reportId) {
        LogAnalysisReport report = logAnalysisRepository.findById(reportId)
            .orElseThrow(() -> new IllegalArgumentException("报告不存在: " + reportId));
        
        StringBuilder sb = new StringBuilder();
        sb.append("# 日志分析报告 #").append(reportId).append("\n\n");
        
        // Header info
        sb.append("## 基本信息\n\n");
        sb.append("| 字段 | 值 |\n");
        sb.append("|------|----|\n");
        sb.append("| 报告编号 | ").append(report.getReportNo() != null ? report.getReportNo() : reportId).append(" |\n");
        sb.append("| 查询时间 | ").append(report.getQueryTime() != null ? report.getQueryTime().format(TIME_FMT) : "-").append(" |\n");
        sb.append("| 状态 | ").append(report.getStatus() != null ? report.getStatus() : "-").append(" |\n");
        sb.append("| 创建时间 | ").append(report.getCreatedAt() != null ? report.getCreatedAt().format(TIME_FMT) : "-").append(" |\n\n");
        
        // Core content
        sb.append("## 错误摘要\n\n");
        sb.append(report.getLogSummary() != null ? report.getLogSummary() : "暂无").append("\n\n");
        
        sb.append("## 根本原因\n\n");
        sb.append(report.getRootCause() != null ? report.getRootCause() : "暂无").append("\n\n");
        
        sb.append("## 修复建议\n\n");
        sb.append(report.getFixSuggestion() != null ? report.getFixSuggestion() : "暂无").append("\n\n");
        
        // Error stack
        if (report.getErrorStack() != null && !report.getErrorStack().isBlank()) {
            sb.append("## 错误堆栈\n\n");
            sb.append("```\n").append(report.getErrorStack()).append("\n```\n\n");
        }
        
        // Related code
        if (report.getRelatedCode() != null && !report.getRelatedCode().isEmpty()) {
            sb.append("## 相关代码\n\n");
            for (LogAnalysisReport.RelatedCode code : report.getRelatedCode()) {
                sb.append("### ").append(code.getClassName()).append(".");
                sb.append(code.getMethodName() != null ? code.getMethodName() : "unknown").append("\n\n");
                if (code.getCodeSnippet() != null) {
                    sb.append("```java\n").append(code.getCodeSnippet()).append("\n```\n\n");
                }
            }
        }
        
        return sb.toString();
    }
}
```

**Step 4: Add export endpoint in LogAnalysisController**

```java
// Add to LogAnalysisController.java
@GetMapping("/report/{id}/export/md")
public ResponseEntity<String> exportReportMd(@PathVariable Long id) {
    String markdown = reportExportService.exportLogReportAsMd(id);
    return ResponseEntity.ok()
        .header("Content-Type", "text/markdown; charset=utf-8")
        .header("Content-Disposition", "attachment; filename=\"report-" + id + ".md\"")
        .body(markdown);
}
```

**Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=LogAnalysisControllerTest#exportReportMd_returnsMarkdownContent -pl hisi-dev-tool`
Expected: PASS

**Step 6: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/service/ReportExportService.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/controller/LogAnalysisController.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/controller/LogAnalysisControllerTest.java
git commit -m "feat(log): add single report export to markdown"
```

---

## Task 2: 后端 - 日志分析批量导出 ZIP

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/ReportExportService.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/LogAnalysisController.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/service/ReportExportServiceTest.java`

**Step 1: Write the failing test**

```java
@Test
void exportReportsAsZip_returnsZipFile() throws Exception {
    // Given: reports within time range
    String startTime = "2024-01-01T00:00:00";
    String endTime = "2024-01-02T00:00:00";
    
    // When: request batch export
    byte[] zipContent = mockMvc.perform(get("/api/log/reports/export/zip")
            .param("startTime", startTime)
            .param("endTime", endTime))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/zip"))
        .andReturn().getResponse().getContentAsByteArray();
    
    // Then: ZIP contains MD files
    assertThat(zipContent.length).isGreaterThan(0);
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ReportExportServiceTest#exportReportsAsZip_returnsZipFile -pl hisi-dev-tool`
Expected: FAIL (endpoint not found)

**Step 3: Add batch export to ReportExportService**

```java
// Add to ReportExportService.java
public byte[] exportLogReportsAsZip(LocalDateTime startTime, LocalDateTime endTime) throws IOException {
    List<LogAnalysisReport> reports = logAnalysisRepository.findByCreatedAtBetween(startTime, endTime);
    
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ZipOutputStream zos = new ZipOutputStream(baos);
    
    for (LogAnalysisReport report : reports) {
        String mdContent = exportLogReportAsMd(report.getId());
        ZipEntry entry = new ZipEntry("report-" + report.getId() + ".md");
        zos.putNextEntry(entry);
        zos.write(mdContent.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
    
    zos.close();
    return baos.toByteArray();
}
```

**Step 4: Add batch export endpoint**

```java
// Add to LogAnalysisController.java
@GetMapping("/reports/export/zip")
public ResponseEntity<byte[]> exportReportsZip(
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime
) throws IOException {
    byte[] zipContent = reportExportService.exportLogReportsAsZip(startTime, endTime);
    String filename = "reports-" + startTime.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".zip";
    return ResponseEntity.ok()
        .header("Content-Type", "application/zip")
        .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
        .body(zipContent);
}
```

**Step 5: Add repository query method**

```java
// Add to LogAnalysisRepository.java
List<LogAnalysisReport> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
```

**Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=ReportExportServiceTest#exportReportsAsZip_returnsZipFile -pl hisi-dev-tool`
Expected: PASS

**Step 7: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/service/ReportExportService.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/controller/LogAnalysisController.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java
git commit -m "feat(log): add batch report export to ZIP with time range filter"
```

---

## Task 3: 后端 - RAM 需求分析导出 MD

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/ReportExportService.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/controller/RamController.java`

**Step 1: Add RAM export method in ReportExportService**

```java
// Add to ReportExportService.java
public String exportRamSessionAsMd(String sessionId) {
    AgentSession session = agentSessionRepository.findByUuid(sessionId)
        .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
    
    List<AgentEvent> events = agentEventRepository.findBySessionIdOrderBySeq(session.getId());
    
    StringBuilder sb = new StringBuilder();
    sb.append("# 需求分析报告 #").append(sessionId).append("\n\n");
    
    // Header info
    sb.append("## 基本信息\n\n");
    sb.append("| 字段 | 值 |\n");
    sb.append("|------|----|\n");
    sb.append("| 会话ID | ").append(sessionId).append(" |\n");
    sb.append("| 状态 | ").append(session.getStatus() != null ? session.getStatus() : "-").append(" |\n");
    sb.append("| 需求意图 | ").append(session.getIntent() != null ? session.getIntent() : "-").append(" |\n");
    sb.append("| 项目路径 | ").append(session.getProjectPaths() != null ? session.getProjectPaths() : "-").append(" |\n");
    sb.append("| 创建时间 | ").append(formatTimestamp(session.getCreatedAt())).append(" |\n\n");
    
    // Node outputs from events
    sb.append("## 分析内容\n\n");
    
    for (AgentEvent evt : events) {
        if (evt.getType() != null && evt.getType().startsWith("NODE_OUTPUT")) {
            String nodeName = evt.getPayload() != null ? 
                String.valueOf(evt.getPayload().get("nodeName")) : "unknown";
            String output = evt.getPayload() != null ? 
                String.valueOf(evt.getPayload().get("output")) : "";
            
            sb.append("### ").append(nodeName).append("\n\n");
            sb.append(output).append("\n\n");
        }
    }
    
    return sb.toString();
}

private String formatTimestamp(Long ts) {
    if (ts == null) return "-";
    return new java.util.Date(ts * 1000L).toString();
}
```

**Step 2: Add export endpoint in RamController**

```java
// Add to RamController.java
@GetMapping("/sessions/{sid}/export/md")
public ResponseEntity<String> exportSessionMd(@PathVariable String sid) {
    String markdown = reportExportService.exportRamSessionAsMd(sid);
    return ResponseEntity.ok()
        .header("Content-Type", "text/markdown; charset=utf-8")
        .header("Content-Disposition", "attachment; filename=\"ram-" + sid + ".md\"")
        .body(markdown);
}
```

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/service/ReportExportService.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/controller/RamController.java
git commit -m "feat(ram): add session export to markdown"
```

---

## Task 4: 后端 - 项目现状分析导出 MD

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/ReportExportService.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/controller/RamStatusController.java`

**Step 1: Add RAM Status export method**

```java
// Add to ReportExportService.java
public String exportRamStatusAsMd(String sessionId) {
    AgentSession session = agentSessionRepository.findByUuid(sessionId)
        .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
    
    StringBuilder sb = new StringBuilder();
    sb.append("# 项目现状分析报告 #").append(sessionId).append("\n\n");
    
    // Header info
    sb.append("## 基本信息\n\n");
    sb.append("| 字段 | 值 |\n");
    sb.append("|------|----|\n");
    sb.append("| 会话ID | ").append(sessionId).append(" |\n");
    sb.append("| 状态 | ").append(session.getStatus() != null ? session.getStatus() : "-").append(" |\n");
    sb.append("| 项目路径 | ").append(session.getProjectPaths() != null ? session.getProjectPaths() : "-").append(" |\n");
    sb.append("| 分析时间 | ").append(formatTimestamp(session.getCreatedAt())).append(" |\n\n");
    
    // Report content from events
    sb.append("## 分析结果\n\n");
    
    List<AgentEvent> events = agentEventRepository.findBySessionIdOrderBySeq(session.getId());
    for (AgentEvent evt : events) {
        if ("STATUS_REPORT".equals(evt.getType()) || "NODE_OUTPUT".equals(evt.getType())) {
            Map<String, Object> payload = evt.getPayload();
            if (payload != null && payload.containsKey("output")) {
                sb.append(payload.get("output")).append("\n\n");
            }
        }
    }
    
    return sb.toString();
}
```

**Step 2: Add export endpoint in RamStatusController**

```java
// Add to RamStatusController.java
@GetMapping("/{sid}/export/md")
public ResponseEntity<String> exportStatusMd(@PathVariable String sid) {
    String markdown = reportExportService.exportRamStatusAsMd(sid);
    return ResponseEntity.ok()
        .header("Content-Type", "text/markdown; charset=utf-8")
        .header("Content-Disposition", "attachment; filename=\"status-" + sid + ".md\"")
        .body(markdown);
}
```

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/service/ReportExportService.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/controller/RamStatusController.java
git commit -m "feat(ram-status): add report export to markdown"
```

---

## Task 5: 前端 - 日志分析导出按钮

**Files:**
- Modify: `hisi-dev-tool-frontend/src/api/logAnalysis.ts`
- Modify: `hisi-dev-tool-frontend/src/views/log-analysis/ReportDetail.vue`
- Modify: `hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue`

**Step 1: Add export API functions**

```typescript
// Add to logAnalysis.ts
exportSingleReportMd(id: string): Promise<void> {
  return request.get(`/log/report/${id}/export/md`, { responseType: 'blob' })
    .then(blob => {
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `report-${id}.md`
      a.click()
      window.URL.revokeObjectURL(url)
    })
}

exportReportsZip(startTime: Date, endTime: Date): Promise<void> {
  return request.get('/log/reports/export/zip', {
    params: { startTime, endTime },
    responseType: 'blob'
  }).then(blob => {
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `reports-${startTime.toISOString().slice(0,10)}.zip`
    a.click()
    window.URL.revokeObjectURL(url)
  })
}
```

**Step 2: Add export button in ReportDetail.vue**

```vue
<!-- Add to ReportDetail.vue template header buttons -->
<el-button type="success" @click="handleExportMd">
  <el-icon><Download /></el-icon>
  导出 MD
</el-button>

<!-- Add to script -->
import { Download } from '@element-plus/icons-vue'

async function handleExportMd(): Promise<void> {
  try {
    await logAnalysisApi.exportSingleReportMd(reportId.value)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}
```

**Step 3: Add batch export in LogQuery.vue**

```vue
<!-- Add batch export section -->
<el-form-item label="批量导出">
  <el-date-picker v-model="exportTimeRange" type="datetimerange" range-separator="至" />
  <el-button type="success" @click="handleBatchExport" :disabled="!exportTimeRange">
    导出 ZIP
  </el-button>
</el-form-item>

<!-- Add to script -->
const exportTimeRange = ref<[Date, Date] | null>(null)

async function handleBatchExport(): Promise<void> {
  if (!exportTimeRange.value) return
  try {
    await logAnalysisApi.exportReportsZip(exportTimeRange.value[0], exportTimeRange.value[1])
    ElMessage.success('批量导出成功')
  } catch {
    ElMessage.error('批量导出失败')
  }
}
```

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/api/logAnalysis.ts
git add hisi-dev-tool-frontend/src/views/log-analysis/ReportDetail.vue
git add hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue
git commit -m "feat(log-ui): add export buttons for single MD and batch ZIP"
```

---

## Task 6: 前端 - RAM 导出按钮

**Files:**
- Modify: `hisi-dev-tool-frontend/src/api/ram.ts`
- Modify: `hisi-dev-tool-frontend/src/views/ram/DraftPage.vue`

**Step 1: Add export API function**

```typescript
// Add to ram.ts
export function exportRamSessionMd(sessionId: string): Promise<void> {
  return request.get(`/ram/sessions/${sessionId}/export/md`, { responseType: 'blob' })
    .then(blob => {
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `ram-${sessionId}.md`
      a.click()
      window.URL.revokeObjectURL(url)
    })
}
```

**Step 2: Add export button in DraftPage.vue**

```vue
<!-- Add to top-bar actions -->
<el-button type="success" size="small" @click="handleExportMd" :disabled="session.status.value !== 'completed'">
  <el-icon><Download /></el-icon>
  导出 MD
</el-button>

<!-- Add to script -->
import { exportRamSessionMd } from '@/api/ram'

async function handleExportMd(): Promise<void> {
  try {
    await exportRamSessionMd(sid.value)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}
```

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/api/ram.ts
git add hisi-dev-tool-frontend/src/views/ram/DraftPage.vue
git commit -m "feat(ram-ui): add export MD button on DraftPage"
```

---

## Task 7: 前端 - 项目现状分析导出按钮

**Files:**
- Modify: `hisi-dev-tool-frontend/src/api/ram.ts`
- Modify: `hisi-dev-tool-frontend/src/views/ram/StatusSessionListPage.vue` (or detail page)

**Step 1: Add status export API function**

```typescript
// Add to ram.ts
export function exportRamStatusMd(sessionId: string): Promise<void> {
  return request.get(`/ram/status/${sessionId}/export/md`, { responseType: 'blob' })
    .then(blob => {
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `status-${sessionId}.md`
      a.click()
      window.URL.revokeObjectURL(url)
    })
}
```

**Step 2: Add export button in status detail page**

```vue
<!-- Add export button -->
<el-button type="success" size="small" @click="handleExportMd">
  <el-icon><Download /></el-icon>
  导出 MD
</el-button>

<!-- Add to script -->
async function handleExportMd(): Promise<void> {
  try {
    await exportRamStatusMd(sessionId)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}
```

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/api/ram.ts
git commit -m "feat(ram-status-ui): add export MD button"
```

---

## Task 8: 验证与测试

**Step 1: Run all backend tests**

```bash
mvn test -pl hisi-dev-tool
```

Expected: All tests pass

**Step 2: Start backend and frontend**

```bash
cd hisi-dev-tool && mvn spring-boot:run
cd hisi-dev-tool-frontend && npm run dev
```

**Step 3: Manual verification**

1. Log Analysis:
   - Navigate to `/log-analysis`
   - Click on a report, verify "导出 MD" button works
   - Set time range, verify "导出 ZIP" works

2. RAM:
   - Navigate to `/ram/draft/:sid`
   - Complete a session, verify "导出 MD" button works

3. RAM Status:
   - Navigate to `/ram/status/:sid`
   - Verify export button works

**Step 4: Commit**

```bash
git commit -m "test: verify export functionality works"
```

---

## Summary

| 模块 | 功能 | 格式 |
|------|------|------|
| 日志分析 | 单报告导出 | MD |
| 日志分析 | 批量导出（时间筛选） | ZIP（每个 MD 为一个报告） |
| RAM 需求分析 | 会话导出 | MD |
| RAM 现状分析 | 报告导出 | MD |

MD 格式包含：
- 报告 ID / 会话 ID
- 状态、时间等头信息
- 核心内容（错误摘要、根本原因、修复建议等）