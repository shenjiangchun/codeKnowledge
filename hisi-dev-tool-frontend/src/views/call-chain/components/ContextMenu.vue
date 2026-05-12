<template>
  <teleport to="body">
    <div
      v-if="visible"
      class="context-menu"
      :style="{ left: x + 'px', top: y + 'px' }"
      @click.stop
    >
      <div class="menu-item" @click="handleAction('upstream')">
        <el-icon><Top /></el-icon>
        查看上游调用链
      </div>
      <div class="menu-item" @click="handleAction('downstream')">
        <el-icon><Bottom /></el-icon>
        查看下游调用链
      </div>
      <div class="menu-divider"></div>
      <div class="menu-item" @click="handleAction('copy')">
        <el-icon><DocumentCopy /></el-icon>
        复制方法签名
      </div>
      <div class="menu-divider"></div>
      <div class="menu-item" @click="handleAction('openInMethodRef')">
        <el-icon><Link /></el-icon>
        在方法引用分析中打开
      </div>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { Top, Bottom, DocumentCopy, Link } from '@element-plus/icons-vue'

interface MenuNode {
  name: string
  className?: string
  methodSignature?: string
}

const props = defineProps<{
  visible: boolean
  x: number
  y: number
  node: MenuNode | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'action', action: string, node: MenuNode): void
}>()

const handleAction = (action: string) => {
  if (props.node) {
    emit('action', action, props.node)
  }
  emit('close')
}

const handleClickOutside = () => {
  emit('close')
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<style scoped>
.context-menu {
  position: fixed;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  z-index: 3000;
  min-width: 180px;
  padding: 4px 0;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  cursor: pointer;
  font-size: 14px;
}

.menu-item:hover {
  background: #f5f7fa;
}

.menu-divider {
  height: 1px;
  background: #e4e7ed;
  margin: 4px 0;
}
</style>