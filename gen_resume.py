#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成沈江春简历 PDF - 单页简洁版 v2，修复重叠和文字可见性"""
from fpdf import FPDF

# ─── 配置 ───
FONT_DIR = r"C:\Windows\Fonts"
OUTPUT   = r"C:\Users\47583\projects\hisi_dev_tool v5.0\沈江春_简历.pdf"

# 颜色 (R, G, B) - 正文用深黑色保证可见性
C_BLUE  = (0, 82, 155)
C_BLACK = (20, 20, 20)      # 正文深黑
C_DGRAY = (60, 60, 60)      # 次要文字深灰
C_GRAY  = (110, 110, 110)   # 辅助信息灰
C_LINE  = (190, 190, 190)   # 分隔线淡灰
C_WHITE = (255, 255, 255)

# 字号常量 (pt → mm 近似: 1pt ≈ 0.353mm)
SZ_NAME   = 22
SZ_INFO   = 9
SZ_SEC_H  = 11    # 章节标题
SZ_CO     = 9.5   # 公司名
SZ_ROLE   = 9     # 角色/日期
SZ_BODY   = 9     # 正文要点
SZ_LINK   = 8.5   # 链接
SZ_SK_CAT = 9     # 技能分类名
SZ_SK_IT  = 9     # 技能条目

# 行高常量
LH_BODY   = 4.5   # 正文行高
LH_SKILL  = 4.8   # 技能行高

class ResumePDF(FPDF):
    def __init__(self):
        super().__init__(orientation='P', unit='mm', format='A4')
        self.set_auto_page_break(auto=False)
        # 注册中文字体（统一用 SimHei，避免 TTC 子集化问题）
        self.add_font('SimHei', '', f'{FONT_DIR}\\simhei.ttf')
        # 页面
        self.add_page()
        self.set_margins(14, 10, 14)  # 左14, 上10, 右14

    @property
    def cw(self):
        """可用内容宽度"""
        return self.w - self.l_margin - self.r_margin  # 210-14-14=182

    def section_header(self, title):
        """蓝色竖线 + 标题 + 底线"""
        y = self.get_y()
        # 蓝色竖线
        self.set_draw_color(*C_BLUE)
        self.set_line_width(0.9)
        self.line(self.l_margin, y, self.l_margin, y + 5.2)
        # 标题文字
        self.set_text_color(*C_BLUE)
        self.set_font('SimHei', '', SZ_SEC_H)
        self.set_xy(self.l_margin + 3.5, y - 0.5)
        self.cell(0, 5, title, new_x='LMARGIN', new_y='NEXT')
        # 底线
        self.set_draw_color(*C_LINE)
        self.set_line_width(0.25)
        self.line(self.l_margin, y + 5.5, self.w - self.r_margin, y + 5.5)
        self.set_y(y + 7)

    def exp_header(self, left, mid, right):
        """公司 | 角色 | 日期"""
        y = self.get_y()
        x0 = self.l_margin + 2
        # 左：公司名（加粗黑体）
        self.set_text_color(*C_BLACK)
        self.set_font('SimHei', '', SZ_CO)
        self.set_xy(x0, y)
        self.cell(65, 5, left)
        # 中：角色（SimHei 保证中文渲染）
        self.set_text_color(*C_DGRAY)
        self.set_font('SimHei', '', SZ_ROLE)
        self.set_xy(x0 + 65, y)
        self.cell(55, 5, mid)
        # 右：日期
        self.set_text_color(*C_GRAY)
        self.set_font('SimHei', '', SZ_ROLE)
        self.set_xy(self.w - self.r_margin - 45, y)
        self.cell(45, 5, right, align='R')
        self.set_y(y + 5.8)

    def bullet(self, text, indent=None):
        """带圆点的要点"""
        if indent is None:
            indent = self.l_margin + 2
        self.set_text_color(*C_BLACK)
        self.set_font('SimHei', '', SZ_BODY)  # SimHei 可靠渲染中文
        y = self.get_y()
        # 圆点
        self.set_xy(indent, y)
        self.cell(4, LH_BODY, '◆')  # ◆ 小黑方块（SimHei 含此字符）
        # 正文（multi_cell 自动换行，宽度 = 总宽 - 缩进 - 圆点宽）
        tw = self.w - self.r_margin - indent - 5
        self.set_xy(indent + 5, y)
        self.multi_cell(tw, LH_BODY, text, new_x='LMARGIN', new_y='NEXT')

    def link_line(self, url, indent=None):
        """蓝色链接行"""
        if indent is None:
            indent = self.l_margin + 2
        self.set_text_color(0, 100, 180)
        self.set_font('SimHei', '', SZ_LINK)
        self.set_x(indent)
        self.cell(0, 4.5, url, new_x='LMARGIN', new_y='NEXT')

    def skill_row(self, cat, items, indent=None):
        """技能分类 | 具体条目（左标签 + 右内容，不会重叠）"""
        if indent is None:
            indent = self.l_margin + 2
        y = self.get_y()
        cat_w = 20  # 分类标签宽度 mm
        # 分类标签（加粗）
        self.set_text_color(*C_BLACK)
        self.set_font('SimHei', '', SZ_SK_CAT)
        self.set_xy(indent, y)
        self.cell(cat_w, LH_SKILL, cat)
        # 条目内容（SimHei 保证中文渲染）
        self.set_text_color(*C_DGRAY)
        self.set_font('SimHei', '', SZ_SK_IT)
        content_x = indent + cat_w + 1
        content_w = self.w - self.r_margin - content_x
        self.set_xy(content_x, y)
        self.multi_cell(content_w, LH_SKILL, items, new_x='LMARGIN', new_y='NEXT')


