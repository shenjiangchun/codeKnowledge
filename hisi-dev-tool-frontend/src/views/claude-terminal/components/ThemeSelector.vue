<template>
  <div class="theme-selector">
    <!-- 主题列表 -->
    <div class="theme-list">
      <div
        v-for="theme in presets"
        :key="theme.id"
        class="theme-item"
        :class="{ active: currentThemeId === theme.id }"
        @click="selectTheme(theme.id)"
      >
        <div class="theme-preview" :style="{ backgroundColor: theme.backgroundLevel1 }">
          <div class="preview-accent" :style="{ backgroundColor: theme.accentPrimary }"></div>
        </div>
        <span class="theme-name">{{ theme.name }}</span>
      </div>
    </div>

    <!-- 自定义颜色 -->
    <div class="custom-color">
      <span>自定义主色调</span>
      <el-color-picker v-model="customAccentColor" @change="onAccentChange" />
    </div>

    <!-- 操作按钮 -->
    <div class="theme-actions">
      <el-button size="small" @click="reset">重置</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useThemeStore } from '@/stores/themeStore'

const themeStore = useThemeStore()

// 预设主题列表
const presets = computed(() => themeStore.presets)

// 当前主题 ID
const currentThemeId = computed(() => themeStore.themeId)

// 自定义主色调
const customAccentColor = computed({
  get: () => themeStore.customAccent,
  set: (value) => themeStore.setCustomAccent(value)
})

// 选择主题
function selectTheme(id: string): void {
  themeStore.setTheme(id)
}

// 自定义颜色变化
function onAccentChange(color: string | null): void {
  themeStore.setCustomAccent(color)
}

// 重置为默认主题
function reset(): void {
  themeStore.resetToDefault()
}
</script>

<style scoped>
/* CSS 变量默认值 */
.theme-selector {
  --ct-bg-level-1: #1a1a1a;
  --ct-bg-level-2: #1e1e1e;
  --ct-bg-level-3: #252526;
  --ct-bg-level-4: #404040;
  --ct-text-primary: #e0e0e0;
  --ct-text-secondary: #909399;
  --ct-text-muted: #666666;
  --ct-accent-primary: #409eff;
  --ct-accent-success: #67c23a;
  --ct-accent-warning: #e6a23c;
  --ct-accent-danger: #f56c6c;
  --ct-text-on-accent: #ffffff;
  --ct-text-on-accent-secondary: rgba(255, 255, 255, 0.8);
  --ct-success-light-bg: rgba(103, 194, 58, 0.1);
  --ct-border-hover: #505050;
  --ct-success-text-on-accent: #a5d6a7;
}

.theme-selector {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  background: var(--ct-bg-level-3);
  border-radius: 8px;
}

.theme-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.theme-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
  background: var(--ct-bg-level-2);
  border: 1px solid var(--ct-bg-level-4);
}

.theme-item:hover {
  background: var(--ct-bg-level-3);
  border-color: var(--ct-border-hover);
}

.theme-item.active {
  background: var(--ct-accent-primary);
  border-color: var(--ct-accent-primary);
}

.theme-item.active .theme-name {
  color: var(--ct-text-on-accent);
}

.theme-preview {
  width: 32px;
  height: 24px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--ct-bg-level-4);
}

.preview-accent {
  width: 12px;
  height: 12px;
  border-radius: 2px;
}

.theme-name {
  font-size: 13px;
  color: var(--ct-text-primary);
}

.custom-color {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--ct-bg-level-2);
  border-radius: 6px;
  border: 1px solid var(--ct-bg-level-4);
}

.custom-color span {
  font-size: 13px;
  color: var(--ct-text-secondary);
}

.theme-actions {
  display: flex;
  justify-content: flex-end;
}

.theme-actions :deep(.el-button) {
  background: var(--ct-bg-level-2);
  border-color: var(--ct-bg-level-4);
  color: var(--ct-text-primary);
}

.theme-actions :deep(.el-button:hover) {
  background: var(--ct-bg-level-3);
  border-color: var(--ct-border-hover);
}
</style>