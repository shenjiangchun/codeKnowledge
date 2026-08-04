<template>
  <div class="settings-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>系统设置</span>
          <div class="header-actions">
            <el-button type="primary" @click="saveConfig" :loading="saving">
              <el-icon><Check /></el-icon>
              保存
            </el-button>
            <el-button @click="loadConfig" :loading="loading">
              <el-icon><Refresh /></el-icon>
              重新加载
            </el-button>
          </div>
        </div>
      </template>

      <el-skeleton :rows="8" animated v-if="loading && !configLoaded" />

      <el-form v-else label-width="200px" label-position="right">
        <!-- 服务配置 -->
        <el-divider content-position="left">服务配置</el-divider>
        <el-form-item label="服务端口">
          <el-input-number v-model="form.serverPort" :min="1024" :max="65535" />
        </el-form-item>

        <!-- Neo4j -->
        <el-divider content-position="left">Neo4j 图数据库</el-divider>
        <el-form-item label="连接地址">
          <el-input v-model="form.neo4jUri" placeholder="bolt://127.0.0.1:7687" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.neo4jUsername" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.neo4jPassword" type="password" show-password />
        </el-form-item>

        <!-- ==================== 向量生成模型（Embedding） ==================== -->
        <el-divider content-position="left">向量生成模型（Embedding）</el-divider>
        <el-form-item label="快速选择供应商">
          <el-select v-model="embeddingPresetKey" placeholder="选择预置供应商" @change="onEmbeddingPresetChange" style="width: 320px">
            <el-option
              v-for="p in EMBEDDING_PRESETS"
              :key="p.key"
              :label="p.label"
              :value="p.key"
            />
            <el-option label="自定义" value="custom" />
          </el-select>
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.embeddingApiKey" type="password" show-password placeholder="填入对应平台的 API Key" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.embeddingBaseUrl" placeholder="https://api.siliconflow.cn/v1" />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="form.embeddingModel" placeholder="Qwen/Qwen3-VL-Embedding-8B" />
        </el-form-item>
        <el-form-item label="向量维度">
          <el-input-number v-model="form.embeddingDimension" :min="64" :max="8192" />
        </el-form-item>
        <el-collapse v-model="embeddingAdvanced" style="margin-bottom: 16px">
          <el-collapse-item title="高级选项" name="advanced">
            <el-form-item label="超时（毫秒）">
              <el-input-number v-model="form.embeddingTimeout" :min="5000" :max="120000" :step="5000" />
            </el-form-item>
            <el-form-item label="最大重试">
              <el-input-number v-model="form.embeddingMaxRetries" :min="0" :max="10" />
            </el-form-item>
            <el-form-item label="重试基础延迟（毫秒）">
              <el-input-number v-model="form.embeddingRetryDelay" :min="1000" :max="300000" :step="1000" />
            </el-form-item>
            <el-form-item label="自定义请求头">
              <div style="width: 500px">
                <div v-for="(_value, key) in form.embeddingHeaders" :key="key" class="header-row">
                  <el-input :model-value="String(key)" placeholder="Header Name" style="width: 180px; margin-right: 8px" disabled />
                  <el-input v-model="form.embeddingHeaders[key]" placeholder="Header Value" style="width: 200px" />
                  <el-button type="danger" icon="Delete" @click="removeEmbeddingHeader(key)" style="margin-left: 8px" />
                </div>
                <div class="header-add-row">
                  <el-input v-model="newHeaderName" placeholder="Header Name" style="width: 180px; margin-right: 8px" />
                  <el-input v-model="newHeaderValue" placeholder="Header Value" style="width: 200px" />
                  <el-button type="primary" icon="Plus" @click="addEmbeddingHeader" style="margin-left: 8px" />
                </div>
              </div>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>

        <!-- ==================== 自然语言生成模型（Text） ==================== -->
        <el-divider content-position="left">自然语言生成模型（描述生成）</el-divider>
        <el-form-item label="快速选择供应商">
          <el-select v-model="textPresetKey" placeholder="选择预置供应商" @change="onTextPresetChange" style="width: 320px">
            <el-option
              v-for="p in TEXT_PRESETS"
              :key="p.key"
              :label="p.label"
              :value="p.key"
            />
            <el-option label="自定义" value="custom" />
          </el-select>
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.textApiKey" type="password" show-password placeholder="填入对应平台的 API Key" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.textBaseUrl" placeholder="https://open.bigmodel.cn/api/paas/v4" />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="form.textModel" placeholder="glm-4-flash" />
        </el-form-item>
        <el-collapse v-model="textAdvanced" style="margin-bottom: 16px">
          <el-collapse-item title="高级选项" name="advanced">
            <el-form-item label="Temperature">
              <el-input-number v-model="form.textTemperature" :min="0" :max="2" :step="0.1" :precision="1" />
            </el-form-item>
            <el-form-item label="Max Tokens">
              <el-input-number v-model="form.textMaxTokens" :min="50" :max="4096" :step="50" />
            </el-form-item>
            <el-form-item label="超时（毫秒）">
              <el-input-number v-model="form.textTimeout" :min="5000" :max="120000" :step="5000" />
            </el-form-item>
            <el-form-item label="最大重试">
              <el-input-number v-model="form.textMaxRetries" :min="0" :max="10" />
            </el-form-item>
            <el-form-item label="重试基础延迟（毫秒）">
              <el-input-number v-model="form.textRetryDelay" :min="1000" :max="300000" :step="1000" />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>

        <!-- 项目 -->
        <el-divider content-position="left">项目配置</el-divider>
        <el-form-item label="项目目录">
          <el-input v-model="form.projectDir" />
        </el-form-item>

        <!-- 日志级别 -->
        <el-divider content-position="left">日志级别</el-divider>
        <el-form-item label="根日志级别">
          <el-select v-model="form.logLevelRoot">
            <el-option label="DEBUG" value="debug" />
            <el-option label="INFO" value="info" />
            <el-option label="WARN" value="warn" />
            <el-option label="ERROR" value="error" />
          </el-select>
        </el-form-item>

        <!-- CORS -->
        <el-divider content-position="left">CORS 跨域</el-divider>
        <el-form-item label="允许的源">
          <el-input v-model="form.corsOrigins" placeholder="http://localhost:5173,http://localhost:3000" />
        </el-form-item>

        <!-- HTTP 代理 -->
        <el-divider content-position="left">
          HTTP 代理
          <el-tag size="small" type="success" style="margin-left: 8px">实时生效</el-tag>
        </el-divider>
        <el-form-item label="启用代理">
          <el-switch v-model="proxyForm.enabled" />
        </el-form-item>
        <el-form-item label="代理地址" v-if="proxyForm.enabled">
          <el-input v-model="proxyForm.host" placeholder="proxy.huawei.com" style="width: 300px" />
        </el-form-item>
        <el-form-item label="端口" v-if="proxyForm.enabled">
          <el-input-number v-model="proxyForm.port" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="代理类型" v-if="proxyForm.enabled">
          <el-select v-model="proxyForm.type" style="width: 120px">
            <el-option label="HTTP" value="HTTP" />
            <el-option label="SOCKS" value="SOCKS" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户名" v-if="proxyForm.enabled">
          <el-input v-model="proxyForm.username" placeholder="可选" />
        </el-form-item>
        <el-form-item label="密码" v-if="proxyForm.enabled">
          <el-input v-model="proxyForm.password" type="password" show-password placeholder="可选" />
        </el-form-item>
        <el-form-item label="不代理地址" v-if="proxyForm.enabled">
          <el-input v-model="proxyForm.nonProxyHosts" placeholder="localhost,127.0.0.1" />
        </el-form-item>
        <el-form-item label="禁用SSL验证" v-if="proxyForm.enabled">
          <el-switch v-model="proxyForm.disableSslVerification" />
          <span style="color: #909399; margin-left: 12px; font-size: 12px">仅用于内网/测试环境，生产环境请勿开启</span>
        </el-form-item>
        <el-form-item v-if="proxyForm.enabled">
          <el-button type="primary" @click="saveProxy" :loading="savingProxy">
            应用代理（立即生效）
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="configLoaded"
        type="info"
        :closable="false"
        show-icon
        style="margin-top: 16px"
      >
        修改保存后需要重启后端服务才能生效。
        <el-button v-if="isElectron" type="text" @click="restartBackend">点击重启</el-button>
      </el-alert>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'

