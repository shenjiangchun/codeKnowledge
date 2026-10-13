/**
 * 导出 markdown 工具：把架构图的 mermaid DSL 内嵌为 markdown 代码块。
 */

/** 架构图导出用最小结构（组件内的 Diagram 满足该结构即可传入）。 */
export interface ExportDiagram {
  name: string
  mermaid: string
}

/**
 * 在报告 markdown 末尾追加「架构图」章节，每张图用 ```mermaid 代码块内嵌。
 * 修复「导出时缺少有图部分」：此前导出只取 markdownReport 文本，diagrams 完全丢失。
 *
 * @param markdownReport 结构化报告 markdown 文本
 * @param diagrams 图生成器产出的 mermaid DSL 列表
 * @returns 内嵌 mermaid 代码块后的完整 markdown
 */
export function buildExportMarkdown(markdownReport: string, diagrams: ExportDiagram[]): string {
  if (!diagrams.length) {
    return markdownReport
  }
  let md = markdownReport
  if (md && !md.endsWith('\n')) {
    md += '\n'
  }
  md += '\n## 架构图\n\n'
  for (const d of diagrams) {
    md += `### ${d.name}\n\n`
    md += '```mermaid\n' + d.mermaid.trim() + '\n```\n\n'
  }
  return md
}
