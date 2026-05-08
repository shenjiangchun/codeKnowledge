<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Download, Check, Loading, Cpu, FolderOpened, SuccessFilled, WarningFilled, CircleCloseFilled, Monitor, Cpu as Terminal, CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { mcpApi, type McpStatus } from '@/api/mcp'

// 扩展 McpStatus 类型
interface ExtendedMcpStatus extends McpStatus {
  claudeCodeInstalled?: boolean
  claudeType?: string
  mcpConfigured?: boolean
}

const mcpInfo = ref<Record<string, unknown> | null>(null)
const loading = ref(true)
const downloading = ref(false)
const copySuccess = ref(false)

// 操作系统检测
const isWindows = navigator.platform.toLowerCase().includes('win')

// 后端状态
const backendRunning = ref(false)
const checkingBackend = ref(false)

// MCP 安装状态
const mcpStatus = ref<ExtendedMcpStatus | null>(null)
const checkingStatus = ref(false)

// 安装进度
const installing = ref(false)
const installStep = ref('')
const installLogs = ref<string[]>([])
const installSuccess = ref(false)
const installError = ref('')

// MCP 目录输入
const mcpDirInput = ref('')
// 目标项目目录（用于配置 MCP）
const projectDirInput = ref('')

// 加载 MCP 信息
onMounted(async () => {
  try {
    const response = await mcpApi.getInfo()
    // response 已经是后端返回的数据（axios 拦截器已处理）
    mcpInfo.value = response as unknown as Record<string, unknown>
    await checkBackend()
    await checkMcpStatus()
  } catch (error) {
    console.error('Failed to load MCP info:', error)
  } finally {
    loading.value = false
  }
})

// 检测后端
async function checkBackend() {
  checkingBackend.value = true
  try {
    const response = await fetch('/actuator/health')
    backendRunning.value = response.ok
  } catch {
    backendRunning.value = false
  } finally {
    checkingBackend.value = false
  }
}

// 检查 MCP 状态
async function checkMcpStatus() {
  checkingStatus.value = true
  try {
    const response = await mcpApi.checkStatus(mcpDirInput.value || undefined)
    // response 已经是后端返回的数据（axios 拦截器已处理）
    const data = response as unknown as ExtendedMcpStatus
    mcpStatus.value = data
    if (data.mcpDir && !mcpDirInput.value) {
      mcpDirInput.value = data.mcpDir
    }
  } catch {
    mcpStatus.value = { installed: false, message: '检查状态失败' }
  } finally {
    checkingStatus.value = false
  }
}

// 在线安装
async function startInstall() {
  if (!mcpDirInput.value) {
    ElMessage.warning('请输入 MCP 目录路径')
    return
  }

  installing.value = true
  installStep.value = ''
  installLogs.value = []
  installSuccess.value = false
  installError.value = ''

  try {
    await mcpApi.install(mcpDirInput.value, {
      onStep: (step) => {
        installStep.value = step
        installLogs.value.push(`[步骤] ${step}`)
      },
      onInfo: (info) => {
        installLogs.value.push(`[信息] ${info}`)
      },
      onSuccess: (msg) => {
        installLogs.value.push(`[成功] ${msg}`)
      },
      onWarning: (msg) => {
        installLogs.value.push(`[警告] ${msg}`)
      },
      onError: (error) => {
        installError.value = error
        installLogs.value.push(`[错误] ${error}`)
      },
      onLog: (log) => {
        installLogs.value.push(log)
      },
      onDone: () => {
        installSuccess.value = true
        installLogs.value.push('[完成] 安装成功！')
        checkMcpStatus()
      }
    }, projectDirInput.value || undefined)
  } catch (error) {
    installError.value = (error as Error).message
  } finally {
    installing.value = false
  }
}

// Claude Code CLI 配置命令
const claudeCodeCliCommand = computed(() => {
  const mcpPath = mcpDirInput.value || (isWindows
    ? 'C:/Users/你的用户名/projects/hisi-dev-tool-mcp/dist/index.js'
    : '/Users/你的用户名/projects/hisi-dev-tool-mcp/dist/index.js')
  return `claude mcp add hisi-dev-tool -e HISI_API_URL=http://localhost:8080/api/knowledge-graph -e HISI_LOG_API_URL=http://localhost:8080/api/log -- node ${mcpPath.replace(/\\/g, '/')}`
})

