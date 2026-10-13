/**
 * exportMarkdown 单元测试
 * 验证导出 markdown 时把架构图内嵌为 mermaid 代码块，
 * 修复「导出时缺少有图部分」——此前只导出报告文字，图丢失。
 */
import { describe, test, expect } from 'vitest'
import { buildExportMarkdown } from './exportMarkdown'

describe('buildExportMarkdown', () => {
  test('无图时返回原报告原文', () => {
    const md = '# 报告\n\n## 执行摘要\n\n健康度一般\n'
    expect(buildExportMarkdown(md, [])).toBe(md)
  })

  test('有图时在末尾追加 mermaid 代码块', () => {
    const md = '# 报告\n'
    const out = buildExportMarkdown(md, [
      { name: '分层架构图', mermaid: 'flowchart TB\n  n0["CONTROLLER"]\n' },
    ])

    expect(out).toContain('## 架构图')
    expect(out).toContain('### 分层架构图')
    expect(out).toContain('```mermaid\nflowchart TB\n  n0["CONTROLLER"]\n```')
  })

  test('多张图依次追加，顺序与入参一致', () => {
    const diagrams = [
      { name: '分层架构图', mermaid: 'flowchart TB\n' },
      { name: '关键调用链', mermaid: 'flowchart TD\n' },
    ]

    const out = buildExportMarkdown('# 报告\n', diagrams)

    const first = out.indexOf('分层架构图')
    const second = out.indexOf('关键调用链')
    expect(first).toBeGreaterThan(-1)
    expect(second).toBeGreaterThan(first)
    expect(out).toContain('```mermaid\nflowchart TB\n```')
    expect(out).toContain('```mermaid\nflowchart TD\n```')
  })
})
