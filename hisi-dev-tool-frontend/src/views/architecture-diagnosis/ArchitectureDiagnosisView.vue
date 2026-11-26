<template>
  <div class="architecture-diagnosis-view">
    <!-- 顶部：项目选择 + 操作 -->
    <el-card class="control-card">
      <template #header>
        <div class="card-header">
          <span>架构诊断</span>
          <div class="header-actions">
            <el-select
              v-model="realtimeProjectName"
              placeholder="选择项目（实时报告单项目）"
              filterable
              style="width: 350px"
            >
              <el-option
                v-for="proj in availableProjects"
                :key="proj.name"
                :label="proj.name"
                :value="proj.name"
              />
            </el-select>
            <el-button type="primary" :loading="loading" :disabled="!hasProject" class="generate-btn" @click="generateReport(false)">
              {{ report ? '刷新报告' : '生成报告' }}
            </el-button>
            <el-button type="primary" :loading="loading" :disabled="!report || viewingSnapshot" @click="saveSnapshot">
              保存快照
            </el-button>
            <el-button :disabled="!report" @click="exportMarkdown">
              导出 Markdown
            </el-button>
            <el-button type="primary" plain :loading="deepRunning" :disabled="!hasProject" @click="startDeepAnalysis">
              ✦ AI 深度分析
            </el-button>
          </div>
        </div>
      </template>

      <!-- 空态 -->
      <el-empty v-if="!hasProject && !loading" description="选择项目并生成诊断报告" />

      <!-- 加载态 -->
      <div v-if="loading" class="loading-container">
        <el-skeleton :rows="8" animated />
      </div>

      <!-- 报告 + 深度分析双 tab
           用 v-if="hasProject" 保持 tabs 常驻（不随 loading 卸载），loading 期间仅用 v-show 隐藏，
           避免 el-tabs 在 loading 切换时 unmount/remount 触发 element-plus use-ordered-children 崩溃 -->
      <el-tabs v-if="hasProject" v-show="!loading" v-model="activeTopTab" type="border-card">
        <el-tab-pane label="实时报告" name="realtime">
          <div v-if="report" class="report-content">
            <!-- 历史快照查看提示 -->
            <el-alert v-if="viewingSnapshot" type="warning" :closable="false" class="desc-bar">
              <template #title>
                正在查看历史快照（保存于 {{ report.generatedAt }}）。「导出 Markdown」将导出此快照；点击「生成报告」回到实时诊断。
              </template>
            </el-alert>
            <!-- 诊断总览 -->
            <el-card class="overview-card">
              <template #header>
                <div class="card-header">
                  <span>诊断总览</span>
                  <el-tag :type="gradeTagType" size="large">等级 {{ report.grade }}</el-tag>
                </div>
              </template>
              <el-alert type="info" :closable="false" class="desc-bar">
                <template #title>
                  健康总分采用<b>扣分制</b>：初始 100 分，按问题严重度扣除（HIGH -10 / MEDIUM -5 / LOW -2）；项目/模块/类/方法四层各自<b>封顶扣 25 分</b>，总分下限 10 分。等级：<b>A≥90 / B≥75 / C≥60 / D&lt;60</b>。分数颜色：绿(A)、蓝(B)、橙(C)、红(D)。
                </template>
              </el-alert>
              <div class="overview-grid">
                <div class="health-score">
                  <div class="score-number" :style="{ color: scoreColor }">{{ report.healthScore }}</div>
                  <div class="score-label">健康总分</div>
                </div>
                <div class="kpi-list">
                  <div v-if="kpis" class="kpi-item">
                    <span class="kpi-label">总方法数</span>
                    <span class="kpi-value">{{ kpis.totalMethods ?? '-' }}</span>
                  </div>
                  <div v-if="kpis" class="kpi-item">
                    <span class="kpi-label">领域数</span>
                    <span class="kpi-value">{{ kpis.totalDomains ?? '-' }}</span>
                  </div>
                  <div v-if="kpis" class="kpi-item">
                    <span class="kpi-label">循环依赖</span>
                    <span class="kpi-value">{{ kpis.cyclicDependencies ?? '-' }}</span>
                  </div>
                  <div v-if="kpis" class="kpi-item">
                    <span class="kpi-label">分层违规</span>
                    <span class="kpi-value">{{ kpis.layeredViolations ?? '-' }}</span>
                  </div>
                </div>
                <div class="issue-summary">
                  <el-tag v-for="sev in ['HIGH', 'MEDIUM', 'LOW']" :key="sev"
                    :type="severityTagType(sev)" size="small" class="issue-tag">
                    {{ sev }}: {{ issueCountBySeverity(sev) }}
                  </el-tag>
                </div>
              </div>
            </el-card>

            <!-- 问题清单 -->
            <el-card class="issues-card">
              <template #header>
                <div class="card-header">
                  <span>问题清单</span>
                  <span class="issue-count">共 {{ report.issues.length }} 项</span>
                </div>
              </template>
              <div class="metric-desc">严重度：HIGH 高危（-10 分，优先处理）· MEDIUM 中危（-5 分）· LOW 低危（-2 分）。层级 = 问题所属检测层（项目/模块/类/方法）。</div>
              <el-table :data="report.issues" size="small" max-height="300">
                <el-table-column prop="severity" label="严重度" width="80">
                  <template #default="{ row }">
                    <el-tag :type="severityTagType(row.severity)" size="small">{{ row.severity }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="sourceLevel" label="层级" width="140" />
                <el-table-column prop="source" label="来源" width="180" />
                <el-table-column prop="message" label="描述" />
              </el-table>
            </el-card>

            <!-- 四章锚点导航 -->
            <el-card class="chapters-card">
              <template #header>
                <div class="card-header">
                  <span>四层分析</span>
                </div>
              </template>
              <div class="chapter-intro">四层检测自上而下：项目级 → 模块级 → 类级 → 方法级，共 11 项坏味道。多数阈值采用「百分位 p95 + 绝对下限」自适应项目规模，避免小项目误报。</div>
              <el-tabs v-model="activeChapter" type="border-card">
                <el-tab-pane label="项目级" name="project">
                  <div class="chapter-content">
                    <h4>领域划分</h4>
                    <div class="metric-desc">垂直领域识别：同领域词 × 分层角色贯穿聚合（如 OrderController/Service/Repository/Entity → Order 域），跨越包边界；纯 MODEL 孤立词归「孤立数据对象」，不入域。</div>
                    <el-table :data="chapterData('project', 'domains')" size="small" max-height="250">
                      <el-table-column prop="name" label="领域" />
                      <el-table-column prop="domainWord" label="领域词" width="140" />
                      <el-table-column prop="memberCount" label="类数" width="80" />
                      <el-table-column label="分层角色">
                        <template #default="{ row }">{{ (row.roles || []).join(' / ') }}</template>
                      </el-table-column>
                    </el-table>
                    <h4>孤立数据对象</h4>
                    <div class="metric-desc">仅 MODEL/UTILITY 或单角色、无业务骨架的数据对象词，未构成完整领域。</div>
                    <el-table :data="chapterData('project', 'isolatedDataObjects')" size="small" max-height="200">
                      <el-table-column prop="domainWord" label="领域词" width="140" />
                      <el-table-column prop="memberCount" label="类数" width="80" />
                      <el-table-column label="分层角色">
                        <template #default="{ row }">{{ (row.roles || []).join(' / ') }}</template>
                      </el-table-column>
                    </el-table>
                    <h4>大泥球（Big Ball of Mud）</h4>
                    <div class="metric-desc">耦合密度 = 模块依赖边数 ÷ (模块数 × (模块数-1))；跨层依赖占比 = 分层违规边数 ÷ 总依赖边数。判定「大泥球」：密度 ≥ 0.5，或（密度 ≥ 0.3 且 跨层占比 ≥ 0.3）。大泥球意味着模块边界模糊、全局高度耦合，重构成本高。</div>
                    <el-table v-if="bigBallOfMud" :data="[bigBallOfMud]" size="small">
                      <el-table-column prop="couplingDensity" label="耦合密度" />
                      <el-table-column prop="crossLayerRatio" label="跨层依赖占比" />
                      <el-table-column prop="moduleCount" label="模块数" width="90" />
                      <el-table-column label="判定" width="100">
                        <template #default="{ row }">
                          <el-tag :type="row.isMud ? 'danger' : 'success'" size="small">{{ row.isMud ? '大泥球' : '健康' }}</el-tag>
                        </template>
                      </el-table-column>
                    </el-table>
                    <el-empty v-else description="暂无大泥球数据" :image-size="50" />
                    <h4>全局循环依赖</h4>
                    <div class="metric-desc">分别统计领域环 / 包环 / 模块环个数；totalCycles = 三者之和，涉及模块数 = 参与任意环的模块去重数。循环依赖会导致模块无法独立构建与测试。</div>
                    <el-table v-if="globalCycles" :data="[globalCycles]" size="small">
                      <el-table-column prop="domainCycles" label="领域环" />
                      <el-table-column prop="packageCycles" label="包环" />
                      <el-table-column prop="moduleCycles" label="模块环" />
                      <el-table-column prop="totalCycles" label="总环数" />
                      <el-table-column prop="involvedModuleCount" label="涉及模块数" />
                    </el-table>
                    <el-empty v-else description="暂无循环依赖数据" :image-size="50" />
                  </div>
                </el-tab-pane>
                <el-tab-pane label="模块级" name="module">
                  <div class="chapter-content">
                    <div class="module-dual-pane">
                      <div class="pane-left">
                        <h4>DSM 依赖矩阵</h4>
                        <DsmMatrix
                          :project-paths="realtimeProjectPaths"
                          :external-dsm="dsmExternal"
                          :external-call-graph="callGraphExternal"
                        />
                      </div>
                      <div class="pane-right">
                        <h4>循环依赖</h4>
                        <div class="metric-desc">level = CROSS_LAYER（跨层环，破坏分层，高危）或 SAME_LAYER（同层环，技术债）。</div>
                        <el-table :data="chapterCycles" size="small" max-height="220">
                          <el-table-column label="类型" width="110">
                            <template #default="{ row }">
                              <el-tag :type="cycleTagType(row.level)" size="small">{{ row.level }}</el-tag>
                            </template>
                          </el-table-column>
                          <el-table-column prop="message" label="描述" />
                        </el-table>
                        <h4>分层违规</h4>
                        <div class="metric-desc">source → target 违反分层规则（如持久层反向依赖控制器层）。</div>
                        <el-table :data="chapterData('module', 'layerViolations')" size="small" max-height="180">
                          <el-table-column prop="source" label="来源" />
                          <el-table-column prop="target" label="目标" />
                          <el-table-column prop="message" label="描述" />
                        </el-table>
                      </div>
                    </div>
                    <h4>模块枢纽（扇入/扇出双高）</h4>
                    <div class="metric-desc">扇入 = 被多少模块依赖，扇出 = 依赖多少模块；两者均 ≥ 95% 分位即「模块枢纽」，是耦合热点，改动影响面大。</div>
                    <el-table :data="moduleHubs" size="small" max-height="200">
                      <el-table-column prop="module" label="模块" />
                      <el-table-column prop="fanIn" label="扇入" width="90" />
                      <el-table-column prop="fanOut" label="扇出" width="90" />
                    </el-table>
                    <el-empty v-if="!moduleHubs.length" description="暂无模块枢纽" :image-size="50" />
                    <h4>反向依赖（下层 → 上层）</h4>
                    <div class="metric-desc">下层模块依赖上层模块（方向倒置），是分层违规中最需治理的一类。</div>
                    <el-table :data="reverseDependencies" size="small" max-height="200">
                      <el-table-column prop="source" label="来源" />
                      <el-table-column prop="target" label="目标" />
                      <el-table-column prop="message" label="描述" />
                    </el-table>
                    <el-empty v-if="!reverseDependencies.length" description="暂无反向依赖" :image-size="50" />
                    <h4>臃肿包</h4>
                    <div class="metric-desc">包内类数 ≥ max(3, 95%分位) 且 方法数 ≥ 95%分位，说明包职责过重，建议按领域拆分。</div>
                    <el-table :data="bloatedPackages" size="small" max-height="200">
                      <el-table-column prop="packageName" label="包" />
                      <el-table-column prop="classCount" label="类数" width="90" />
                      <el-table-column prop="methodCount" label="方法数" width="90" />
                    </el-table>
                    <el-empty v-if="!bloatedPackages.length" description="暂无臃肿包" :image-size="50" />
                    <h4>实现类外泄</h4>
                    <div class="metric-desc">接口与实现跨包，且实现类被实现包之外的代码直接调用（绕过接口），破坏面向接口编程的封装。</div>
                    <el-table :data="implementationLeaks" size="small" max-height="200">
                      <el-table-column prop="implClass" label="实现类" />
                      <el-table-column prop="implPackage" label="实现包" width="200" />
                      <el-table-column prop="ifaceClass" label="接口" />
                      <el-table-column label="外部调用方" width="90">
                        <template #default="{ row }">{{ (row.externalCallers || []).length }}</template>
                      </el-table-column>
                    </el-table>
                    <el-empty v-if="!implementationLeaks.length" description="暂无实现类外泄" :image-size="50" />
                  </div>
                </el-tab-pane>
                <el-tab-pane label="类级" name="class">
                  <div class="chapter-content">
                    <h4>上帝类（WMC）</h4>
                    <div class="metric-desc">上帝类 = WMC（类内所有方法圈复杂度之和）与方法数双高（均 ≥ 95% 分位）。柱状图按 WMC 降序，柱越长 = 复杂度越高，单类承担过多职责，难以维护与测试。</div>
                    <div v-if="godClasses.length" ref="godChartRef" class="class-chart" />
                    <el-empty v-else description="暂无上帝类数据" :image-size="60" />
                    <h4>枢纽类（扇入 / 扇出）</h4>
                    <div class="metric-desc">枢纽类 = 扇入（被多少类依赖）与扇出（依赖多少类）双高。柱状图：蓝 = 扇入，绿 = 扇出。</div>
                    <div v-if="hubClasses.length" ref="hubChartRef" class="class-chart" />
                    <el-empty v-else description="暂无枢纽类数据" :image-size="60" />
                    <el-collapse v-model="detailCollapse">
                      <el-collapse-item title="类级明细" name="class">
                        <h4>上帝类</h4>
                        <el-table :data="godClasses" size="small" max-height="200">
                          <el-table-column label="类名">
                            <template #default="{ row }">{{ shortName(row.className) }}</template>
                          </el-table-column>
                          <el-table-column prop="methodCount" label="方法数" width="80" />
                          <el-table-column prop="wmc" label="WMC" width="80" />
                        </el-table>
                        <h4>枢纽类</h4>
                        <el-table :data="hubClasses" size="small" max-height="200">
                          <el-table-column label="类名">
                            <template #default="{ row }">{{ shortName(row.className) }}</template>
                          </el-table-column>
                          <el-table-column prop="inDegree" label="扇入" width="80" />
                          <el-table-column prop="outDegree" label="扇出" width="80" />
                        </el-table>
                        <h4>幽灵类</h4>
                        <div class="metric-desc">无方法（no_method）或所有方法既无调用也无被调（isolated），属死代码或未接线的逻辑，建议删除或补齐。</div>
                        <el-table :data="ghostClasses" size="small" max-height="200">
                          <el-table-column label="类名">
                            <template #default="{ row }">{{ shortName(row.className) }}</template>
                          </el-table-column>
                          <el-table-column prop="packageName" label="包" width="200" />
                          <el-table-column label="原因" width="90">
                            <template #default="{ row }">{{ row.reason === 'no_method' ? '无方法' : '孤立类' }}</template>
                          </el-table-column>
                        </el-table>
                        <el-empty v-if="!ghostClasses.length" description="暂无幽灵类" :image-size="50" />
                        <h4>贫血数据类（仅 get/set/is/has）</h4>
                        <div class="metric-desc">≥ 3 个方法且全部为 get/set/is/has 访问器（已排除接口与测试类），领域逻辑外置，是典型贫血模型（数据与行为分离）。</div>
                        <el-table :data="anemicDataClasses" size="small" max-height="200">
                          <el-table-column label="类名">
                            <template #default="{ row }">{{ shortName(row.className) }}</template>
                          </el-table-column>
                          <el-table-column prop="packageName" label="包" width="200" />
                          <el-table-column prop="methodCount" label="访问器数" width="90" />
                        </el-table>
                        <el-empty v-if="!anemicDataClasses.length" description="暂无贫血数据类" :image-size="50" />
                      </el-collapse-item>
                    </el-collapse>
                  </div>
                </el-tab-pane>
                <el-tab-pane label="方法级" name="method">
                  <div class="chapter-content">
                    <h4>方法级热点（热力图，按风险分着色）</h4>
                    <div class="metric-desc">风险分 = 圈复杂度×0.35 + 变更频率×0.35 + 入度×0.20 + 循环惩罚×0.10（归一化 0~1）。热力图块大小 = 圈复杂度，颜色越红 = 风险分越高，越值得优先重构与测试。</div>
                    <div v-if="methodHotspots.length" ref="methodChartRef" class="method-treemap" />
                    <el-empty v-else description="暂无方法级热点数据" :image-size="60" />
                    <el-collapse v-model="detailCollapse">
                      <el-collapse-item title="方法级明细" name="method">
                        <el-table :data="methodHotspots" size="small" max-height="350">
                          <el-table-column prop="methodName" label="方法" />
                          <el-table-column label="类">
                            <template #default="{ row }">{{ shortName(row.className) }}</template>
                          </el-table-column>
                          <el-table-column prop="complexity" label="复杂度" width="80" />
                          <el-table-column prop="riskScore" label="风险分" width="80" />
                          <el-table-column prop="filePath" label="文件" />
                        </el-table>
                      </el-collapse-item>
                    </el-collapse>
                    <h4>超长方法</h4>
                    <div class="metric-desc">行数 LOC ≥ max(50, 95%分位)，过长的方法难以理解与测试，建议按职责拆分。</div>
                    <el-table :data="longMethods" size="small" max-height="200">
                      <el-table-column label="方法">
                        <template #default="{ row }">{{ shortName(row.className) }}.{{ row.methodName }}</template>
                      </el-table-column>
                      <el-table-column prop="filePath" label="文件" />
                      <el-table-column prop="loc" label="行数" width="90" />
                    </el-table>
                    <el-empty v-if="!longMethods.length" description="暂无超长方法" :image-size="50" />
                    <h4>过多参数</h4>
                    <div class="metric-desc">参数个数 ≥ max(5, 95%分位)，参数过多说明职责混杂，建议用参数对象封装。</div>
                    <el-table :data="tooManyParams" size="small" max-height="200">
                      <el-table-column label="方法">
                        <template #default="{ row }">{{ shortName(row.className) }}.{{ row.methodName }}</template>
                      </el-table-column>
                      <el-table-column prop="paramCount" label="参数数" width="90" />
                    </el-table>
                    <el-empty v-if="!tooManyParams.length" description="暂无过多参数方法" :image-size="50" />
                    <h4>过深调用链</h4>
                    <div class="metric-desc">从根方法（无上游调用者）起算的最短跳数深度 ≥ max(8, 95%分位)，链路过深增加调试与维护成本。</div>
                    <el-table :data="deepCallChains" size="small" max-height="200">
                      <el-table-column label="方法">
                        <template #default="{ row }">{{ shortName(row.className) }}.{{ row.methodName }}</template>
                      </el-table-column>
                      <el-table-column prop="depth" label="深度" width="90" />
                    </el-table>
                    <el-empty v-if="!deepCallChains.length" description="暂无过深调用链" :image-size="50" />
                  </div>
                </el-tab-pane>
              </el-tabs>
            </el-card>

            <!-- 快照历史 -->
            <el-card class="snapshot-card">
              <template #header>
                <div class="card-header">
                  <span>快照历史</span>
                </div>
              </template>
              <div class="metric-desc">记录每次「保存快照」时的健康总分、等级与各严重度（HIGH/MEDIUM/LOW）问题计数，用于追踪架构健康度随时间的变化趋势。</div>
              <div v-if="snapshots.length === 0" class="empty-snapshot">暂无历史快照</div>
              <el-table v-else :data="snapshots" size="small">
                <el-table-column prop="createdAt" label="时间" width="160" />
                <el-table-column prop="healthScore" label="总分" width="80" />
                <el-table-column prop="grade" label="等级" width="60" />
                <el-table-column prop="highCount" label="HIGH" width="70" />
                <el-table-column prop="mediumCount" label="MEDIUM" width="80" />
                <el-table-column prop="lowCount" label="LOW" width="70" />
                <el-table-column label="操作" width="80" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" size="small" @click="viewSnapshot(row.id)">查看</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </div>
        </el-tab-pane>

        <el-tab-pane label="AI 深度分析" name="deep">
          <AiDeepAnalysisPanel
            ref="deepPanel"
            :available-projects="availableProjects"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { useAppStore } from '@/stores/app'
import { getReport, getSnapshots, getSnapshotReport, exportReport } from '@/api/architectureDiagnosis'
import type { DiagnosisReport, SnapshotItem, MethodHotspot, ClassMetric } from '@/types/architectureDiagnosis'
import DsmMatrix from '@/views/knowledge-graph/components/DsmMatrix.vue'
import AiDeepAnalysisPanel from './AiDeepAnalysisPanel.vue'

const appStore = useAppStore()

const realtimeProjectName = ref('')
const report = ref<DiagnosisReport | null>(null)
const loading = ref(false)
const activeChapter = ref('project')
const activeTopTab = ref<'realtime' | 'deep'>('realtime')
const snapshots = ref<SnapshotItem[]>([])
const detailCollapse = ref<string[]>([])
// 历史快照查看模式：非 null 表示正在展示某条历史快照（进入页面默认展示最新一条，不自动刷新）
const viewingSnapshotId = ref<number | null>(null)
const viewingSnapshot = computed(() => viewingSnapshotId.value != null)
const methodChartRef = ref<HTMLDivElement>()
const godChartRef = ref<HTMLDivElement>()
const hubChartRef = ref<HTMLDivElement>()
const deepPanel = ref<{ start: (defaultProject?: string) => void; isRunning?: boolean } | null>(null)
const deepRunning = computed(() => deepPanel.value?.isRunning ?? false)

// 报告请求序号：用于丢弃过期请求的返回，避免竞态下旧结果覆盖新结果
let reportRequestSeq = 0

const availableProjects = computed(() => appStore.selectedProjects.map(p => ({ name: p.name, path: p.path })))
const realtimeProjectPaths = computed(() => {
  const proj = availableProjects.value.find(p => p.name === realtimeProjectName.value)
  return proj ? [proj.path] : []
})
const hasProject = computed(() => realtimeProjectPaths.value.length > 0)

// 历史快照模式下，DsmMatrix 使用快照内的 dsm + 类调用图（本地下钻，不触发实时 API）
const dsmExternal = computed(() => {
  if (!viewingSnapshot.value) return null
  return (report.value?.chapters?.module as Record<string, any> | undefined)?.dsm ?? null
})
const callGraphExternal = computed(() => {
  if (!viewingSnapshot.value) return null
  return (report.value?.chapters?.class as Record<string, any> | undefined)?.callGraph ?? null
})

const kpis = computed(() => {
  const projectChapter = report.value?.chapters?.project as Record<string, any> | undefined
  return projectChapter?.kpis
})

const chapterData = (chapter: 'project' | 'module' | 'class' | 'method', key: string) => {
  const data = report.value?.chapters?.[chapter] as Record<string, any> | undefined
  return (data?.[key] as any[]) || []
}

const chapterCycles = computed(() => {
  const moduleChapter = report.value?.chapters?.module as Record<string, any> | undefined
  return (moduleChapter?.cycles as any[]) || []
})

const methodHotspots = computed<MethodHotspot[]>(() => chapterData('method', 'hotspots') as MethodHotspot[])

const godClasses = computed<ClassMetric[]>(() => {
  const classChapter = report.value?.chapters?.class as Record<string, any> | undefined
  const metrics = classChapter?.metrics as Record<string, any> | undefined
  return (metrics?.godClasses as ClassMetric[]) || []
})

const hubClasses = computed<ClassMetric[]>(() => {
  const classChapter = report.value?.chapters?.class as Record<string, any> | undefined
  const metrics = classChapter?.metrics as Record<string, any> | undefined
  return (metrics?.hubClasses as ClassMetric[]) || []
})

// ── Phase 1 四层坏味道明细（后端 ArchSmellDetectorService 产出）──
const bigBallOfMud = computed(() => {
  const projectChapter = report.value?.chapters?.project as Record<string, any> | undefined
  return projectChapter?.bigBallOfMud ?? null
})

const globalCycles = computed(() => {
  const projectChapter = report.value?.chapters?.project as Record<string, any> | undefined
  return projectChapter?.globalCycles ?? null
})

const moduleHubs = computed<any[]>(() => chapterData('module', 'moduleHubs'))
const reverseDependencies = computed<any[]>(() => chapterData('module', 'reverseDependencies'))
const bloatedPackages = computed<any[]>(() => envelopeItems('module', 'bloatedPackages'))
const implementationLeaks = computed<any[]>(() => envelopeItems('module', 'implementationLeaks'))
const ghostClasses = computed<any[]>(() => envelopeItems('class', 'ghostClasses'))
const anemicDataClasses = computed<any[]>(() => envelopeItems('class', 'anemicDataClasses'))
const longMethods = computed<any[]>(() => envelopeItems('method', 'longMethods'))
const tooManyParams = computed<any[]>(() => envelopeItems('method', 'tooManyParams'))
const deepCallChains = computed<any[]>(() => envelopeItems('method', 'deepCallChains'))

/** 取 envelope 检测结果（{ items, thresholds }）中的 items 数组。 */
function envelopeItems(chapter: 'project' | 'module' | 'class' | 'method', key: string): any[] {
  const data = report.value?.chapters?.[chapter] as Record<string, any> | undefined
  return (data?.[key]?.items as any[]) || []
}

const scoreColor = computed(() => {
  const score = report.value?.healthScore ?? 100
  if (score >= 90) return '#67C23A'
  if (score >= 75) return '#409EFF'
  if (score >= 60) return '#E6A23C'
  return '#F56C6C'
})

const gradeTagType = computed(() => {
  const grade = report.value?.grade
  if (grade === 'A') return 'success'
  if (grade === 'B') return 'primary'
  if (grade === 'C') return 'warning'
  return 'danger'
})

function severityTagType(severity: string) {
  if (severity === 'HIGH') return 'danger'
  if (severity === 'MEDIUM') return 'warning'
  return 'info'
}

function cycleTagType(level: string) {
  if (level === 'CROSS_LAYER') return 'danger'
  if (level === 'SAME_LAYER') return 'warning'
  return 'info'
}

function shortName(className: string) {
  if (!className) return ''
  return className.split('.').pop() || className
}

function issueCountBySeverity(severity: string) {
  return report.value?.issues?.filter(i => i.severity === severity).length || 0
}

/** 下拉单选：实时报告仅针对单个项目生成，选中即刷新报告 */
async function generateReport(persist = false) {
  const paths = realtimeProjectPaths.value
  if (paths.length === 0) {
    return
  }
  const seq = ++reportRequestSeq
  loading.value = true
  try {
    const result = await getReport(paths, undefined, persist)
    if (seq !== reportRequestSeq) {
      // 已有更新的请求发起，丢弃本次过期结果
      return
    }
    report.value = result
    viewingSnapshotId.value = null
    if (persist) {
      ElMessage.success('快照保存成功')
      await loadSnapshots(paths[0])
    }
  } catch (e: any) {
    if (seq !== reportRequestSeq) {
      return
    }
    ElMessage.error('报告生成失败: ' + (e.message || e))
  } finally {
    if (seq === reportRequestSeq) {
      loading.value = false
    }
  }
}

/** 保存快照：persist=true 重新生成并落库，随后刷新快照列表 */
async function saveSnapshot() {
  await generateReport(true)
}

async function loadSnapshots(scope: string) {
  try {
    const resp = await getSnapshots(scope)
    snapshots.value = resp.snapshots
  } catch (e) {
    snapshots.value = []
  }
}

async function exportMarkdown() {
  try {
    // 历史快照模式：导出当前展示的历史报告（snapshotId 还原）
    if (viewingSnapshot.value && viewingSnapshotId.value != null) {
      const blob = await exportReport({ snapshotId: viewingSnapshotId.value })
      await downloadMarkdown(blob, `arch-diagnosis-snapshot-${viewingSnapshotId.value}.md`)
      return
    }
    const paths = realtimeProjectPaths.value
    if (paths.length === 0) {
      ElMessage.warning('请先选择项目')
      return
    }
    const blob = await exportReport({ projectPaths: paths })
    await downloadMarkdown(blob, 'arch-diagnosis.md')
  } catch (e: any) {
    ElMessage.error('导出失败: ' + (e.message || e))
  }
}

async function downloadMarkdown(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('导出成功')
}

/** 查看历史快照：加载该快照完整报告体并切换为历史模式 */
async function viewSnapshot(snapshotId: number) {
  const seq = ++reportRequestSeq
  loading.value = true
  try {
    const result = await getSnapshotReport(snapshotId)
    if (seq !== reportRequestSeq) {
      // 已有更新的加载（切换项目/查看其它快照）发起，丢弃本次过期结果
      return
    }
    report.value = result
    viewingSnapshotId.value = snapshotId
    activeTopTab.value = 'realtime'
  } catch (e: any) {
    if (seq !== reportRequestSeq) {
      return
    }
    ElMessage.error('加载历史报告失败: ' + (e.message || e))
  } finally {
    if (seq === reportRequestSeq) {
      loading.value = false
    }
  }
}

/** 发起 AI 深度分析：切到深度分析 tab 并启动工作流（面板没另选项目时，就分析页头当前选中的项目） */
function startDeepAnalysis() {
  activeTopTab.value = 'deep'
  nextTick(() => deepPanel.value?.start(realtimeProjectName.value))
}

// ──────────────────────────── 图表渲染 ────────────────────────────

function renderMethodTreemap() {
  const el = methodChartRef.value
  const items = methodHotspots.value
  if (!el || !items.length) return
  const W = el.clientWidth
  const H = el.clientHeight
  if (W < 50 || H < 50) return
  echarts.getInstanceByDom(el)?.dispose()
  const chart = echarts.init(el)
  chart.setOption({
    tooltip: {
      formatter: (p: any) =>
        `${p.data.methodName}（${p.data.className}）<br/>复杂度:${p.data.complexity} | 风险分:${p.data.riskScore}`
    },
    series: [{
      type: 'treemap',
      roam: false,
      leafDepth: 1,
      data: items.map(h => ({
        name: h.methodName,
        value: Math.max(1, h.complexity),
        methodName: h.methodName,
        className: h.className,
        complexity: h.complexity,
        riskScore: h.riskScore,
        itemStyle: { color: `rgba(${Math.round(h.riskScore * 255)},${Math.round((1 - h.riskScore) * 200)},0,0.85)` }
      })),
      label: { show: true, formatter: '{b}' },
      levels: [{ itemStyle: { borderWidth: 1, borderColor: '#fff', gapWidth: 2 } }]
    }]
  })
}

function renderGodClassChart() {
  const el = godChartRef.value
  const gods = godClasses.value
  if (!el || !gods.length) return
  const W = el.clientWidth
  const H = el.clientHeight
  if (W < 50 || H < 50) return
  const sorted = [...gods].sort((a, b) => b.wmc - a.wmc)
  echarts.getInstanceByDom(el)?.dispose()
  const chart = echarts.init(el)
  chart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 150, right: 30, top: 10, bottom: 30 },
    xAxis: { type: 'value', name: 'WMC' },
    yAxis: { type: 'category', data: sorted.map(g => shortName(g.className)), inverse: true },
    series: [{ type: 'bar', data: sorted.map(g => g.wmc), itemStyle: { color: '#e6a23c' } }]
  })
}