// Claude Desktop 配置
const claudeDesktopConfig = computed(() => {
  const mcpPath = mcpDirInput.value || (isWindows
    ? 'C:\\\\Users\\\\你的用户名\\\\projects\\\\hisi-dev-tool-mcp\\\\dist\\\\index.js'
    : '/Users/你的用户名/projects/hisi-dev-tool-mcp/dist/index.js')
  return JSON.stringify({
    mcpServers: {
      "hisi-dev-tool": {
        command: "node",
        args: [mcpPath.replace(/\\/g, '/')],
        env: {
          HISI_API_URL: "http://localhost:8080/api/knowledge-graph",
          HISI_LOG_API_URL: "http://localhost:8080/api/log"
        }
      }
    }
  }, null, 2)
})

// 复制文本
async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    copySuccess.value = true
    ElMessage.success('已复制到剪贴板')
    setTimeout(() => { copySuccess.value = false }, 2000)
  } catch {
    ElMessage.error('复制失败')
  }
}

// 下载 MCP
async function downloadMCP() {
  downloading.value = true
  try {
    const url = mcpApi.getDownloadUrl()
    const a = document.createElement('a')
    a.href = url
    a.download = 'hisi-dev-tool-mcp.zip'
    a.click()
    ElMessage.success('开始下载')
  } catch {
    ElMessage.error('下载失败')
  } finally {
    downloading.value = false
  }
}

// MCP 工具列表（含参数和示例）
const tools = [
  {
    name: 'find_callers',
    desc: '向上查找调用方',
    params: [
      { name: 'method', type: 'string', required: true, desc: '方法签名，支持完整签名、类.方法、仅方法名' },
      { name: 'project_dir', type: 'string', required: true, desc: '项目目录路径' },
    ],
    example: '帮我查找 HisiURIMethodChainToDBServiceImpl.chainGenerator 方法被哪些 URI 入口调用',
  },
  {
    name: 'find_callees',
    desc: '向下查找依赖',
    params: [
      { name: 'method', type: 'string', required: true, desc: '方法签名' },
      { name: 'project_dir', type: 'string', required: true, desc: '项目目录路径' },
      { name: 'max_depth', type: 'number', required: false, desc: '最大递归深度，默认10' },
    ],
    example: '分析 UserService.createUser 方法的下游依赖链',
  },
  {
    name: 'search_methods',
    desc: '搜索方法',
    params: [
      { name: 'keyword', type: 'string', required: true, desc: '搜索关键词' },
      { name: 'project', type: 'string', required: false, desc: '项目名称' },
    ],
    example: '搜索项目中所有包含 "upload" 的方法',
  },
  {
    name: 'list_projects',
    desc: '列出项目',
    params: [],
    example: '列出所有已分析的项目',
  },
  {
    name: 'list_uris',
    desc: '列出 URI',
    params: [
      { name: 'project', type: 'string', required: true, desc: '项目名称' },
    ],
    example: '列出 hisi-dev-tool 项目的所有 URI 入口',
  },
  {
    name: 'list_classes',
    desc: '列出项目中的类',
    params: [
      { name: 'project', type: 'string', required: false, desc: '项目名称' },
    ],
    example: '列出项目中所有的类',
  },
  {
    name: 'list_methods_in_class',
    desc: '列出类中的方法',
    params: [
      { name: 'class_name', type: 'string', required: true, desc: '类名' },
      { name: 'project', type: 'string', required: false, desc: '项目名称' },
    ],
    example: '列出 UserService 类中定义的所有方法',
  },
  {
    name: 'get_call_chain',
    desc: '获取 URI 调用链',
    params: [
      { name: 'uri', type: 'string', required: true, desc: 'URI 路径' },
    ],
    example: '获取 /api/users 接口的完整调用链',
  },
  {
    name: 'query_error_logs',
    desc: '查询错误日志',
    params: [
      { name: 'time_range', type: 'string', required: false, desc: '时间范围，如 now-15m、now-1h' },
      { name: 'size', type: 'number', required: false, desc: '返回数量，默认20' },
      { name: 'custom_dsl', type: 'string', required: false, desc: '自定义 DSL 查询' },
    ],
    example: '查询最近15分钟的错误日志',
  },
  {
    name: 'query_logs',
    desc: '查询日志',
    params: [
      { name: 'dsl_query', type: 'string', required: true, desc: 'DSL 查询语句（JSON 格式）' },
      { name: 'keyword', type: 'string', required: false, desc: '关键词搜索' },
      { name: 'size', type: 'number', required: false, desc: '返回数量，默认50' },
    ],
    example: '搜索包含 "NullPointerException" 的日志',
  },
  {
    name: 'analyze_interface',
    desc: '接口完整分析',
    params: [
      { name: 'uri', type: 'string', required: true, desc: '接口 URI 路径' },
      { name: 'include_code', type: 'boolean', required: false, desc: '是否包含代码实现，默认 true' },
      { name: 'max_depth', type: 'number', required: false, desc: '最大分析深度，默认10' },
    ],
    example: '分析 /api/projects 接口的完整实现逻辑',
  },
  {
    name: 'generate_interface_doc',
    desc: '生成接口文档',
    params: [
      { name: 'uri', type: 'string', required: true, desc: '接口 URI 路径' },
      { name: 'doc_format', type: 'string', required: false, desc: '文档格式：markdown 或 html' },
    ],
    example: '为 /api/users 接口生成 Markdown 格式的文档',
  },
  {
    name: 'check_code_standards',
    desc: '代码规范检查',
    params: [
      { name: 'uri', type: 'string', required: true, desc: '接口 URI 路径' },
      { name: 'check_types', type: 'array', required: false, desc: '检查类型：naming, exception, logging, complexity, duplication, security' },
    ],
    example: '检查 /api/users 接口的代码规范问题',
  },
]
</script>

