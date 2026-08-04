<template>
  <div class="tree-node" v-if="shouldShow" :style="nodeStyle">
    <div
      class="tree-row"
      :class="{ 'is-external': node.isNoMatch }"
      @click="handleSelect"
      @contextmenu.prevent="handleContextMenu"
    >
      <div
        class="tree-toggle"
        :class="{ 'has-children': hasVisibleChildren }"
        @click.stop="toggleExpand"
      >
        {{ hasVisibleChildren ? (expanded ? '▼' : '▶') : '•' }}
      </div>
      <div class="tree-depth" :style="depthStyle">{{ node.depth !== undefined ? node.depth : level }}</div>
      <div class="tree-info">
        <div class="tree-name" v-html="highlightedName"></div>
        <div class="tree-class" v-if="node.className">{{ node.className }}</div>
        <div class="tree-description" v-if="node.description">{{ node.description }}</div>
      </div>
      <span v-if="node.isNoMatch" class="external-tag">外部</span>
    </div>
    <div v-if="hasVisibleChildren && expanded" class="tree-children">
      <TreeNode
        v-for="child in visibleChildren"
        :key="child.id || child.name + '_' + (child.className || '')"
        :node="child"
        :level="level + 1"
        :search="search"
        :hide-external="hideExternal"
        @select="$emit('select', $event)"
        @contextmenu="$emit('contextmenu', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { escapeHtml, escapeRegExp } from '@/utils/markdown'

interface ChainNode {
  name: string
  className?: string
  methodSignature?: string
  methodBody?: string
  description?: string
  isNoMatch?: boolean
  children?: ChainNode[]
  id?: string
  depth?: number
}

const props = defineProps<{
  node: ChainNode
  level: number
  search?: string
  hideExternal?: boolean
}>()

const emit = defineEmits<{
  (e: 'select', node: ChainNode): void
  (e: 'contextmenu', payload: { event: MouseEvent; node: ChainNode }): void
}>()

const expanded = ref(true)  // 默认全部展开

const shouldShow = computed(() => {
  if (props.hideExternal && props.node.isNoMatch) {
    return false
  }
  return true
})

// 计算当前节点的实际深度
const currentDepth = computed(() => {
  return props.node.depth !== undefined ? props.node.depth : props.level
})

// 根据深度计算缩进样式，使用对数缩放避免过深
const nodeStyle = computed(() => {
  const depth = currentDepth.value
  // 使用较小的缩进，每层16px，最多缩进128px
  const indent = Math.min(depth * 16, 128)
  return {
    marginLeft: `${indent}px`
  }
})

// 根据深度设置不同颜色的深度标签
const depthStyle = computed(() => {
  const depth = currentDepth.value
  // 深度越大颜色越深
  const colors = [
    '#409eff', // 0 - 蓝色
    '#67c23a', // 1 - 绿色
    '#e6a23c', // 2 - 橙色
    '#f56c6c', // 3 - 红色
    '#909399', // 4 - 灰色
    '#b37feb', // 5 - 紫色
    '#ff85c0', // 6 - 粉色
    '#36cfc9', // 7 - 青色
  ]
  const bgColor = colors[Math.min(depth, colors.length - 1)]
  return { backgroundColor: bgColor }
})

const visibleChildren = computed(() => {
  if (!props.node.children) return []
  if (props.hideExternal) {
    return props.node.children.filter(c => !c.isNoMatch)
  }
  return props.node.children
})

const hasVisibleChildren = computed(() => visibleChildren.value.length > 0)

const highlightedName = computed(() => {
  if (!props.search) return escapeHtml(props.node.name)
  const escapedSearch = escapeRegExp(props.search)
  const escapedName = escapeHtml(props.node.name)
  return escapedName.replace(
    new RegExp(`(${escapedSearch})`, 'gi'),
    '<mark>$1</mark>'
  )
})

const toggleExpand = () => {
  expanded.value = !expanded.value
}

const handleSelect = () => {
  emit('select', props.node)
}

const handleContextMenu = (event: MouseEvent) => {
  emit('contextmenu', { event, node: props.node })
}
</script>

<style scoped>
.tree-node {
  margin-bottom: 4px;
}

.tree-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
}

.tree-row:hover {
  background: #e8f4ff;
  border-color: #409eff;
}

.tree-row.is-external {
  background: #fffbe6;
  border-color: #f5d44d;
}

.tree-toggle {
  width: 18px;
  text-align: center;
  font-size: 10px;
  color: #909399;
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
}

.tree-toggle.has-children {
  color: #409eff;
}

.tree-depth {
  min-width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  flex-shrink: 0;
}

.tree-info {
  flex: 1;
  min-width: 0;
}

.tree-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.tree-name :deep(mark) {
  background: #ffe066;
  padding: 0 2px;
  border-radius: 2px;
}

.tree-class {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-description {
  font-size: 12px;
  color: #606266;
  margin-top: 3px;
  padding: 2px 6px;
  background: #f0f7ff;
  border-left: 2px solid #409eff;
  border-radius: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.external-tag {
  font-size: 11px;
  padding: 2px 8px;
  background: #f0a020;
  color: #fff;
  border-radius: 4px;
  flex-shrink: 0;
}

.tree-children {
  margin-top: 4px;
  padding-left: 8px;
  border-left: 2px solid #dcdfe6;
}
</style>