<script setup lang="ts">
import { Document, Folder, Monitor, Search, Shop, DataAnalysis, Setting, Cpu, MagicStick, Connection, User } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import type { Component } from 'vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'

// Define menu key type that matches availableMenus keys
type MenuKey = 'log-analysis' | 'project-management' | 'claude-terminal' | 'prompt-config' | 'search' | 'skill-market' | 'knowledge-graph' | 'settings' | 'apm-debug' | 'ram' | 'merge-analysis'

interface MenuItem {
  index: string
  title: string
  icon: Component
  menuKey: MenuKey
  children?: { index: string; title: string }[]
}

const route = useRoute()
const appStore = useAppStore()
const authStore = useAuthStore()

const baseMenuItems: MenuItem[] = [
  {
    index: '/skill-market',
    title: '技能市场',
    icon: Shop,
    menuKey: 'skill-market'
  },
  // 自然语言诊断已移除
  {
    index: '/claude-terminal',
    title: 'Claude 终端',
    icon: Monitor,
    menuKey: 'claude-terminal'
  },
  {
    index: '/apm-debug',
    title: 'APM 调试',
    icon: Cpu,
    menuKey: 'apm-debug' as MenuKey
  },
  {
    index: '/search',
    title: '增强检索',
    icon: Search,
    menuKey: 'search'
  },
  {
    index: '/log-analysis',
    title: '日志分析',
    icon: Document,
    menuKey: 'log-analysis'
  },
  {
    index: '/knowledge-graph',
    title: '知识图谱',
    icon: DataAnalysis,
    menuKey: 'knowledge-graph',
    children: [
      { index: '/knowledge-graph', title: '图谱总览' },
      { index: '/prompt-config', title: '提示词配置' },
      { index: '/glossary', title: '术语管理' }
    ]
  },
  {
    index: '/ram',
    title: '需求分析大师',
    icon: MagicStick,
    menuKey: 'ram'
  },
  {
    index: '/merge-analysis',
    title: '合入分析',
    icon: Connection,
    menuKey: 'merge-analysis'
  },
  {
    index: '/project',
    title: '项目管理',
    icon: Folder,
    menuKey: 'project-management'
  },
  {
    index: '/settings',
    title: '系统设置',
    icon: Setting,
    menuKey: 'settings'
  },
]

const menuItems = computed(() => {
  const items = baseMenuItems.map(item => ({
    ...item,
    disabled: !appStore.availableMenus[item.menuKey]
  }))
  // Add admin-only menu items
  if (authStore.isAdmin) {
    items.splice(items.length - 1, 0, {
      index: '/admin/users',
      title: '用户管理',
      icon: User,
      menuKey: 'settings' as MenuKey,
      disabled: false
    })
  }
  return items
})

// Check if a submenu should be opened based on current route
const defaultOpeneds = computed(() => {
  const openeds: string[] = []
  menuItems.value.forEach(item => {
    if (item.children && item.children.some(child => route.path.startsWith(child.index))) {
      openeds.push(item.index)
    }
  })
  return openeds
})
</script>

<template>
  <el-aside class="app-sidebar" width="220px">
    <el-menu
      :default-active="route.path"
      :default-openeds="defaultOpeneds"
      class="sidebar-menu"
      router
    >
      <template v-for="item in menuItems" :key="item.index">
        <!-- Submenu for items with children -->
        <el-sub-menu v-if="item.children && !item.disabled" :index="item.index">
          <template #title>
            <el-icon>
              <component :is="item.icon" />
            </el-icon>
            <span>{{ item.title }}</span>
          </template>
          <el-menu-item
            v-for="child in item.children"
            :key="child.index"
            :index="child.index"
          >
            {{ child.title }}
          </el-menu-item>
        </el-sub-menu>
        <!-- Regular menu item for items without children -->
        <el-menu-item
          v-else
          :index="item.index"
          :disabled="item.disabled"
        >
          <el-icon>
            <component :is="item.icon" />
          </el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </template>
    </el-menu>
  </el-aside>
</template>

<style scoped>
.app-sidebar {
  background-color: #304156;
  height: 100%;
  overflow: hidden;
}

.sidebar-menu {
  border-right: none;
  background-color: #304156;
  height: 100%;
  width: 100% !important;
}

.sidebar-menu :deep(.el-menu-item),
.sidebar-menu :deep(.el-sub-menu__title) {
  color: #bfcbd9;
  background-color: #304156;
  height: 56px;
  line-height: 56px;
}

.sidebar-menu :deep(.el-menu-item:hover),
.sidebar-menu :deep(.el-sub-menu__title:hover) {
  background-color: #263445;
  color: #fff;
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  color: #409eff;
  background-color: #263445;
}

.sidebar-menu :deep(.el-menu-item .el-icon),
.sidebar-menu :deep(.el-sub-menu__title .el-icon) {
  color: inherit;
}

.sidebar-menu :deep(.el-menu-item.is-disabled) {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Submenu items styling */
.sidebar-menu :deep(.el-sub-menu .el-menu-item) {
  background-color: #1f2d3d;
  min-width: auto;
  padding-left: 50px !important;
}

.sidebar-menu :deep(.el-sub-menu .el-menu-item:hover) {
  background-color: #263445;
}
</style>