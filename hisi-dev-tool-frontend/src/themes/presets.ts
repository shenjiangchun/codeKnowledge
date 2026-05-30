/**
 * 预设主题配置
 *
 * 包含6种预设主题：3种深色主题、2种浅色主题、1种护眼主题
 */

import type { TerminalTheme } from './types'

/**
 * 创建 xterm.js 终端颜色配置
 */
function createTerminalColors(
  background: string,
  foreground: string,
  cursor: string,
  accentPrimary: string
): TerminalTheme['terminal'] {
  return {
    background,
    foreground,
    cursor,
    cursorAccent: foreground,
    selectionBackground: `${accentPrimary}40`,
    black: '#000000',
    red: '#f56c6c',
    green: '#67c23a',
    yellow: '#e6a23c',
    blue: '#409eff',
    magenta: '#f92672',
    cyan: '#00d4ff',
    white: '#ffffff',
    brightBlack: '#808080',
    brightRed: '#ff6b6b',
    brightGreen: '#90ee90',
    brightYellow: '#ffd700',
    brightBlue: '#6bb3ff',
    brightMagenta: '#ff79c6',
    brightCyan: '#00ffff',
    brightWhite: '#ffffff'
  }
}

/**
 * 深色科技主题
 * 主色调: 蓝色 (#409eff)
 */
export const DARK_TECH: TerminalTheme = {
  id: 'dark-tech',
  name: '深色科技',
  isDark: true,
  backgroundLevel1: '#1a1a1a',
  backgroundLevel2: '#252525',
  backgroundLevel3: '#303030',
  backgroundLevel4: '#404040',
  textPrimary: '#ffffff',
  textSecondary: '#a0a0a0',
  textMuted: '#606060',
  accentPrimary: '#409eff',
  accentSuccess: '#67c23a',
  accentWarning: '#e6a23c',
  accentDanger: '#f56c6c',
  textOnAccent: '#ffffff',
  textOnAccentSecondary: 'rgba(255, 255, 255, 0.8)',
  successLightBg: 'rgba(103, 194, 58, 0.1)',
  borderHover: '#505050',
  successTextOnAccent: '#a5d6a7',
  terminal: createTerminalColors('#1a1a1a', '#ffffff', '#409eff', '#409eff')
}

/**
 * Monokai 经典主题
 * 主色调: 粉红 (#f92672)
 */
export const DARK_MONOKAI: TerminalTheme = {
  id: 'dark-monokai',
  name: 'Monokai 经典',
  isDark: true,
  backgroundLevel1: '#1e1e1e',
  backgroundLevel2: '#272822',
  backgroundLevel3: '#3e3d32',
  backgroundLevel4: '#49483e',
  textPrimary: '#f8f8f2',
  textSecondary: '#a6a69b',
  textMuted: '#75715e',
  accentPrimary: '#f92672',
  accentSuccess: '#a6e22e',
  accentWarning: '#e6db74',
  accentDanger: '#f92672',
  textOnAccent: '#f8f8f2',
  textOnAccentSecondary: 'rgba(248, 248, 242, 0.8)',
  successLightBg: 'rgba(166, 226, 46, 0.1)',
  borderHover: '#5a5a50',
  successTextOnAccent: '#d4e89e',
  terminal: {
    background: '#1e1e1e',
    foreground: '#f8f8f2',
    cursor: '#f92672',
    cursorAccent: '#f8f8f2',
    selectionBackground: '#f9267240',
    black: '#000000',
    red: '#f92672',
    green: '#a6e22e',
    yellow: '#e6db74',
    blue: '#66d9ef',
    magenta: '#f92672',
    cyan: '#66d9ef',
    white: '#f8f8f2',
    brightBlack: '#75715e',
    brightRed: '#f92672',
    brightGreen: '#a6e22e',
    brightYellow: '#e6db74',
    brightBlue: '#66d9ef',
    brightMagenta: '#f92672',
    brightCyan: '#66d9ef',
    brightWhite: '#f8f8f2'
  }
}

/**
 * Dracula 主题
 * 主色调: 紫色 (#bd93f9)
 */