<template>
  <div class="mcp-guide">
    <!-- 加载状态 -->
    <div v-if="loading" class="loading">
      <el-icon class="is-loading" :size="48"><Loading /></el-icon>
      <p>加载中...</p>
    </div>

    <!-- 主内容 -->
    <template v-else>
      <!-- 头部 -->
      <div class="header">
        <div class="header-content">
          <div class="title">
            <el-icon :size="40" color="#409EFF"><Cpu /></el-icon>
            <div>
              <h1>MCP 安装指南</h1>
              <p>一键安装 Claude MCP，让 AI 理解你的代码</p>
            </div>
          </div>
          <div class="backend-status" :class="{ running: backendRunning }">
            <el-icon v-if="checkingBackend" class="is-loading"><Loading /></el-icon>
            <el-icon v-else-if="backendRunning"><Check /></el-icon>
            <span v-else>×</span>
            <span>后端服务: {{ backendRunning ? '运行中' : '未启动' }}</span>
          </div>
        </div>
      </div>

      <!-- 安装状态卡片 -->
      <el-card class="status-card">
        <template #header>
          <div class="card-header">
            <div class="header-left">
              <span>MCP 安装状态</span>
              <el-tag v-if="mcpStatus?.claudeType" :type="mcpStatus.claudeCodeInstalled ? 'primary' : 'info'" size="small">
                {{ mcpStatus.claudeType }}
              </el-tag>
            </div>
            <el-button size="small" @click="checkMcpStatus" :loading="checkingStatus">
              刷新状态
            </el-button>
          </div>
        </template>

        <!-- Claude 类型检测 -->
        <div v-if="mcpStatus" class="claude-type-info">
          <div class="type-badge" :class="{ 'code-cli': mcpStatus.claudeCodeInstalled, 'desktop': !mcpStatus.claudeCodeInstalled }">
            <el-icon v-if="mcpStatus.claudeCodeInstalled"><Terminal /></el-icon>
            <el-icon v-else><Monitor /></el-icon>
            <span>{{ mcpStatus.claudeType }}</span>
          </div>
          <p class="type-desc">
            {{ mcpStatus.claudeCodeInstalled
              ? '已检测到 Claude Code CLI，将使用 claude mcp add 命令配置'
              : '未检测到 Claude Code CLI，将使用 Claude Desktop 配置文件' }}
          </p>
        </div>

        <el-divider />

        <div v-if="mcpStatus" class="status-info">
          <div class="status-item">
            <el-icon v-if="mcpStatus.packageJsonExists" color="#67C23A"><SuccessFilled /></el-icon>
            <el-icon v-else color="#F56C6C"><CircleCloseFilled /></el-icon>
            <span>package.json</span>
          </div>
          <div class="status-item">
            <el-icon v-if="mcpStatus.nodeModulesExists" color="#67C23A"><SuccessFilled /></el-icon>
            <el-icon v-else color="#E6A23C"><WarningFilled /></el-icon>
            <span>node_modules</span>
          </div>
          <div class="status-item">
            <el-icon v-if="mcpStatus.distExists" color="#67C23A"><SuccessFilled /></el-icon>
            <el-icon v-else color="#E6A23C"><WarningFilled /></el-icon>
            <span>dist (构建产物)</span>
          </div>
          <div class="status-item">
            <el-icon v-if="mcpStatus.mcpConfigured" color="#67C23A"><SuccessFilled /></el-icon>
            <el-icon v-else color="#E6A23C"><WarningFilled /></el-icon>
            <span>MCP 已配置</span>
          </div>
        </div>

        <div v-if="mcpStatus?.installed && mcpStatus?.mcpConfigured" class="installed-badge">
          <el-tag type="success" size="large">
            <el-icon><SuccessFilled /></el-icon>
            MCP 已安装并配置完成
          </el-tag>
        </div>
      </el-card>

      <!-- 在线安装 -->
      <el-card class="install-card">
        <template #header>
          <div class="card-header">
            <span>方式一：在线安装（推荐）</span>
            <el-tag type="success" size="small">实时进度</el-tag>
          </div>
        </template>

        <div class="install-steps">
          <div class="step">
            <div class="step-num">1</div>
            <div class="step-content">
              <strong>下载 MCP 包并解压</strong>
              <el-button type="primary" :loading="downloading" @click="downloadMCP">
                <el-icon><Download /></el-icon>
                下载 MCP
              </el-button>
            </div>
          </div>

          <div class="step">
            <div class="step-num">2</div>
            <div class="step-content">
              <strong>输入解压后的目录路径</strong>
              <div class="dir-input">
                <el-input
                  v-model="mcpDirInput"
                  placeholder="例如: C:\Users\用户名\projects\hisi-dev-tool-mcp"
                  :disabled="installing"
                >
                  <template #prepend>
                    <el-icon><FolderOpened /></el-icon>
                  </template>
                </el-input>
              </div>
            </div>
          </div>

          <div class="step" v-if="mcpStatus?.claudeCodeInstalled">
            <div class="step-num">2.5</div>
            <div class="step-content">
              <strong>输入目标项目目录（可选）</strong>
              <el-alert type="info" :closable="false" style="margin-bottom: 8px;">
                MCP 配置是按项目的。留空则配置到后端服务运行目录，建议填入您实际使用 Claude 的项目目录。
              </el-alert>
              <div class="dir-input">
                <el-input
                  v-model="projectDirInput"
                  placeholder="例如: C:\Users\用户名\projects\my-project"
                  :disabled="installing"
                >
                  <template #prepend>
                    <el-icon><FolderOpened /></el-icon>
                  </template>
                </el-input>
              </div>
            </div>
          </div>

          <div class="step">
            <div class="step-num">3</div>
            <div class="step-content">
              <strong>点击安装按钮</strong>
              <el-button
                type="success"
                :loading="installing"
                :disabled="!mcpDirInput"
                @click="startInstall"
              >
                <el-icon><Check /></el-icon>
                {{ installing ? '安装中...' : '开始安装' }}
              </el-button>
            </div>
          </div>
        </div>

        <!-- 安装进度 -->
        <div v-if="installing || installLogs.length > 0" class="install-progress">
          <div class="progress-header">
            <span v-if="installing" class="progress-title">
              <el-icon class="is-loading"><Loading /></el-icon>
              正在安装... {{ installStep }}
            </span>
            <span v-else-if="installSuccess" class="progress-title success">
              <el-icon><SuccessFilled /></el-icon>
              安装成功
            </span>
            <span v-else-if="installError" class="progress-title error">
              <el-icon><CircleCloseFilled /></el-icon>
              安装失败
            </span>
          </div>
          <div class="log-container">
            <div v-for="(log, index) in installLogs" :key="index" class="log-line">
              {{ log }}
            </div>
          </div>
        </div>
      </el-card>

      <!-- 手动配置 -->
      <el-card class="manual-card">
        <template #header>
          <div class="card-header">
            <span>方式二：手动配置</span>
            <el-tag type="info" size="small">高级用户</el-tag>
          </div>
        </template>

        <el-collapse>
          <el-collapse-item title="查看手动配置步骤" name="manual">
            <!-- Claude Code CLI 配置 -->
            <div v-if="mcpStatus?.claudeCodeInstalled" class="manual-config">
              <h4>Claude Code CLI 配置</h4>
              <el-alert type="warning" :closable="false" style="margin-bottom: 16px;">
                <template #title>
                  <strong>重要提示</strong>
                </template>
                Claude Code CLI 的 MCP 配置是<strong>按项目</strong>的，需要在目标项目目录下执行配置命令。
              </el-alert>
              <el-steps direction="vertical" :active="2">
                <el-step title="安装依赖并构建">
                  <template #description>
                    <div class="code-block">
                      <code>cd hisi-dev-tool-mcp && npm install && npm run build</code>
                    </div>
                  </template>
                </el-step>
                <el-step title="添加 MCP 服务器">
                  <template #description>
                    <p>执行以下命令：</p>
                    <div class="code-block">
                      <el-button class="copy-btn" size="small" @click="copyText(claudeCodeCliCommand)">复制</el-button>
                      <pre><code>{{ claudeCodeCliCommand }}</code></pre>
                    </div>
                    <p class="tip">或使用在线安装按钮自动配置</p>
                  </template>
                </el-step>
                <el-step title="验证配置">
                  <template #description>
                    <div class="code-block">
                      <code>claude mcp list</code>
                    </div>
                    <p>应该能看到 hisi-dev-tool 服务</p>
                  </template>
                </el-step>
              </el-steps>

              <el-divider content-position="left">全局配置（可选）</el-divider>
              <p class="tip">如果想在所有项目中使用 MCP，可以手动编辑全局配置文件：</p>
              <div class="code-block">
                <p>编辑文件：<code>{{ isWindows ? '~\\.claude\\settings.json' : '~/.claude/settings.json' }}</code></p>
                <p>在 <code>mcpServers</code> 中添加配置：</p>
                <el-button class="copy-btn" size="small" @click="copyText(claudeDesktopConfig)">复制</el-button>
                <pre><code>{{ claudeDesktopConfig }}</code></pre>
              </div>
            </div>

            <!-- Claude Desktop 配置 -->
            <div v-else class="manual-config">
              <h4>Claude Desktop 配置</h4>
              <el-steps direction="vertical" :active="3">
                <el-step title="安装依赖并构建">
                  <template #description>
                    <div class="code-block">
                      <code>cd hisi-dev-tool-mcp && npm install && npm run build</code>
                    </div>
                  </template>
                </el-step>
                <el-step title="配置 Claude Desktop">
                  <template #description>
                    <p>编辑配置文件：<code>{{ isWindows ? '%APPDATA%\\Claude\\claude_desktop_config.json' : '~/Library/Application Support/Claude/claude_desktop_config.json' }}</code></p>
                    <div class="code-block">
                      <el-button class="copy-btn" size="small" @click="copyText(claudeDesktopConfig)">复制</el-button>
                      <pre><code>{{ claudeDesktopConfig }}</code></pre>
                    </div>
                  </template>
                </el-step>
                <el-step title="重启 Claude Desktop" />
              </el-steps>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <!-- 可用工具 -->
      <el-card class="tools-card">
        <template #header>
          <div class="card-header">
            <span>安装后可用工具</span>
            <el-tag type="info" size="small">点击展开查看参数和示例</el-tag>
          </div>
        </template>

        <el-collapse accordion>
          <el-collapse-item v-for="tool in tools" :key="tool.name" :name="tool.name">
            <template #title>
              <div class="tool-title">
                <code class="tool-name">{{ tool.name }}</code>
                <span class="tool-desc">{{ tool.desc }}</span>
                <el-tag v-if="tool.params.some((p: any) => p.required)" type="danger" size="small">需要参数</el-tag>
              </div>
            </template>

            <!-- 参数说明 -->
            <div v-if="tool.params.length > 0" class="tool-params">
              <h5>参数说明</h5>
              <el-table :data="tool.params" size="small" border>
                <el-table-column prop="name" label="参数名" width="120">
                  <template #default="{ row }">
                    <code>{{ row.name }}</code>
                  </template>
                </el-table-column>
                <el-table-column prop="type" label="类型" width="80" />
                <el-table-column label="必填" width="60" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.required ? 'danger' : 'info'" size="small">
                      {{ row.required ? '是' : '否' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="desc" label="说明" />
              </el-table>
            </div>
            <div v-else class="tool-params">
              <el-tag type="success" size="small">无需参数</el-tag>
            </div>

            <!-- 示例提问 -->
            <div class="tool-example">
              <h5>示例提问</h5>
              <div class="example-box">
                <el-icon><Cpu /></el-icon>
                <span>{{ tool.example }}</span>
                <el-button size="small" text @click="copyText(tool.example)">
                  <el-icon><CopyDocument /></el-icon>
                </el-button>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <!-- 使用场景 -->
      <el-card class="usage-card">
        <template #header>
          <span>常见使用场景</span>
        </template>
        <el-collapse>
          <el-collapse-item title="场景一：技术方案设计时的影响分析" name="scenario1">
            <p>在设计技术方案时，需要评估修改某个方法会影响哪些 API 接口：</p>
            <div class="code-block">
              <code>帮我查找 UserService.updateUser 方法被哪些 URI 入口调用，项目目录是 C:/Users/xxx/projects/my-project</code>
            </div>
            <p class="tip">这将返回所有调用该方法的 API 接口列表，帮助评估影响范围。</p>
          </el-collapse-item>
          <el-collapse-item title="场景二：问题定位时的调用链追踪" name="scenario2">
            <p>遇到错误日志时，快速定位问题根源：</p>
            <ol>
              <li>首先查询错误日志：<code>查询最近15分钟的错误日志</code></li>
              <li>然后根据日志中的方法名查找调用链：<code>查找 xxx 方法的上游 URI 入口</code></li>
              <li>最后分析接口实现：<code>分析 /api/xxx 接口的完整实现逻辑</code></li>
            </ol>
          </el-collapse-item>
          <el-collapse-item title="场景三：代码审查时的接口文档生成" name="scenario3">
            <p>生成接口文档用于代码审查或交接：</p>
            <div class="code-block">
              <code>为 /api/projects 接口生成 Markdown 格式的文档</code>
            </div>
            <p class="tip">将自动生成包含调用流程、涉及方法等信息的结构化文档。</p>
          </el-collapse-item>
          <el-collapse-item title="场景四：代码规范检查" name="scenario4">
            <p>检查接口代码是否符合规范：</p>
            <div class="code-block">
              <code>检查 /api/users 接口的代码规范问题，包括命名规范、异常处理、安全问题</code>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <!-- 常见问题 -->
      <el-card class="faq-card">
        <template #header>
          <span>常见问题</span>
        </template>
        <el-collapse>
          <el-collapse-item title="MCP 工具不显示？" name="1">
            <template v-if="mcpStatus?.claudeCodeInstalled">
              <p><strong>Claude Code CLI 用户：</strong></p>
              <ol>
                <li>运行 <code>claude mcp list</code> 确认 hisi-dev-tool 已列出</li>
                <li>确认 <code>dist/index.js</code> 文件存在</li>
                <li>确认后端服务运行中</li>
              </ol>
            </template>
            <template v-else>
              <p><strong>Claude Desktop 用户：</strong></p>
              <ol>
                <li>确认配置文件 JSON 格式正确</li>
                <li>确认 <code>dist/index.js</code> 文件存在</li>
                <li>重启 Claude Desktop</li>
              </ol>
            </template>
          </el-collapse-item>
          <el-collapse-item title="如何验证 MCP 配置？" name="2">
            <template v-if="mcpStatus?.claudeCodeInstalled">
              <p>Claude Code CLI 用户运行：</p>
              <div class="code-block">
                <code>claude mcp list</code>
              </div>
              <p>应该看到 hisi-dev-tool 服务</p>
            </template>
            <template v-else>
              <p>检查配置文件是否存在并包含 hisi-dev-tool：</p>
              <div class="code-block">
                <code>cat {{ isWindows ? '%APPDATA%\\Claude\\claude_desktop_config.json' : '~/Library/Application Support/Claude/claude_desktop_config.json' }}</code>
              </div>
            </template>
          </el-collapse-item>
          <el-collapse-item title="API 调用失败？" name="3">
            <p>确保后端服务运行中：</p>
            <div class="code-block">
              <code>curl http://localhost:8080/actuator/health</code>
            </div>
          </el-collapse-item>
          <el-collapse-item title="调用链数据为空？" name="4">
            <p>需要先生成调用链数据：</p>
            <div class="code-block">
              <code>curl -X POST http://localhost:8080/api/method_chain/generate</code>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.mcp-guide {
  min-height: 100%;
  background: #f5f7fa;
  padding: 24px;
}

.loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  color: #909399;
}