def build_resume():
    pdf = ResumePDF()
    L = pdf.l_margin   # 左边距 = 14
    R = pdf.r_margin    # 右边距 = 14

    # ════════════════════════════════════
    #  HEADER
    # ════════════════════════════════════
    y = 10
    # 姓名
    pdf.set_text_color(*C_BLUE)
    pdf.set_font('SimHei', '', SZ_NAME)
    pdf.set_xy(L, y)
    pdf.cell(pdf.cw, 9, '沈 江 春', align='C')
    y += 10.5

    # 基本信息
    pdf.set_text_color(*C_DGRAY)
    pdf.set_font('SimHei', '', SZ_INFO)
    pdf.set_xy(L, y)
    pdf.cell(pdf.cw, 5,
             '男  |  29岁  |  东北大学 · 计算机科学与技术 · 硕士  |  3年后端开发经验  |  华为 · 系统工程师',
             align='C')
    y += 6

    # 联系方式
    pdf.set_xy(L, y)
    pdf.cell(pdf.cw, 5,
             '电话：请填写  |  邮箱：请填写  |  GitHub：github.com/shenjiangchun',
             align='C')
    y += 7

    # 蓝色粗分隔线
    pdf.set_draw_color(*C_BLUE)
    pdf.set_line_width(1.0)
    pdf.line(L, y, pdf.w - R, y)
    pdf.set_y(y + 3)

    # ════════════════════════════════════
    #  工作经历
    # ════════════════════════════════════
    pdf.section_header('工作经历')
    pdf.exp_header('华为技术有限公司', '系统工程师 · 后端开发', '2024.01 - 至今')
    pdf.set_y(pdf.get_y() + 0.5)

    # ════════════════════════════════════
    #  项目经历
    # ════════════════════════════════════
    pdf.section_header('项目经历')

    # ── 项目1：智能问数平台 ──
    pdf.exp_header('智能问数平台（Text2SQL）', '核心开发', '2025.01 - 至今')
    pdf.bullet(
        '基于 Python/FastAPI + PostgreSQL 搭建 Text2SQL 数据查询平台，独立完成指标、枚举、元数据、术语、'
        '历史知识 5 大核心模块设计与落地，支持自然语言转 SQL 自助查询，降低业务方取数门槛')
    pdf.bullet(
        '设计并实现表级 + 行级双层数据权限管控方案，结合 RBAC 角色体系保障多部门数据隔离与访问安全，'
        '通过 SQL 改写注入权限过滤条件，实现查询零泄漏')

    # ── 项目2：研发工具链架构治理 ──
    pdf.exp_header('研发工具链架构治理', '技术负责人', '2024.06 - 2025.01')
    pdf.bullet(
        '主导百万级月度 PV 的 IT 研发装备工具链开源三方件安全整改，推动核心服务从 JDK8 + Spring 2.x '
        '升级至 JDK21 + Spring Boot 3.4，彻底消除全部已知 CVE 安全漏洞')
    pdf.bullet(
        '牵头架构优化与开源合规审计，输出标准化架构设计文档，主导需求方案设计与技术评审流程，'
        '推动研发流程规范化落地，建立技术债治理长效机制')

    # ── 项目3：AI 研发效能提升 ──
    pdf.exp_header('AI 研发效能提升', '独立开发', '2024.09 - 至今')
    pdf.bullet(
        '独立开发代码提交追踪、自动化测试录制回放、代码语义查询等多款 AI 辅助研发工具，'
        '搭建研发 Skill 工具套件覆盖 31 人团队全场景日常使用')
    pdf.bullet(
        '基于 RAG + 向量检索实现代码语义搜索，结合 LLM 智能生成代码摘要与变更影响分析，'
        '提升代码评审与问题定位效率')

    # ── 项目4：全球纳税申报 SaaS ──
    pdf.exp_header('全球纳税申报 SaaS 平台', '研发负责人（3人小组）', '2024.01 - 2024.09')
    pdf.bullet(
        '带领 3 人小组负责核心报表与申报业务模块研发，为中国、马来西亚、新加坡等 5 国 300+ 子公司'
        '提供纳税申报、税款缴纳、报表生成全流程服务')
    pdf.bullet(
        '设计全球公共基础类与多国税务计算引擎，统一封装多区域税收计算逻辑；'
        '排查修复内存泄漏与 SQL 性能瓶颈，保障财务数据 100% 准确与系统稳定运行')

    # ── 项目5：HiSi DevTool（开源） ──
    pdf.exp_header('HiSi DevTool（开源个人项目）', '独立开发', '2025.03 - 至今')
    pdf.link_line('github.com/shenjiangchun/codeKnowledge')
    pdf.bullet(
        '基于 JavaParser + ANTLR4 实现 Java/Python 双语言 AST 解析，搭建代码知识图谱构建引擎'
        '自动扫描项目生成知识图谱存储于 Neo4j')
    pdf.bullet(
        '设计混合检索引擎融合 9 种查询策略 + RRF 多路结果融合排序，'
        '实现语义/代码/SQL 三向量空间联合检索；搭建 RAM 需求分析引擎通过 DAG 编排 5 节点实现需求高效流转')
    pdf.bullet(
        '开发合并影响分析模块，支持 JGit Diff 比对、KG 三层上游追溯、LLM 智能生成测试范围建议，'
        '通过 SSE 实时推送分析进度')
    pdf.bullet(
        '技术栈：Spring Boot 3 / JDK 21 / Neo4j 5.11+ / 智谱 AI / Claude API / ANTLR4 / Vue3 / TypeScript')

    # ════════════════════════════════════
    #  专业技能
    # ════════════════════════════════════
    pdf.section_header('专业技能')
    pdf.skill_row('语言框架', 'Java（精通）、Python（熟练）、Spring Boot 3 / Spring Cloud / MyBatis / FastAPI')
    pdf.skill_row('数据存储', 'PostgreSQL（索引优化、MVCC、分区表）、Redis（缓存策略、分布式锁）、Neo4j（Cypher、图建模）')
    pdf.skill_row('中间件',   'Kafka（消息可靠性、顺序消费）、Kubernetes、Docker、Nginx')
    pdf.skill_row('AI 工程',  'RAG 管道设计、向量检索（pgvector）、LLM 集成（智谱/Claude）、Prompt Engineering')
    pdf.skill_row('工具链',   'Git、Maven/Gradle、ANTLR4、JGit、Flyway、Grafana、ELK')
    pdf.skill_row('架构能力', '多租户 SaaS 架构、分布式系统设计、DDD、微服务拆分、性能调优')

    # ════════════════════════════════════
    #  教育背景
    # ════════════════════════════════════
    pdf.section_header('教育背景')
    pdf.exp_header('东北大学', '计算机科学与技术 · 硕士', '2020.09 - 2023.06')
    pdf.set_text_color(*C_DGRAY)
    pdf.set_font('SimHei', '', SZ_BODY)  # SimHei 保证中文渲染
    pdf.set_x(L + 2)
    pdf.cell(0, 4.5, '985 / 211 / 双一流  |  研究方向：软件工程 / 知识图谱', new_x='LMARGIN', new_y='NEXT')

    # ── 保存 ──
    final_y = pdf.get_y()
    print(f'内容结束 Y = {final_y:.1f}mm (A4 高度 297mm，剩余 {297 - final_y:.1f}mm)')
    pdf.output(OUTPUT)
    print(f'简历 PDF 已生成: {OUTPUT}')
    print(f'总页数: {pdf.pages_count}')


if __name__ == '__main__':
    build_resume()
