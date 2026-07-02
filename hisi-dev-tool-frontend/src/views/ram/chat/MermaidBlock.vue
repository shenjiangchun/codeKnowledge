<script setup lang="ts">
/**
 * MermaidBlock — Renders a Mermaid code block extracted from assistant text.
 *
 * On parse failure, falls back to the original source in a <pre> block
 * plus a small notice, so a single bad diagram cannot break the whole
 * message rendering.
 */
import { ref, watch, onMounted } from 'vue'
import mermaid from 'mermaid'

mermaid.initialize({
  startOnLoad: false,
  theme: 'default',
  securityLevel: 'loose',
  fontFamily: 'inherit'
})

const props = defineProps<{ source: string }>()

const containerId = `mermaid-chat-${Math.random().toString(36).slice(2, 9)}`
const svgHtml = ref('')
const failed = ref(false)

let renderCount = 0

async function renderDiagram(): Promise<void> {
  const code = props.source?.trim() ?? ''
  if (!code) {
    svgHtml.value = ''
    failed.value = false
    return
  }
  try {
    renderCount += 1
    const id = `${containerId}-${renderCount}`
    const { svg } = await mermaid.render(id, code)
    svgHtml.value = svg
    failed.value = false
  } catch (_e: unknown) {
    failed.value = true
    svgHtml.value = ''
    // Mermaid leaves an orphan DOM element on failure; clean it up
    const orphan = document.getElementById(`${containerId}-${renderCount}`)
    if (orphan) orphan.remove()
  }
}

onMounted(renderDiagram)
watch(() => props.source, renderDiagram)
</script>

<template>
  <div class="mermaid-block">
    <template v-if="failed">
      <pre class="mermaid-fallback">{{ props.source }}</pre>
      <div class="mermaid-fallback-note">Mermaid 语法错误</div>
    </template>
    <div v-else class="mermaid-svg" v-html="svgHtml" />
  </div>
</template>

<style scoped>
.mermaid-block {
  margin: 8px 0;
  background: #fafbfc;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px;
  overflow-x: auto;
}
.mermaid-svg {
  display: flex;
  justify-content: center;
}
.mermaid-svg :deep(svg) {
  max-width: 100%;
  height: auto;
}
.mermaid-fallback {
  background: #f5f5f5;
  color: #303133;
  padding: 8px;
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}
.mermaid-fallback-note {
  margin-top: 4px;
  font-size: 12px;
  color: #f56c6c;
}
</style>
