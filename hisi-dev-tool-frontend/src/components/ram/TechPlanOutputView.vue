<script setup lang="ts">
/**
 * TechPlanOutputView — Human-readable renderer for tech_plan node output.
 *
 * Renders:
 * - target_methods_detail: detailed method change specifications
 * - sequence_diagrams / flow_diagrams: Mermaid diagrams
 * - test_scope: unit/integration/migration tests
 * - risk_mitigations: risk-mitigation pairs
 * - reasoning & markdown_report: collapsible sections
 */
import { computed, ref } from 'vue'
import { renderMarkdown } from '@/utils/markdown'
import MermaidDiagram from './MermaidDiagram.vue'

// ─── Types ───────────────────────────────────────────────────────────────────

interface MethodDetail {
  method?: string
  file?: string
  lines?: string
  current_logic?: string
  change_spec?: string
  pseudocode?: string
}

interface MermaidDiagramData {
  name?: string
  mermaid?: string
}

interface TestScope {
  unit_tests?: string[]
  integration_tests?: string[]
  data_migration?: string[]
}

interface RiskMitigation {
  risk?: string
  mitigation?: string
}

interface TechPlanOutput {
  target_methods_detail?: MethodDetail[]
  sequence_diagrams?: MermaidDiagramData[]
  flow_diagrams?: MermaidDiagramData[]
  test_scope?: TestScope
  risk_mitigations?: RiskMitigation[]
  reasoning?: string
  markdown_report?: string
}

const props = defineProps<{
  output: TechPlanOutput
}>()

// ─── Markdown Rendering ──────────────────────────────────────────────────────

const showMarkdownReport = ref(true)

const renderedMarkdown = computed(() => {
  const md = props.output.markdown_report
  if (!md) return ''
  return renderMarkdown(md)
})

// ─── Computed ────────────────────────────────────────────────────────────────

const methods = computed(() => props.output.target_methods_detail ?? [])
const seqDiagrams = computed(() => props.output.sequence_diagrams ?? [])
const flowDiagrams = computed(() => props.output.flow_diagrams ?? [])
const testScope = computed(() => props.output.test_scope)
const mitigations = computed(() => props.output.risk_mitigations ?? [])

const hasReasoning = computed(() => !!props.output.reasoning?.trim())
const showReasoning = ref(false)
const hasMarkdownReport = computed(() => !!props.output.markdown_report?.trim())

const totalTests = computed(() => {
  const scope = testScope.value
  if (!scope) return 0
  return (scope.unit_tests?.length ?? 0) + (scope.integration_tests?.length ?? 0) + (scope.data_migration?.length ?? 0)
})
</script>