// ==================== 预置供应商 ====================

interface EmbeddingPreset {
  key: string
  label: string
  baseUrl: string
  model: string
  dimension: number
  apiKey?: string
  csbToken?: string
}

interface TextPreset {
  key: string
  label: string
  baseUrl: string
  model: string
}

const EMBEDDING_PRESETS: EmbeddingPreset[] = [
  { key: 'siliconflow', label: '硅基流动 - Qwen3-VL-Embedding-8B (4096d)', baseUrl: 'https://api.siliconflow.cn/v1', model: 'Qwen/Qwen3-VL-Embedding-8B', dimension: 4096 },
  { key: 'zhipu', label: '智谱AI - embedding-3 (2048d)', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'embedding-3', dimension: 2048 },
  { key: 'iflytek', label: '科大讯飞 - xop3qwen8bembedding (768d)', baseUrl: 'https://maas-api.cn-huabei-1.xf-yun.com/v2', model: 'xop3qwen8bembedding', dimension: 768 },
  {
    key: 'huawei-qwen3',
    label: '华为云 - Qwen3-Embedding-8B (4096d)',
    baseUrl: 'http://onlineservice.cn-southwest-2.roma.huawei.com:8085/csb-inner-service/rest/infers/91d5b5d2-77cc-49bc-ab5d-aafe8e48e555?endpoint=infer-modelarts-cn-southwest-2.myhuaweicloud.com&path=/v1',
    model: 'Qwen3-Embedding-8B',
    dimension: 4096,
    apiKey: '***REMOVED_API_KEY***',
    csbToken: '***REMOVED_TOKEN***'
  },
]

