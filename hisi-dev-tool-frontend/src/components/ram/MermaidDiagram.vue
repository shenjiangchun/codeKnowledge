<script setup lang="ts">
/**
 * MermaidDiagram — Renders a Mermaid.js diagram string into SVG.
 *
 * Uses mermaid.render() to produce SVG from a Mermaid DSL string.
 * Re-renders when the `source` prop changes. Falls back to an error
 * placeholder on parse failure.
 */
import { ref, watch, onMounted } from 'vue'
import mermaid from 'mermaid'

mermaid.initialize({
  startOnLoad: false,
  theme: 'default',
  securityLevel: 'loose',
  fontFamily: 'inherit'
})

const props = defineProps<{
  source: string
  title?: string
}>()

const containerId = `mermaid-${Math.random().toString(36).slice(2, 9)}`
const svgHtml = ref('')
const error = ref('')

let renderCount = 0

async function renderDiagram() {
  if (!props.source?.trim()) {
    svgHtml.value = ''
    error.value = ''
    return
  }

  try {
    renderCount += 1
    const id = `${containerId}-${renderCount}`
    const { svg } = await mermaid.render(id, props.source)
    svgHtml.value = svg
    error.value = ''
  } catch (e: unknown) {
    const errorId = `${containerId}-${renderCount}`
    error.value = e instanceof Error ? e.message : '图表渲染失败'
    svgHtml.value = ''
    // Mermaid leaves a broken DOM element on failure; clean it up
    const broken = document.getElementById(errorId)
    if (broken) broken.remove()
  }
}

onMounted(renderDiagram)
watch(() => props.source, renderDiagram)
</script>

<template>
  <div class="mermaid-diagram">
    <div v-if="title" class="mermaid-title">{{ title }}</div>
    <div v-if="error" class="mermaid-error">
      <span>图表解析失败：{{ error }}</span>
    </div>
    <div v-else class="mermaid-svg" v-html="svgHtml" />
  </div>
</template>

<style scoped>
.mermaid-diagram {
  background: #fafbfc;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px;
  overflow-x: auto;
}

.mermaid-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.mermaid-svg {
  display: flex;
  justify-content: center;
}

.mermaid-svg :deep(svg) {
  max-width: 100%;
  height: auto;
}

.mermaid-error {
  font-size: 12px;
  color: #f56c6c;
  padding: 8px;
  background: #fef0f0;
  border-radius: 4px;
}
</style>