function renderHubClassChart() {
  const el = hubChartRef.value
  const hubs = hubClasses.value
  if (!el || !hubs.length) return
  const W = el.clientWidth
  const H = el.clientHeight
  if (W < 50 || H < 50) return
  const sorted = [...hubs].sort((a, b) => (b.inDegree + b.outDegree) - (a.inDegree + a.outDegree))
  echarts.getInstanceByDom(el)?.dispose()
  const chart = echarts.init(el)
  chart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['扇入', '扇出'] },
    grid: { left: 150, right: 30, top: 40, bottom: 30 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: sorted.map(g => shortName(g.className)), inverse: true },
    series: [
      { name: '扇入', type: 'bar', data: sorted.map(g => g.inDegree), itemStyle: { color: '#409eff' } },
      { name: '扇出', type: 'bar', data: sorted.map(g => g.outDegree), itemStyle: { color: '#67c23a' } }
    ]
  })
}

/** 章节/top-tab 切换或报告数据就绪后，重绘当前可见章节的图表 */
function renderCharts() {
  nextTick(() => {
    if (activeTopTab.value !== 'realtime') return
    if (activeChapter.value === 'method') renderMethodTreemap()
    else if (activeChapter.value === 'class') {
      renderGodClassChart()
      renderHubClassChart()
    }
  })
}

