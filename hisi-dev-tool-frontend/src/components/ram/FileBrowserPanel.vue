<script setup lang="ts">
/**
 * FileBrowserPanel — searchable, group-able, multi-select impact file list.
 *
 * Bi-directional linkage with DagGraph via useRamStore: clicking a row
 * selects the file (which the graph highlights); the row matching
 * selectedFile / hoveredFile is visually emphasised. Supports CSV export
 * of the currently filtered set.
 */
import { computed, ref } from 'vue'
import { useRamStore } from '@/stores/ram'

type RingKey = 'involved' | 'modified' | 'impacted'

interface Props {
  involved: readonly string[]
  modified: readonly string[]
  impacted: readonly string[]
  riskScores?: Readonly<Record<string, number>>
  groupBy?: 'none' | 'ring' | 'package'
}

const props = withDefaults(defineProps<Props>(), {
  riskScores: () => ({}),
  groupBy: 'ring'
})

const store = useRamStore()
const search = ref('')
const selected = ref<Set<string>>(new Set())

interface Row { file: string; ring: RingKey; risk?: number }

const rows = computed<Row[]>(() => {
  const out: Row[] = []
  const push = (files: readonly string[], ring: RingKey) => {
    for (const f of files) out.push({ file: f, ring, risk: props.riskScores[f] })
  }
  push(props.involved, 'involved')
  push(props.modified, 'modified')
  push(props.impacted, 'impacted')
  const q = search.value.trim().toLowerCase()
  return q ? out.filter((r) => r.file.toLowerCase().includes(q)) : out
})

const grouped = computed<{ header: string; items: Row[] }[]>(() => {
  if (props.groupBy === 'none') return [{ header: '全部', items: rows.value }]
  if (props.groupBy === 'ring') {
    const buckets: Record<RingKey, Row[]> = { involved: [], modified: [], impacted: [] }
    for (const r of rows.value) buckets[r.ring].push(r)
    return [
      { header: `涉及 (${buckets.involved.length})`, items: buckets.involved },
      { header: `修改 (${buckets.modified.length})`, items: buckets.modified },
      { header: `影响 (${buckets.impacted.length})`, items: buckets.impacted }
    ]
  }
  const map = new Map<string, Row[]>()
  for (const r of rows.value) {
    const pkg = r.file.includes('/')
      ? r.file.slice(0, r.file.lastIndexOf('/'))
      : r.file.split('.').slice(0, -1).join('.')
    if (!map.has(pkg)) map.set(pkg, [])
    map.get(pkg)!.push(r)
  }
  return [...map.entries()].map(([header, items]) => ({ header, items }))
})

function toggleSelect(file: string): void {
  if (selected.value.has(file)) selected.value.delete(file)
  else selected.value.add(file)
  selected.value = new Set(selected.value)
}

function rowClick(file: string): void {
  store.selectFile(file)
}

function exportCsv(): void {
  const lines = ['file,ring,risk']
  for (const r of rows.value) lines.push(`${r.file},${r.ring},${r.risk ?? ''}`)
  const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'impact-files.csv'
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="file-browser">
    <div class="toolbar">
      <input v-model="search" class="search" placeholder="搜索文件 / 包名…" />
      <button type="button" @click="exportCsv">导出 CSV</button>
    </div>
    <div class="groups">
      <div v-for="g in grouped" :key="g.header" class="file-group">
        <div class="file-group-header">{{ g.header }}</div>
        <div
          v-for="r in g.items"
          :key="`${r.ring}-${r.file}`"
          class="file-row"
          :class="{
            'is-selected': store.selectedFile === r.file,
            'is-hovered': store.hoveredFile === r.file,
            'is-checked': selected.has(r.file)
          }"
          :data-file="r.file"
          :data-ring="r.ring"
          @click="rowClick(r.file)"
          @mouseenter="store.hoverFile(r.file)"
          @mouseleave="store.hoverFile(null)"
        >
          <input type="checkbox" :checked="selected.has(r.file)" @click.stop="toggleSelect(r.file)" />
          <span class="file">{{ r.file }}</span>
          <span v-if="typeof r.risk === 'number'" class="risk-tag">{{ r.risk.toFixed(2) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.file-browser { display: flex; flex-direction: column; height: 100%; }
.toolbar { display: flex; gap: 8px; padding: 8px; border-bottom: 1px solid #ebeef5; }
.search { flex: 1; padding: 4px 8px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; }
.groups { flex: 1; overflow: auto; }
.file-group-header { padding: 6px 10px; font-weight: 600; font-size: 12px; color: #909399; background: #fafafa; }
.file-row { display: flex; align-items: center; gap: 6px; padding: 4px 10px; font-size: 12px; cursor: pointer; font-family: ui-monospace, monospace; }
.file-row:hover, .file-row.is-hovered { background: #ecf5ff; }
.file-row.is-selected { background: #d9ecff; }
.file-row.is-checked { font-weight: 600; }
.file { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.risk-tag { font-size: 11px; color: #f56c6c; }
</style>
