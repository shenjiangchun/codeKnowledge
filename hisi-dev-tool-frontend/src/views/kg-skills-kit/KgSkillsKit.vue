<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { kgSkillsKitApi, type KgSkill, type SkillGuide } from '@/api/kgSkillsKit'
import {
  Download,
  Delete,
  RefreshRight,
  Check,
  Close,
  Tools,
  StarFilled
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const skills = ref<KgSkill[]>([])
const loading = ref(false)
const installing = ref(false)
const skillsDir = ref('')
const kitVersion = ref('')
const installedCount = ref(0)
const guide = ref<SkillGuide | null>(null)
const guideVisible = ref(false)
const installSuccessGuideVisible = ref(false)

const oneFingerGuide = ref({
  threeSkills: [
    { name: 'kg-search', desc: '知识图谱混合检索，找代码', cmd: '/kg-search 查找处理支付的方法' },
    { name: 'kg-trace', desc: '调用链追踪，看上下游', cmd: '/kg-trace 分析订单创建的调用链' },
    { name: 'kg-diagnose', desc: '日志诊断，定位根因', cmd: '/kg-diagnose 分析这个错误日志的根因' }
  ],
  prerequisites: [
    'MCP hisi-mcp-server 已配置并连接',
    '项目已执行知识图谱生成',
    'Neo4j 和后端服务正常运行'
  ],
  flow: ['用户输入命令', 'Skill 调 MCP kg_list_projects', '弹出项目选择框（可多选）', '执行查询', '返回结果'],
  remember: ['找代码用 /kg-search', '看链路用 /kg-trace', '诊错误用 /kg-diagnose'],
  elements: ['MCP 要连', '图谱要生', '项目要选']
})

const categoryColors: Record<string, string> = {
  analysis: '#409EFF',
  diagnosis: '#E6A23C',
  generation: '#67C23A',
  operation: '#909399'
}

const allInstalled = computed(() => installedCount.value === skills.value.length)
const noneInstalled = computed(() => installedCount.value === 0)

async function loadKitList() {
  loading.value = true
  try {
    const response = await kgSkillsKitApi.getKitList()
    skills.value = response.skills
    skillsDir.value = response.skillsDir
    kitVersion.value = response.kitVersion
    installedCount.value = response.installedCount
  } catch (error) {
    ElMessage.error('加载套件列表失败')
  } finally {
    loading.value = false
  }
}

async function loadGuide() {
  try {
    guide.value = await kgSkillsKitApi.getGuide()
  } catch (error) {
    console.error('加载指南失败', error)
  }
}

async function handleInstall(skill: KgSkill) {
  if (skill.installed) {
    ElMessage.info('Skill 已安装')
    return
  }
  installing.value = true
  try {
    const result = await kgSkillsKitApi.installSkill(skill.id)
    if (result.success) {
      ElMessage.success(`${skill.name} 安装成功`)
      skill.installed = true
      skill.installPath = result.installPath
      installedCount.value++
    } else {
      ElMessage.warning(result.message)
    }
  } catch (error) {
    ElMessage.error(`安装 ${skill.name} 失败`)
  } finally {
    installing.value = false
  }
}

async function handleUninstall(skill: KgSkill) {
  if (!skill.installed) return
  try {
    await ElMessageBox.confirm(`确定要卸载 ${skill.name} 吗？`, '确认卸载', { type: 'warning' })
    installing.value = true
    const result = await kgSkillsKitApi.uninstallSkill(skill.id)
    if (result.success) {
      ElMessage.success(`${skill.name} 卸载成功`)
      skill.installed = false
      skill.installPath = undefined
      installedCount.value--
    }
  } catch {} finally {
    installing.value = false
  }
}

async function handleInstallAll() {
  try {
    await ElMessageBox.confirm('确定要一键安装全部 KG Skills 吗？', '确认安装', { type: 'info' })
    installing.value = true
    const result = await kgSkillsKitApi.installAll()
    if (result.success) {
      ElMessage.success(result.message)
      await loadKitList()
      installSuccessGuideVisible.value = true
    }
  } catch {} finally {
    installing.value = false
  }
}

async function handleUninstallAll() {
  if (noneInstalled.value) {
    ElMessage.info('没有已安装的 Skills')
    return
  }
  try {
    await ElMessageBox.confirm('确定要一键卸载全部已安装的 KG Skills 吗？', '确认卸载', { type: 'warning' })
    installing.value = true
    const result = await kgSkillsKitApi.uninstallAll()
    if (result.success) {
      ElMessage.success(result.message)
      await loadKitList()
    }
  } catch {} finally {
    installing.value = false
  }
}

function copyCommand(cmd: string) {
  navigator.clipboard.writeText(cmd).then(() => {
    ElMessage.success('命令已复制到剪贴板')
  })
}

onMounted(() => {
  loadKitList()
  loadGuide()
})
</script>

<template>
  <div class="kg-skills-kit">
    <div class="kit-header">
      <div class="header-left">
        <el-icon class="kit-icon"><Tools /></el-icon>
        <div class="kit-info">
          <h2>KG Skills 开发套件</h2>
          <div class="kit-meta">
            <span>版本 {{ kitVersion }}</span>
            <el-divider direction="vertical" />
            <span>已安装 {{ installedCount }}/{{ skills.length }}</span>
          </div>
        </div>
      </div>
      <div class="header-right">
        <el-button type="primary" :icon="Download" :loading="installing" :disabled="allInstalled" @click="handleInstallAll">一键安装全部</el-button>
        <el-button type="danger" :icon="Delete" :loading="installing" :disabled="noneInstalled" @click="handleUninstallAll">一键卸载全部</el-button>
        <el-button :icon="RefreshRight" :loading="loading" @click="loadKitList">刷新</el-button>
      </div>
    </div>

    <div class="skills-grid" v-loading="loading">
      <div class="skill-card" v-for="skill in skills" :key="skill.id">
        <div class="card-header">
          <div class="skill-title">
            <el-tag :color="categoryColors[skill.category]" effect="dark" size="small">{{ skill.category }}</el-tag>
            <span class="skill-name">{{ skill.name }}</span>
          </div>
          <el-tag v-if="skill.installed" type="success" size="small"><el-icon><Check /></el-icon> 已安装</el-tag>
          <el-tag v-else type="info" size="small"><el-icon><Close /></el-icon> 未安装</el-tag>
        </div>
        <div class="card-body">
          <div class="skill-desc">{{ skill.description }}</div>
          <div class="skill-tags">
            <el-tag v-for="tag in skill.tags.slice(0, 4)" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
          </div>
        </div>
        <div class="card-footer">
          <el-button v-if="skill.installed" type="danger" size="small" :icon="Delete" :loading="installing" @click="handleUninstall(skill)">卸载</el-button>
          <el-button v-else type="primary" size="small" :icon="Download" :loading="installing" @click="handleInstall(skill)">安装</el-button>
        </div>
      </div>
    </div>

    <!-- 安装成功后弹出的一指禅使用指南 -->
    <el-dialog v-model="installSuccessGuideVisible" title="🎉 安装成功！一指禅使用指南" width="700px" :close-on-click-modal="false" top="5vh">
      <div class="one-finger-guide">
        <div class="guide-section">
          <h3><el-icon><StarFilled /></el-icon> 安装已完成，接下来如何使用？</h3>
        </div>

        <div class="guide-section">
          <h4>⚡ 核心三剑客</h4>
          <div class="skills-table">
            <div class="skill-row" v-for="s in oneFingerGuide.threeSkills" :key="s.name">
              <el-tag type="primary" effect="dark">{{ s.name }}</el-tag>
              <span>{{ s.desc }}</span>
              <code @click="copyCommand(s.cmd)" class="clickable-cmd">{{ s.cmd }}</code>
            </div>
          </div>
        </div>

        <div class="guide-section">
          <h4>📋 使用三要素（前置条件）</h4>
          <div class="prerequisites">
            <div class="pre-item" v-for="pre in oneFingerGuide.prerequisites" :key="pre">
              <el-icon><Check /></el-icon>
              <span>{{ pre }}</span>
            </div>
          </div>
        </div>

        <div class="guide-section">
          <h4>🔄 交互流程</h4>
          <div class="flow-diagram">
            <div class="flow-step" v-for="(step, idx) in oneFingerGuide.flow" :key="step">
              <div class="step-node">{{ idx + 1 }}</div>
              <div class="step-label">{{ step }}</div>
              <div v-if="idx < oneFingerGuide.flow.length - 1" class="step-arrow">→</div>
            </div>
          </div>
        </div>

        <div class="guide-section highlight-section">
          <h4>💡 记住这三句话</h4>
          <div class="remember-box">
            <div class="remember-item" v-for="r in oneFingerGuide.remember" :key="r">{{ r }}</div>
          </div>
        </div>

        <div class="guide-section highlight-section">
          <h4>🔑 记住这三要素</h4>
          <div class="elements-box">
            <div class="element-item" v-for="e in oneFingerGuide.elements" :key="e">{{ e }}</div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="installSuccessGuideVisible = false">我已掌握，开始使用</el-button>
      </template>
    </el-dialog>

    <!-- 详细指南 -->
    <el-dialog v-model="guideVisible" title="KG Skills 详细使用指南" width="600px">
      <div class="guide-content" v-if="guide">
        <h3>{{ guide.kitName }} {{ guide.version }}</h3>
        <p class="guide-desc">{{ guide.description }}</p>
        <el-divider />
        <h4>核心特性</h4>
        <ul class="guide-list"><li v-for="f in guide.features" :key="f">{{ f }}</li></ul>
        <h4>使用方式</h4>
        <div class="usage-examples"><el-tag v-for="u in guide.usage" :key="u" effect="plain" class="usage-tag">{{ u }}</el-tag></div>
        <h4>前置条件</h4>
        <ul class="guide-list"><li v-for="p in guide.prerequisites" :key="p">{{ p }}</li></ul>
        <h4>配套 MCP 工具</h4>
        <div class="mcp-tools"><el-tag v-for="t in guide.mcpTools" :key="t" type="info" effect="plain" size="small">{{ t }}</el-tag></div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.kg-skills-kit { padding: 20px; }
.kit-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; padding: 16px 20px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); border-radius: 12px; color: white; }
.header-left { display: flex; align-items: center; gap: 16px; }
.kit-icon { font-size: 32px; }
.kit-info h2 { margin: 0; font-size: 20px; }
.kit-meta { font-size: 13px; opacity: 0.9; }
.header-right { display: flex; gap: 8px; }
.skills-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; }
.skill-card { border: 1px solid #e4e7ed; border-radius: 8px; padding: 16px; background: white; transition: all 0.3s; }
.skill-card:hover { border-color: #409EFF; box-shadow: 0 4px 12px rgba(64, 158, 255, 0.1); }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.skill-title { display: flex; align-items: center; gap: 8px; }
.skill-name { font-size: 16px; font-weight: 500; }
.card-body { margin-bottom: 12px; }
.skill-desc { font-size: 13px; color: #606266; margin-bottom: 8px; line-height: 1.5; }
.skill-tags { display: flex; gap: 4px; margin-bottom: 8px; }
.card-footer { display: flex; justify-content: space-between; align-items: center; }
.one-finger-guide { padding: 10px; }
.guide-section { margin-bottom: 20px; }
.guide-section h3 { display: flex; align-items: center; gap: 8px; margin: 0 0 8px; color: #303133; }
.guide-section h4 { margin: 0 0 12px; color: #303133; font-size: 15px; }
.skills-table { display: flex; flex-direction: column; gap: 8px; }
.skill-row { display: grid; grid-template-columns: 120px 1fr 1fr; gap: 12px; align-items: center; padding: 8px 12px; background: #f5f7fa; border-radius: 6px; }
.clickable-cmd { font-family: monospace; background: #e6f7ff; padding: 4px 8px; border-radius: 4px; font-size: 12px; cursor: pointer; }
.clickable-cmd:hover { background: #d6f0ff; }
.prerequisites { display: flex; flex-direction: column; gap: 8px; }
.pre-item { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #606266; }
.pre-item .el-icon { color: #67C23A; }
.flow-diagram { display: flex; align-items: center; gap: 8px; padding: 12px; background: #f5f7fa; border-radius: 8px; flex-wrap: wrap; }
.flow-step { display: flex; align-items: center; gap: 8px; }
.step-node { width: 24px; height: 24px; border-radius: 50%; background: #409EFF; color: white; display: flex; justify-content: center; align-items: center; font-size: 12px; font-weight: 500; }
.step-label { font-size: 12px; color: #606266; }
.step-arrow { color: #909399; font-size: 16px; }
.highlight-section { background: linear-gradient(135deg, #f5f7fa 0%, #e8f4f8 100%); padding: 12px; border-radius: 8px; }
.remember-box { display: flex; flex-direction: column; gap: 6px; }
.remember-item { font-size: 14px; color: #409EFF; font-weight: 500; }
.elements-box { display: flex; gap: 12px; }
.element-item { font-size: 13px; color: #E6A23C; font-weight: 500; padding: 4px 12px; background: #fdf6ec; border-radius: 4px; }
.guide-content h3 { margin: 0 0 8px; }
.guide-desc { color: #606266; margin-bottom: 16px; }
.guide-content h4 { margin: 16px 0 8px; color: #303133; }
.guide-list { margin: 0; padding-left: 20px; color: #606266; }
.guide-list li { margin-bottom: 4px; }
.usage-examples { display: flex; flex-direction: column; gap: 8px; }
.usage-tag { font-family: monospace; }
.mcp-tools { display: flex; flex-wrap: wrap; gap: 4px; }
</style>