.header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 32px;
  border-radius: 12px;
  color: white;
  margin-bottom: 24px;
}

.header-content {
  max-width: 1000px;
  margin: 0 auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.title {
  display: flex;
  align-items: center;
  gap: 16px;
}

.title h1 {
  margin: 0;
  font-size: 24px;
}

.title p {
  margin: 4px 0 0;
  opacity: 0.9;
  font-size: 14px;
}

.backend-status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: rgba(255,255,255,0.2);
  border-radius: 20px;
  font-size: 14px;
}

.backend-status.running {
  background: rgba(103, 194, 58, 0.3);
}

.status-card, .install-card, .manual-card, .tools-card, .faq-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-header .header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.claude-type-info {
  margin-bottom: 16px;
}

.type-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-radius: 20px;
  font-weight: 500;
  margin-bottom: 8px;
}

.type-badge.code-cli {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.type-badge.desktop {
  background: #f0f2f5;
  color: #606266;
}

.type-desc {
  font-size: 13px;
  color: #909399;
  margin: 0;
}

.status-info {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.installed-badge {
  margin-top: 16px;
  text-align: center;
}

.install-steps {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 16px 0;
}

.step {
  display: flex;
  gap: 16px;
}

.step-num {
  width: 32px;
  height: 32px;
  background: #409EFF;
  color: white;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
  flex-shrink: 0;
}

.step-content {
  flex: 1;
}

.step-content strong {
  display: block;
  margin-bottom: 8px;
}

.dir-input {
  max-width: 500px;
}

.install-progress {
  margin-top: 20px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.progress-header {
  padding: 12px 16px;
  background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
}

.progress-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
}

.progress-title.success {
  color: #67C23A;
}

.progress-title.error {
  color: #F56C6C;
}

.log-container {
  max-height: 300px;
  overflow-y: auto;
  padding: 12px 16px;
  background: #1e1e1e;
  font-family: 'Consolas', monospace;
  font-size: 13px;
}

.log-line {
  color: #d4d4d4;
  line-height: 1.6;
  white-space: pre-wrap;
}

.log-line:has([错误]) {
  color: #f56c6c;
}

.log-line:has([成功]) {
  color: #67c23a;
}

.log-line:has([警告]) {
  color: #e6a23c;
}

.code-block {
  position: relative;
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 16px;
  border-radius: 8px;
  margin: 8px 0;
}

.code-block .copy-btn {
  position: absolute;
  top: 8px;
  right: 8px;
}

.code-block pre, .code-block code {
  margin: 0;
  font-family: 'Consolas', monospace;
  font-size: 13px;
}

.tools-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.tool-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
}

.tool-item code {
  color: #409EFF;
  font-size: 13px;
}

.tool-item span {
  font-size: 12px;
  color: #909399;
}

.tool-title {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.tool-title .tool-name {
  color: #409EFF;
  font-weight: 600;
  background: rgba(64, 158, 255, 0.1);
  padding: 2px 8px;
  border-radius: 4px;
}

.tool-title .tool-desc {
  color: #606266;
  flex: 1;
}

.tool-params {
  margin-bottom: 16px;
}

.tool-params h5 {
  margin: 0 0 8px 0;
  color: #303133;
  font-size: 13px;
}

.tool-example {
  margin-top: 12px;
}

.tool-example h5 {
  margin: 0 0 8px 0;
  color: #303133;
  font-size: 13px;
}

.example-box {
  display: flex;
  align-items: center;
  gap: 8px;
  background: linear-gradient(135deg, #667eea20 0%, #764ba220 100%);
  border: 1px solid #667eea40;
  padding: 12px 16px;
  border-radius: 8px;
}

.example-box span {
  flex: 1;
  font-size: 13px;
  color: #303133;
}

.usage-card {
  margin-bottom: 16px;
}

.usage-card .tip {
  font-size: 12px;
  color: #909399;
  font-style: italic;
  margin-top: 8px;
}

.usage-card ol {
  padding-left: 20px;
}

.usage-card li {
  margin-bottom: 8px;
  line-height: 1.6;
}

.manual-config {
  margin-top: 8px;
}

.manual-config h4 {
  margin: 0 0 16px 0;
  color: #303133;
}

.manual-config .tip {
  font-size: 12px;
  color: #909399;
  font-style: italic;
}

@media (max-width: 768px) {
  .header-content {
    flex-direction: column;
    gap: 16px;
    text-align: center;
  }

  .tools-grid {
    grid-template-columns: 1fr;
  }

  .status-info {
    flex-direction: column;
    gap: 12px;
  }
}
</style>
