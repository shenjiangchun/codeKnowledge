<script setup lang="ts">
import { Document, Folder, Monitor, Shop, DataAnalysis, Setting, Cpu, MagicStick, Connection, User, Tools, ChatDotRound } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { computed, reactive, watch } from 'vue'
import type { Component } from 'vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'

// Define menu key type that matches availableMenus keys
type MenuKey = 'log-analysis' | 'project-management' | 'claude-terminal' | 'prompt-config' | 'skill-market' | 'kg-skills-kit' | 'knowledge-graph' | 'settings' | 'apm-debug' | 'ram' | 'ram-demand' | 'ram-chat' | 'merge-analysis'

interface MenuItem {
  index: string
  title: string
  icon: Component
  menuKey: MenuKey
  groupLabel?: string
  children?: { index: string; title: string }[]
}

const route = useRoute()
const appStore = useAppStore()
const authStore = useAuthStore()

const baseMenuItems: MenuItem[] = [
  {
    index: '/project',
    title: '项目管理',
    icon: Folder,
    menuKey: 'project-management',
    groupLabel: '分析工具'
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
    index: '/log-analysis',
    title: '日志分析',
    icon: Document,
    menuKey: 'log-analysis'
  },
  {
    index: '/apm-debug',
    title: 'APM 调试',
    icon: Cpu,
    menuKey: 'apm-debug' as MenuKey
  },
  {
    index: '/ram',
    title: '需求分析大师',
    icon: MagicStick,
    menuKey: 'ram-demand',
    groupLabel: 'AI Agent'
  },
  {
    index: '/ram/chat',
    title: 'RAM 对话',
    icon: ChatDotRound,
    menuKey: 'ram-chat' as MenuKey
  },
  {
    index: '/fix/chat',
    title: '异常修复',
    icon: Setting,
    menuKey: 'log-analysis' as MenuKey
  },
  {
    index: '/merge-analysis',
    title: '合入分析',
    icon: Connection,
    menuKey: 'merge-analysis'
  },
  {
    index: '/claude-terminal',
    title: 'Claude 终端',
    icon: Monitor,
    menuKey: 'claude-terminal',
    groupLabel: '工具 & 市场'
  },
  {
    index: '/skill-market',
    title: '技能市场',
    icon: Shop,
    menuKey: 'skill-market'
  },
  {
    index: '/kg-skills-kit',
    title: 'KG Skills 套件',
    icon: Tools,
    menuKey: 'kg-skills-kit' as MenuKey
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

// Reactive submenu open state — initialized from current route
const openedSubmenus = reactive(new Set<string>())

function updateOpenSubmenus() {
  menuItems.value.forEach(item => {
    if (item.children?.some(child => route.path.startsWith(child.index))) {
      openedSubmenus.add(item.index)
    }
  })
}
updateOpenSubmenus()
watch(() => route.path, updateOpenSubmenus)

function toggleSubmenu(index: string) {
  if (openedSubmenus.has(index)) {
    openedSubmenus.delete(index)
  } else {
    openedSubmenus.add(index)
  }
}

function isActive(item: MenuItem): boolean {
  if (item.children) {
    return item.children.some(child => route.path.startsWith(child.index))
  }
  return route.path.startsWith(item.index) || route.path === item.index
}
</script>

<template>
  <aside class="app-sidebar">
    <div class="sidebar-brand">HiSi DevTool</div>
    <nav class="sidebar-nav">
      <template v-for="item in menuItems" :key="item.index">
        <!-- Group label -->
        <div v-if="item.groupLabel" class="nav-group-label">
          {{ item.groupLabel }}
        </div>

        <!-- Menu item with children (submenu) -->
        <template v-if="item.children && !item.disabled">
          <div
            class="nav-item"
            :class="{ active: isActive(item), opened: openedSubmenus.has(item.index) }"
            @click="toggleSubmenu(item.index)"
            @keydown.enter="toggleSubmenu(item.index)"
            @keydown.space.prevent="toggleSubmenu(item.index)"
            tabindex="0"
            role="button"
            :aria-expanded="openedSubmenus.has(item.index)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
            <svg
              class="nav-chevron"
              :class="{ rotated: openedSubmenus.has(item.index) }"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              width="14"
              height="14"
            >
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </div>
          <Transition name="submenu-slide">
            <div v-show="openedSubmenus.has(item.index)" class="sub-nav">
              <router-link
                v-for="child in item.children"
                :key="child.index"
                :to="child.index"
                class="nav-item sub-item"
                :class="{ active: route.path === child.index }"
              >
                {{ child.title }}
              </router-link>
            </div>
          </Transition>
        </template>

        <!-- Regular menu item (no children) -->
        <router-link
          v-else
          :to="item.index"
          class="nav-item"
          :class="{
            active: isActive(item),
            disabled: item.disabled
          }"
          :tabindex="item.disabled ? -1 : 0"
          @click.prevent="item.disabled ? undefined : undefined"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </router-link>
      </template>
    </nav>
    <div class="sidebar-footer">
      <router-link to="/settings" class="footer-item">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" width="16" height="16">
          <circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z"/>
        </svg>
        系统设置
      </router-link>
    </div>
  </aside>
</template>

<style scoped>
.app-sidebar {
  width: 220px;
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  overflow: hidden;
}

.sidebar-brand {
  height: 48px;
  display: flex;
  align-items: center;
  padding: 0 18px;
  font-size: 15px;
  font-weight: 700;
  color: var(--color-text-primary);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  padding: 8px 10px;
}

.nav-group-label {
  padding: 14px 10px 6px;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--color-text-muted);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: var(--radius-md);
  font-size: 13.5px;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition:
    background-color 0.12s,
    color 0.12s;
  text-decoration: none;
  font-weight: 450;
  margin: 1px 0;
  min-width: 0;
  white-space: nowrap;
}

.nav-item span {
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-item:hover:not(.disabled) {
  background: var(--color-hover);
  color: var(--color-text-primary);
}

.nav-item.active {
  background: var(--color-accent-light);
  color: var(--color-accent);
  font-weight: 550;
}

.nav-item.disabled {
  opacity: 0.4;
  pointer-events: none;
  cursor: not-allowed;
}

.nav-item:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: -2px;
  border-radius: var(--radius-md);
}

.nav-item :deep(.el-icon) {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
}

.nav-chevron {
  margin-left: auto;
  transition: transform 0.15s ease;
  flex-shrink: 0;
  color: var(--color-text-muted);
}

.nav-chevron.rotated {
  transform: rotate(180deg);
}

/* Sub-navigation */
.sub-nav {
  padding-left: 16px;
  overflow: hidden;
}

.sub-item {
  font-size: 13px;
  padding: 7px 12px;
  padding-left: 20px;
}

/* Transition */
.submenu-slide-enter-active,
.submenu-slide-leave-active {
  transition: all 0.15s ease;
}

.submenu-slide-enter-from,
.submenu-slide-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* Footer */
.sidebar-footer {
  border-top: 1px solid var(--color-border);
  padding: 8px 10px;
  flex-shrink: 0;
}

.footer-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  font-size: 13px;
  color: var(--color-text-muted);
  text-decoration: none;
  cursor: pointer;
  transition:
    background-color 0.12s,
    color 0.12s;
}

.footer-item:hover {
  background: var(--color-hover);
  color: var(--color-text-secondary);
}
</style>
