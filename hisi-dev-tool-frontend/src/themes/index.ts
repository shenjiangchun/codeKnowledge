/**
 * 主题模块入口
 *
 * 导出所有主题相关的类型、常量和预设
 */

// 类型定义
export type { TerminalTheme, TerminalThemeColors, TerminalColors } from './types'

// 常量
export { THEME_CSS_VARS, THEME_STORAGE_KEY } from './types'

// 预设主题
export {
  THEME_PRESETS,
  DEFAULT_THEME,
  DARK_TECH,
  DARK_MONOKAI,
  DARK_DRACULA,
  LIGHT_MINIMAL,
  LIGHT_SEPIA,
  EYE_CARE,
  getThemeById
} from './presets'