watch(activeChapter, renderCharts)
watch(activeTopTab, renderCharts)
watch(() => report.value, renderCharts)

// 项目（单选）变化 → 重置状态并展示该项目最新一条历史快照（不自动触发实时生成）
watch(realtimeProjectName, async (name) => {
  report.value = null
  viewingSnapshotId.value = null
  snapshots.value = []
  activeTopTab.value = 'realtime'
  detailCollapse.value = []
  if (name) {
    await loadSnapshots(realtimeProjectPaths.value[0] ?? '')
    if (snapshots.value.length > 0) {
      await viewSnapshot(snapshots.value[0].id)
    }
  }
})

// 项目池变化 → 同步单选选中项（移除失效项 / 缺省选中首个），池为只读来源
watch(() => appStore.selectedProjects, (projects) => {
  const names = projects.map(p => p.name)
  if (realtimeProjectName.value && !names.includes(realtimeProjectName.value)) {
    realtimeProjectName.value = ''
  }
  if (!realtimeProjectName.value && names.length > 0) {
    realtimeProjectName.value = names[0]
  }
}, { immediate: true })
</script>

<style scoped>
.architecture-diagnosis-view {
  padding: 16px;
}

.control-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.loading-container {
  padding: 20px;
}

.overview-grid {
  display: flex;
  gap: 24px;
  align-items: center;
}