export const DARK_DRACULA: TerminalTheme = {
  id: 'dark-dracula',
  name: 'Dracula',
  isDark: true,
  backgroundLevel1: '#282a36',
  backgroundLevel2: '#2d2f3f',
  backgroundLevel3: '#3b3e50',
  backgroundLevel4: '#44475a',
  textPrimary: '#f8f8f2',
  textSecondary: '#b0b0b0',
  textMuted: '#6272a4',
  accentPrimary: '#bd93f9',
  accentSuccess: '#50fa7b',
  accentWarning: '#ffb86c',
  accentDanger: '#ff5555',
  textOnAccent: '#f8f8f2',
  textOnAccentSecondary: 'rgba(248, 248, 242, 0.8)',
  successLightBg: 'rgba(80, 250, 123, 0.1)',
  borderHover: '#6272a4',
  successTextOnAccent: '#94faab',
  terminal: {
    background: '#282a36',
    foreground: '#f8f8f2',
    cursor: '#bd93f9',
    cursorAccent: '#f8f8f2',
    selectionBackground: '#bd93f940',
    black: '#000000',
    red: '#ff5555',
    green: '#50fa7b',
    yellow: '#f1fa8c',
    blue: '#bd93f9',
    magenta: '#ff79c6',
    cyan: '#8be9fd',
    white: '#f8f8f2',
    brightBlack: '#6272a4',
    brightRed: '#ff6e6e',
    brightGreen: '#69ff94',
    brightYellow: '#ffffa5',
    brightBlue: '#d6acff',
    brightMagenta: '#ff92df',
    brightCyan: '#a4ffff',
    brightWhite: '#ffffff'
  }
}

/**
 * 浅色简约主题
 * 主色调: 蓝色 (#409eff)
 */
export const LIGHT_MINIMAL: TerminalTheme = {
  id: 'light-minimal',
  name: '浅色简约',
  isDark: false,
  backgroundLevel1: '#f5f5f5',
  backgroundLevel2: '#ffffff',
  backgroundLevel3: '#fafafa',
  backgroundLevel4: '#e0e0e0',
  textPrimary: '#303133',
  textSecondary: '#606266',
  textMuted: '#909399',
  accentPrimary: '#409eff',
  accentSuccess: '#67c23a',
  accentWarning: '#e6a23c',
  accentDanger: '#f56c6c',
  textOnAccent: '#ffffff',
  textOnAccentSecondary: 'rgba(255, 255, 255, 0.8)',
  successLightBg: 'rgba(103, 194, 58, 0.15)',
  borderHover: '#c0c0c0',
  successTextOnAccent: '#a5d6a7',
  terminal: createTerminalColors('#ffffff', '#303133', '#409eff', '#409eff')
}

/**
 * 护眼暖色主题
 * 主色调: 橙色 (#d48806)
 */
export const LIGHT_SEPIA: TerminalTheme = {
  id: 'light-sepia',
  name: '护眼暖色',
  isDark: false,
  backgroundLevel1: '#f8f4e8',
  backgroundLevel2: '#fffbf0',
  backgroundLevel3: '#f5f0e0',
  backgroundLevel4: '#e8dcc8',
  textPrimary: '#3e3e3e',
  textSecondary: '#5a5a5a',
  textMuted: '#8a8a8a',
  accentPrimary: '#d48806',
  accentSuccess: '#52c41a',
  accentWarning: '#fa8c16',
  accentDanger: '#f5222d',
  textOnAccent: '#ffffff',
  textOnAccentSecondary: 'rgba(255, 255, 255, 0.8)',
  successLightBg: 'rgba(82, 196, 26, 0.15)',
  borderHover: '#d0c8b8',
  successTextOnAccent: '#95de64',
  terminal: createTerminalColors('#fffbf0', '#3e3e3e', '#d48806', '#d48806')
}

/**
 * 护眼绿色主题
 * 主色调: 绿色 (#67c23a)
 */
export const EYE_CARE: TerminalTheme = {
  id: 'eye-care',
  name: '护眼绿色',
  isDark: false,
  backgroundLevel1: '#e8f5e9',
  backgroundLevel2: '#f5fbf5',
  backgroundLevel3: '#e0f0e0',
  backgroundLevel4: '#c8e6c9',
  textPrimary: '#2e3e2e',
  textSecondary: '#4a5a4a',
  textMuted: '#7a8a7a',
  accentPrimary: '#67c23a',
  accentSuccess: '#52c41a',
  accentWarning: '#faad14',
  accentDanger: '#ff4d4f',
  textOnAccent: '#ffffff',
  textOnAccentSecondary: 'rgba(255, 255, 255, 0.8)',
  successLightBg: 'rgba(82, 196, 26, 0.2)',
  borderHover: '#a8d8a9',
  successTextOnAccent: '#b7eb8f',
  terminal: createTerminalColors('#f5fbf5', '#2e3e2e', '#67c23a', '#67c23a')
}

/**
 * 所有预设主题列表
 */
export const THEME_PRESETS: TerminalTheme[] = [
  DARK_TECH,
  DARK_MONOKAI,
  DARK_DRACULA,
  LIGHT_MINIMAL,
  LIGHT_SEPIA,
  EYE_CARE
]

/**
 * 默认主题
 */
export const DEFAULT_THEME = DARK_TECH

/**
 * 根据主题 ID 获取主题配置
 * @param id 主题 ID
 * @returns 主题配置，不存在则返回 undefined
 */
export function getThemeById(id: string): TerminalTheme | undefined {
  return THEME_PRESETS.find(theme => theme.id === id)
}