/**
 * 终端主题类型定义
 *
 * 定义主题相关的接口、类型和常量
 */

/**
 * xterm.js 终端主题配置
 */
export interface TerminalColors {
  background: string
  foreground: string
  cursor: string
  cursorAccent: string
  selectionBackground: string
  black: string
  red: string
  green: string
  yellow: string
  blue: string
  magenta: string
  cyan: string
  white: string
  brightBlack: string
  brightRed: string
  brightGreen: string
  brightYellow: string
  brightBlue: string
  brightMagenta: string
  brightCyan: string
  brightWhite: string
}

/**
 * 主题颜色配置
 */
export interface TerminalThemeColors {
  /** 页面背景色 */
  backgroundLevel1: string
  /** 卡片/面板背景色 */
  backgroundLevel2: string
  /** 子区域背景色 */
  backgroundLevel3: string
  /** 边框/分隔线颜色 */
  backgroundLevel4: string
  /** 主要文字颜色 */
  textPrimary: string
  /** 次级文字颜色 */
  textSecondary: string
  /** 描述文字颜色 */
  textMuted: string
  /** 主色调 */
  accentPrimary: string
  /** 成功状态颜色 */
  accentSuccess: string
  /** 警告状态颜色 */
  accentWarning: string
  /** 错误状态颜色 */
  accentDanger: string
  /** 主色调背景上的文字颜色 */
  textOnAccent: string
  /** 主色调背景上的次要文字颜色 */
  textOnAccentSecondary: string
  /** 成功色淡背景 */
  successLightBg: string
  /** hover 状态边框颜色 */
  borderHover: string
  /** 主色调背景上的成功色文字 */
  successTextOnAccent: string
}

/**
 * 完整终端主题接口
 */
export interface TerminalTheme extends TerminalThemeColors {
  /** 主题唯一标识 */
  id: string
  /** 主题显示名称 */
  name: string
  /** 是否为深色主题 */
  isDark: boolean
  /** xterm.js 终端颜色配置 */
  terminal: TerminalColors
}

/**
 * CSS 变量名称常量
 * 用于动态设置主题颜色
 */
export const THEME_CSS_VARS = {
  backgroundLevel1: '--ct-bg-level-1',
  backgroundLevel2: '--ct-bg-level-2',
  backgroundLevel3: '--ct-bg-level-3',
  backgroundLevel4: '--ct-bg-level-4',
  textPrimary: '--ct-text-primary',
  textSecondary: '--ct-text-secondary',
  textMuted: '--ct-text-muted',
  accentPrimary: '--ct-accent-primary',
  accentSuccess: '--ct-accent-success',
  accentWarning: '--ct-accent-warning',
  accentDanger: '--ct-accent-danger',
  textOnAccent: '--ct-text-on-accent',
  textOnAccentSecondary: '--ct-text-on-accent-secondary',
  successLightBg: '--ct-success-light-bg',
  borderHover: '--ct-border-hover',
  successTextOnAccent: '--ct-success-text-on-accent'
} as const

/**
 * localStorage 存储键名
 */
export const THEME_STORAGE_KEY = 'claude-terminal-theme'