const TEXT_PRESETS: TextPreset[] = [
  { key: 'zhipu-flash', label: '智谱AI - glm-4-flash', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
]

// ==================== 表单类型 ====================

interface ConfigForm {
  serverPort: number
  neo4jUri: string
  neo4jUsername: string
  neo4jPassword: string
  // Embedding model
  embeddingApiKey: string
  embeddingBaseUrl: string
  embeddingModel: string
  embeddingDimension: number
  embeddingTimeout: number
  embeddingMaxRetries: number
  embeddingRetryDelay: number
  embeddingHeaders: Record<string, string>
  // Text model
  textApiKey: string
  textBaseUrl: string
  textModel: string
  textTemperature: number
  textMaxTokens: number
  textTimeout: number
  textMaxRetries: number
  textRetryDelay: number
  // Others
  projectDir: string
  logLevelRoot: string
  corsOrigins: string
}

const loading = ref(false)
const saving = ref(false)
const savingProxy = ref(false)
const configLoaded = ref(false)

const embeddingPresetKey = ref('custom')
const textPresetKey = ref('custom')
const embeddingAdvanced = ref<string[]>([])
const textAdvanced = ref<string[]>([])
const newHeaderName = ref('')
const newHeaderValue = ref('')

// Detect Electron environment
const isElectron = typeof window !== 'undefined' && !!(window as unknown as Record<string, unknown>).electronAPI

const form = reactive<ConfigForm>({
  serverPort: 8080,
  neo4jUri: 'bolt://127.0.0.1:7687',
  neo4jUsername: 'neo4j',
  neo4jPassword: '',
  // Embedding
  embeddingApiKey: '',
  embeddingBaseUrl: 'https://api.siliconflow.cn/v1',
  embeddingModel: 'Qwen/Qwen3-VL-Embedding-8B',
  embeddingDimension: 4096,
  embeddingTimeout: 30000,
  embeddingMaxRetries: 3,
  embeddingRetryDelay: 60000,
  embeddingHeaders: {},
  // Text
  textApiKey: '',
  textBaseUrl: 'https://open.bigmodel.cn/api/paas/v4',
  textModel: 'glm-4-flash',
  textTemperature: 0.1,
  textMaxTokens: 200,
  textTimeout: 30000,
  textMaxRetries: 3,
  textRetryDelay: 60000,
  // Others
  projectDir: '',
  logLevelRoot: 'info',
  corsOrigins: 'http://localhost:5173,http://localhost:3000',
})

// 代理配置（独立于主配置，支持实时生效）
const proxyForm = reactive({
  enabled: false,
  host: '',
  port: 0,
  type: 'HTTP',
  username: '',
  password: '',
  nonProxyHosts: 'localhost,127.0.0.1',
  disableSslVerification: false,
})

// ==================== 预置选择处理 ====================

function onEmbeddingPresetChange(key: string) {
  if (key === 'custom') return
  const preset = EMBEDDING_PRESETS.find(p => p.key === key)
  if (preset) {
    form.embeddingBaseUrl = preset.baseUrl
    form.embeddingModel = preset.model
    form.embeddingDimension = preset.dimension
    // Set API Key if provided
    if (preset.apiKey) {
      form.embeddingApiKey = preset.apiKey
    }
    // Set CSB Token if provided
    form.embeddingHeaders = {}
    if (preset.csbToken) {
      form.embeddingHeaders['csb-token'] = preset.csbToken
    }
  }
}

function addEmbeddingHeader() {
  const name = newHeaderName.value.trim()
  const value = newHeaderValue.value.trim()
  if (name && value) {
    form.embeddingHeaders[name] = value
    newHeaderName.value = ''
    newHeaderValue.value = ''
  }
}

function removeEmbeddingHeader(key: string) {
  delete form.embeddingHeaders[key]
}

function onTextPresetChange(key: string) {
  if (key === 'custom') return
  const preset = TEXT_PRESETS.find(p => p.key === key)
  if (preset) {
    form.textBaseUrl = preset.baseUrl
    form.textModel = preset.model
  }
}

/** 根据当前 baseUrl+model 推断选中的预置 */
function detectEmbeddingPreset(): string {
  const match = EMBEDDING_PRESETS.find(
    p => p.baseUrl === form.embeddingBaseUrl && p.model === form.embeddingModel
  )
  return match ? match.key : 'custom'
}

function detectTextPreset(): string {
  const match = TEXT_PRESETS.find(
    p => p.baseUrl === form.textBaseUrl && p.model === form.textModel
  )
  return match ? match.key : 'custom'
}

// ==================== Deep helpers ====================

function deepGet(obj: Record<string, unknown>, path: string): unknown {
  return path.split('.').reduce((o: unknown, k) => {
    if (o && typeof o === 'object') return (o as Record<string, unknown>)[k]
    return undefined
  }, obj)
}

function deepSet(obj: Record<string, unknown>, path: string, value: unknown): void {
  const keys = path.split('.')
  let current: Record<string, unknown> = obj
  for (let i = 0; i < keys.length - 1; i++) {
    if (!current[keys[i]] || typeof current[keys[i]] !== 'object') {
      current[keys[i]] = {}
    }
    current = current[keys[i]] as Record<string, unknown>
  }
  current[keys[keys.length - 1]] = value
}

// ==================== YAML <-> Form 映射 ====================

function mapToForm(config: Record<string, unknown>): void {
  form.serverPort = (deepGet(config, 'server.port') as number) ?? 8080
  form.neo4jUri = (deepGet(config, 'neo4j.uri') as string) ?? ''
  form.neo4jUsername = (deepGet(config, 'neo4j.username') as string) ?? ''
  form.neo4jPassword = (deepGet(config, 'neo4j.password') as string) ?? ''
  // Embedding
  form.embeddingApiKey = (deepGet(config, 'embedding.api-key') as string) ?? ''
  form.embeddingBaseUrl = (deepGet(config, 'embedding.base-url') as string) ?? 'https://api.siliconflow.cn/v1'
  form.embeddingModel = (deepGet(config, 'embedding.model') as string) ?? 'Qwen/Qwen3-VL-Embedding-8B'
  form.embeddingDimension = (deepGet(config, 'embedding.dimension') as number) ?? 4096
  form.embeddingTimeout = (deepGet(config, 'embedding.timeout') as number) ?? 30000
  form.embeddingMaxRetries = (deepGet(config, 'embedding.max-retries') as number) ?? 3
  form.embeddingRetryDelay = (deepGet(config, 'embedding.retry-base-delay-ms') as number) ?? 60000
  const headers = deepGet(config, 'embedding.headers') as Record<string, string>
  form.embeddingHeaders = headers ? { ...headers } : {}
  // Text model
  form.textApiKey = (deepGet(config, 'text-model.api-key') as string) ?? ''
  form.textBaseUrl = (deepGet(config, 'text-model.base-url') as string) ?? 'https://open.bigmodel.cn/api/paas/v4'
  form.textModel = (deepGet(config, 'text-model.model') as string) ?? 'glm-4-flash'
  form.textTemperature = (deepGet(config, 'text-model.temperature') as number) ?? 0.1
  form.textMaxTokens = (deepGet(config, 'text-model.max-tokens') as number) ?? 200
  form.textTimeout = (deepGet(config, 'text-model.timeout') as number) ?? 30000
  form.textMaxRetries = (deepGet(config, 'text-model.max-retries') as number) ?? 3
  form.textRetryDelay = (deepGet(config, 'text-model.retry-base-delay-ms') as number) ?? 60000
  // Others
  form.projectDir = (deepGet(config, 'app.project_dir') as string) ?? ''
  form.logLevelRoot = (deepGet(config, 'logging.level.root') as string) ?? 'info'
  form.corsOrigins = (deepGet(config, 'cors.allowed-origins') as string) ?? ''

  // 自动识别预置
  embeddingPresetKey.value = detectEmbeddingPreset()
  textPresetKey.value = detectTextPreset()
}

function formToConfig(config: Record<string, unknown>): Record<string, unknown> {
  const result = JSON.parse(JSON.stringify(config)) as Record<string, unknown>
  deepSet(result, 'server.port', form.serverPort)
  deepSet(result, 'neo4j.uri', form.neo4jUri)
  deepSet(result, 'neo4j.username', form.neo4jUsername)
  deepSet(result, 'neo4j.password', form.neo4jPassword)
  // Embedding
  deepSet(result, 'embedding.api-key', form.embeddingApiKey)
  deepSet(result, 'embedding.base-url', form.embeddingBaseUrl)
  deepSet(result, 'embedding.model', form.embeddingModel)
  deepSet(result, 'embedding.dimension', form.embeddingDimension)
  deepSet(result, 'embedding.timeout', form.embeddingTimeout)
  deepSet(result, 'embedding.max-retries', form.embeddingMaxRetries)
  deepSet(result, 'embedding.retry-base-delay-ms', form.embeddingRetryDelay)
  deepSet(result, 'embedding.headers', form.embeddingHeaders)
  // Text model
  deepSet(result, 'text-model.api-key', form.textApiKey)
  deepSet(result, 'text-model.base-url', form.textBaseUrl)
  deepSet(result, 'text-model.model', form.textModel)
  deepSet(result, 'text-model.temperature', form.textTemperature)
  deepSet(result, 'text-model.max-tokens', form.textMaxTokens)
  deepSet(result, 'text-model.timeout', form.textTimeout)
  deepSet(result, 'text-model.max-retries', form.textMaxRetries)
  deepSet(result, 'text-model.retry-base-delay-ms', form.textRetryDelay)
  // Others
  deepSet(result, 'app.project_dir', form.projectDir)
  deepSet(result, 'logging.level.root', form.logLevelRoot)
  deepSet(result, 'cors.allowed-origins', form.corsOrigins)
  return result
}

// ==================== 配置加载/保存 ====================

let rawConfig: Record<string, unknown> = {}

const loadConfig = async () => {
  loading.value = true
  try {
    if (isElectron) {
      const api = (window as unknown as Record<string, unknown>).electronAPI as Record<string, Function>
      rawConfig = await api.getConfig() as Record<string, unknown>
    } else {
      const resp = await request.get('/settings/config') as { data?: Record<string, unknown> } & Record<string, unknown>
      rawConfig = (resp.data ?? resp) as Record<string, unknown>
    }
    mapToForm(rawConfig)
    configLoaded.value = true
  } catch (err) {
    ElMessage.error('加载配置失败')
  } finally {
    loading.value = false
  }
}

const saveConfig = async () => {
  saving.value = true
  try {
    const updated = formToConfig(rawConfig)
    if (isElectron) {
      const api = (window as unknown as Record<string, unknown>).electronAPI as Record<string, Function>
      await api.saveConfig(updated)
    } else {
      await request.post('/settings/config', updated)
    }
    rawConfig = updated
    ElMessage.success('配置已保存，请重启后端服务生效')
  } catch (err) {
    ElMessage.error('保存配置失败')
  } finally {
    saving.value = false
  }
}

// ==================== 代理配置（实时生效） ====================

const loadProxy = async () => {
  try {
    const resp = await request.get('/settings/proxy') as { data?: Record<string, unknown> } & Record<string, unknown>
    const data = (resp.data ?? resp) as Record<string, unknown> & {
      enabled?: boolean
      host?: string
      port?: number
      type?: string
      username?: string
      password?: string
      nonProxyHosts?: string
      disableSslVerification?: boolean
    }
    proxyForm.enabled = data.enabled ?? false
    proxyForm.host = data.host ?? ''
    proxyForm.port = data.port ?? 0
    proxyForm.type = data.type ?? 'HTTP'
    proxyForm.username = data.username ?? ''
    proxyForm.password = data.password ?? ''
    proxyForm.nonProxyHosts = data.nonProxyHosts ?? 'localhost,127.0.0.1'
    proxyForm.disableSslVerification = data.disableSslVerification ?? false
  } catch {
    // 代理接口不可用时静默处理
  }
}

const saveProxy = async () => {
  savingProxy.value = true
  try {
    await request.post('/settings/proxy', {
      enabled: proxyForm.enabled,
      host: proxyForm.host,
      port: proxyForm.port,
      type: proxyForm.type,
      username: proxyForm.username,
      password: proxyForm.password,
      nonProxyHosts: proxyForm.nonProxyHosts,
      disableSslVerification: proxyForm.disableSslVerification,
    })
    ElMessage.success('代理配置已生效（无需重启）')
  } catch {
    ElMessage.error('保存代理配置失败')
  } finally {
    savingProxy.value = false
  }
}

const restartBackend = async () => {
  if (!isElectron) return
  try {
    const api = (window as unknown as Record<string, unknown>).electronAPI as Record<string, Function>
    const result = await api.restartBackend() as { success: boolean; error?: string }
    if (result.success) {
      ElMessage.success('后端已重启')
    } else {
      ElMessage.error(`重启失败: ${result.error}`)
    }
  } catch {
    ElMessage.error('重启失败')
  }
}

onMounted(() => {
  loadConfig()
  loadProxy()
})
</script>

<style scoped>
.settings-view {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.el-divider {
  margin: 24px 0 16px;
}

.el-form-item {
  margin-bottom: 16px;
}

.el-collapse {
  border: none;
  margin-left: 200px;
  max-width: 600px;
}

:deep(.el-collapse-item__header) {
  font-size: 13px;
  color: #909399;
  height: 36px;
  line-height: 36px;
}

:deep(.el-collapse-item__wrap) {
  border-bottom: none;
}

.header-row, .header-add-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
</style>