.health-score {
  text-align: center;
  min-width: 120px;
}

.score-number {
  font-size: 48px;
  font-weight: bold;
}

.score-label {
  color: #909399;
  font-size: 14px;
}

.kpi-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  flex: 1;
}

.kpi-item {
  display: flex;
  justify-content: space-between;
  padding: 4px 8px;
  background: #f5f7fa;
  border-radius: 4px;
}

.kpi-label {
  color: #909399;
}

.kpi-value {
  font-weight: bold;
}

.issue-summary {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.issue-tag {
  text-align: center;
}

.issue-count {
  color: #909399;
  font-size: 12px;
}

.chapter-content h4 {
  margin: 12px 0 8px;
  color: #303133;
}

.chapter-content h4:first-child {
  margin-top: 0;
}

.module-dual-pane {
  display: flex;
  gap: 16px;
}

.pane-left {
  flex: 1.3;
  min-width: 0;
}

.pane-right {
  flex: 1;
  min-width: 0;
}

.method-treemap {
  width: 100%;
  height: 420px;
}

.class-chart {
  width: 100%;
  height: 300px;
}

.empty-snapshot {
  text-align: center;
  color: #909399;
  padding: 20px;
}

.desc-bar {
  margin-bottom: 12px;
}

.chapter-intro {
  color: #909399;
  font-size: 13px;
  margin-bottom: 8px;
}

.metric-desc {
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
  margin: 0 0 8px;
}
</style>