<template>
  <div class="tech-plan-output">
    <!-- ① Target Methods Detail -->
    <div v-if="methods.length > 0" class="section">
      <div class="section-header">
        <span class="section-icon">🎯</span>
        <span class="section-title">目标方法详细方案</span>
        <el-tag size="small" type="primary" round>{{ methods.length }}个</el-tag>
      </div>
      <div class="section-desc">每个需要修改的方法的具体变更方案：</div>
      <div v-for="(m, idx) in methods" :key="idx" class="method-detail-card">
        <div class="method-detail-header">
          <code class="method-name">{{ m.method ?? '(未知方法)' }}</code>
          <span v-if="m.file" class="method-file">{{ m.file }}<template v-if="m.lines">:{{ m.lines }}</template></span>
        </div>
        <div v-if="m.current_logic" class="method-field">
          <span class="field-label">当前逻辑：</span>{{ m.current_logic }}
        </div>
        <div v-if="m.change_spec" class="method-field">
          <span class="field-label">变更规格：</span>{{ m.change_spec }}
        </div>
        <div v-if="m.pseudocode" class="method-field method-pseudocode">
          <span class="field-label">伪代码：</span>
          <pre>{{ m.pseudocode }}</pre>
        </div>
      </div>
    </div>

    <!-- ② Sequence Diagrams -->
    <div v-if="seqDiagrams.length > 0" class="section">
      <div class="section-header">
        <span class="section-icon">🔄</span>
        <span class="section-title">时序图</span>
        <el-tag size="small" type="info" round>{{ seqDiagrams.length }}个</el-tag>
      </div>
      <div v-for="(d, idx) in seqDiagrams" :key="`seq-${idx}`" class="diagram-card">
        <MermaidDiagram :source="d.mermaid ?? ''" :title="d.name" />
      </div>
    </div>

    <!-- ③ Flow Diagrams -->
    <div v-if="flowDiagrams.length > 0" class="section">
      <div class="section-header">
        <span class="section-icon">🔀</span>
        <span class="section-title">流程图</span>
        <el-tag size="small" type="info" round>{{ flowDiagrams.length }}个</el-tag>
      </div>
      <div v-for="(d, idx) in flowDiagrams" :key="`flow-${idx}`" class="diagram-card">
        <MermaidDiagram :source="d.mermaid ?? ''" :title="d.name" />
      </div>
    </div>

    <!-- ④ Test Scope -->
    <div v-if="testScope" class="section">
      <div class="section-header">
        <span class="section-icon">🧪</span>
        <span class="section-title">测试范围</span>
        <el-tag size="small" type="success" round>{{ totalTests }}项</el-tag>
      </div>
      <div v-if="testScope.unit_tests?.length" class="test-group">
        <div class="test-group-label">单元测试</div>
        <ul class="test-list">
          <li v-for="(t, idx) in testScope.unit_tests" :key="`unit-${idx}`">{{ t }}</li>
        </ul>
      </div>
      <div v-if="testScope.integration_tests?.length" class="test-group">
        <div class="test-group-label">集成测试</div>
        <ul class="test-list">
          <li v-for="(t, idx) in testScope.integration_tests" :key="`int-${idx}`">{{ t }}</li>
        </ul>
      </div>
      <div v-if="testScope.data_migration?.length" class="test-group">
        <div class="test-group-label">数据迁移</div>
        <ul class="test-list">
          <li v-for="(t, idx) in testScope.data_migration" :key="`dm-${idx}`">{{ t }}</li>
        </ul>
      </div>
      <div v-if="totalTests === 0" class="empty-hint">暂无测试范围定义</div>
    </div>

    <!-- ⑤ Risk Mitigations -->
    <div v-if="mitigations.length > 0" class="section">
      <div class="section-header">
        <span class="section-icon">🛡️</span>
        <span class="section-title">风险缓解</span>
        <el-tag size="small" type="warning" round>{{ mitigations.length }}项</el-tag>
      </div>
      <table class="risk-table">
        <thead>
          <tr><th>风险</th><th>缓解措施</th></tr>
        </thead>
        <tbody>
          <tr v-for="(rm, idx) in mitigations" :key="idx">
            <td class="col-risk">{{ rm.risk ?? '' }}</td>
            <td class="col-mitigation">{{ rm.mitigation ?? '' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ⑥ Reasoning (collapsible) -->
    <div v-if="hasReasoning" class="section">
      <el-collapse v-model="showReasoning">
        <el-collapse-item name="reasoning">
          <template #title>
            <div class="collapse-title">
              <span class="section-icon">💭</span>
              <span class="section-title">分析过程</span>
            </div>
          </template>
          <pre class="reasoning-text">{{ output.reasoning }}</pre>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- ⑦ Markdown Report (collapsible) -->
    <div v-if="hasMarkdownReport" class="section">
      <el-collapse v-model="showMarkdownReport">
        <el-collapse-item name="report">
          <template #title>
            <div class="collapse-title">
              <span class="section-icon">📊</span>
              <span class="section-title">格式化报告</span>
            </div>
          </template>
          <div class="markdown-body" v-html="renderedMarkdown"></div>
        </el-collapse-item>
      </el-collapse>
    </div>
  </div>
</template>

<style scoped>
.tech-plan-output {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ─── Sections ─── */
.section {
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 12px;
}

.section:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.section-icon {
  font-size: 16px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.section-desc {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
  line-height: 1.5;
}

/* ─── Method Detail Cards ─── */
.method-detail-card {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
}

.method-detail-card:last-child {
  margin-bottom: 0;
}

.method-detail-header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.method-name {
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 13px;
  background: #ecf5ff;
  padding: 2px 6px;
  border-radius: 3px;
  color: #409eff;
  font-weight: 600;
}

.method-file {
  font-size: 11px;
  color: #909399;
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
}

.method-field {
  font-size: 12px;
  color: #303133;
  line-height: 1.6;
  margin-top: 4px;
}

.field-label {
  font-weight: 500;
  color: #909399;
}

.method-pseudocode pre {
  font-size: 11px;
  background: #eef2f7;
  padding: 6px 8px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 4px 0 0;
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  line-height: 1.4;
}

/* ─── Diagram Cards ─── */
.diagram-card {
  margin-bottom: 12px;
}

.diagram-card:last-child {
  margin-bottom: 0;
}

/* ─── Test Scope ─── */
.test-group {
  margin-bottom: 8px;
}

.test-group:last-child {
  margin-bottom: 0;
}

.test-group-label {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 4px;
}

.test-list {
  margin: 0;
  padding-left: 20px;
  font-size: 12px;
  color: #303133;
  line-height: 1.8;
}

/* ─── Risk Table ─── */
.risk-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.risk-table th {
  text-align: left;
  font-weight: 500;
  color: #909399;
  border-bottom: 1px solid #ebeef5;
  padding: 4px 8px;
  font-size: 12px;
}

.risk-table td {
  padding: 6px 8px;
  border-bottom: 1px solid #f5f7fa;
  vertical-align: top;
}

.col-risk {
  color: #e6a23c;
  font-weight: 500;
  width: 40%;
}

.col-mitigation {
  color: #303133;
  width: 60%;
}

/* ─── Empty ─── */
.empty-hint {
  font-size: 13px;
  color: #c0c4cc;
  font-style: italic;
}

/* ─── Collapse overrides ─── */
.collapse-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

:deep(.el-collapse) {
  border: none;
}

:deep(.el-collapse-item__header) {
  border-bottom: none;
  height: 36px;
  line-height: 36px;
  background: transparent;
}

:deep(.el-collapse-item__wrap) {
  border-bottom: none;
  background: transparent;
}

:deep(.el-collapse-item__content) {
  padding-bottom: 0;
}

/* ─── Reasoning ─── */
.reasoning-text {
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
  background: #f5f7fa;
  padding: 10px 12px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-family: inherit;
}

/* ─── Markdown Report ─── */
.markdown-body {
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
}

.markdown-body :deep(h2) {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 12px;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
}

.markdown-body :deep(h3) {
  font-size: 14px;
  font-weight: 600;
  margin: 12px 0 8px;
}

.markdown-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 12px;
}

.markdown-body :deep(th) {
  text-align: left;
  background: #f5f7fa;
  padding: 6px 8px;
  border: 1px solid #ebeef5;
  font-weight: 500;
}

.markdown-body :deep(td) {
  padding: 6px 8px;
  border: 1px solid #ebeef5;
}

.markdown-body :deep(code) {
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  background: #f0f2f5;
  padding: 1px 4px;
  border-radius: 3px;
  color: #409eff;
}

.markdown-body :deep(ul) {
  padding-left: 20px;
}

.markdown-body :deep(li) {
  margin: 4px 0;
  line-height: 1.5;
}

.markdown-body :deep(strong) {
  font-weight: 600;
}
</style>
