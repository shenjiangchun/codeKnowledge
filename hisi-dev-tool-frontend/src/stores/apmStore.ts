import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { apmApi } from '@/api/apmDebug'
import { knowledgeGraphApi } from '@/api/knowledgeGraph'
import { useApmWebSocket } from '@/composables/useApmWebSocket'
import type {
  ApmSession,
  ApmSessionStatus,
  ApmSpan,
  KgProject,
  KgEntryPoint,
  RequestConfig,
  DebugReport,
  ExecuteResult,
  TraceNode,
  WsMessage,
} from '@/types/apm'
import { extractProjectLabel, parseEntryKey, parseEntryInfo, buildTraceTree } from '@/types/apm'
import type { EntryPoint, DtoSchema } from '@/api/knowledgeGraph'

export const useApmStore = defineStore('apm', () => {
  // ============================================================
  // WebSocket composable
  // ============================================================
  const {
    connected: wsConnected,
    spans: wsSpans,
    events: wsEvents,
    processStatus: wsProcessStatus,
    processLogs: wsProcessLogs,
    processError: wsProcessError,
    connect: wsConnect,
    disconnect: wsDisconnect,
    reset: wsReset,
  } = useApmWebSocket()

  // ============================================================
  // Project & Session State
  // ============================================================
  const projects = ref<KgProject[]>([])
  const selectedProject = ref<KgProject | null>(null)
  const sessionId = ref('')
  const serviceName = ref('')
  const targetPort = ref(0)
  const status = ref<ApmSessionStatus>('IDLE')
  const errorMessage = ref('')
  const projectsLoading = ref(false)

  /** Active sessions keyed by projectPath — loaded from backend on init */
  const activeSessions = ref<Map<string, ApmSession>>(new Map())

  // ============================================================
  // Entry Points State
  // ============================================================
  const entryPoints = ref<KgEntryPoint[]>([])
  const selectedEntry = ref<KgEntryPoint | null>(null)
  const entryPointsLoading = ref(false)
  // Bytecode instrumentation strategy chosen at launch time.
  // Default 'FULL_PROJECT' so users can browse all spans without picking an entry first.
  const instrumentationMode = ref<import('@/types/apm').InstrumentationMode>('FULL_PROJECT')

  /** Schema of the @RequestBody DTO for selected entry (null if no body / not resolved) */
  const bodySchema = ref<DtoSchema | null>(null)
  const bodySchemaLoading = ref(false)

  // ============================================================
  // Request State
  // ============================================================
  const requestConfig = ref<RequestConfig>({
    method: 'GET',
    url: '',
    headers: {},
    body: '',
    queryParams: [],
  })

  // ============================================================
  // Response & Trace State
  // ============================================================
  const lastResponse = ref<ExecuteResult | null>(null)
  const report = ref<DebugReport | null>(null)
  const selectedSpan = ref<ApmSpan | null>(null)

  // ============================================================
  // Computed
  // ============================================================
  const traceTree = computed<TraceNode[]>(() => buildTraceTree(wsSpans.value))

  const isActive = computed(() =>
    status.value !== 'IDLE' && status.value !== 'COMPLETE' && status.value !== 'ERROR'
  )

  const canExecute = computed(() =>
    // Allow re-send after errors / completion. Only block while a launch or an
    // in-flight execute is happening, so users aren't stuck with a greyed button
    // after a transient failure.
    sessionId.value !== '' &&
    requestConfig.value.url.trim().length > 0 &&
    status.value !== 'IDLE' &&
    status.value !== 'LAUNCHING' &&
    status.value !== 'EXECUTING'
  )

  const showTracePanel = computed(() =>
    status.value === 'EXECUTING' ||
    status.value === 'STREAMING' ||
    status.value === 'COMPLETE'
  )

  // ============================================================
  // Internal: Watch WS events for auto-report fetch
  // (Moved here from the view -- the store owns this concern)
  // ============================================================
  watch(wsEvents, (events) => {
    const lastEvent = events[events.length - 1]
    if (lastEvent) {
      handleWsEvent(lastEvent)
    }
  })

  // ============================================================
  // Actions: Project Management
  // ============================================================
  async function loadProjects(): Promise<void> {
    projectsLoading.value = true
    try {
      // Interceptor unwraps ApiResponse.data; runtime type is string[]
      const paths = await knowledgeGraphApi.getProjects() as unknown as string[]
      projects.value = paths.map((p: string) => ({
        projectPath: p,
        label: extractProjectLabel(p),
      }))
      // After loading projects, also load active session info
      await loadActiveSessions()
    } catch {
      // Interceptor already shows ElMessage.error for HTTP failures.
      // Only set fallback message for non-HTTP errors.
    } finally {
      projectsLoading.value = false
    }
  }

  /**
   * Load all active sessions from backend and build a projectPath → session map.
   * This allows the UI to show which projects have active sessions.
   */
  async function loadActiveSessions(): Promise<void> {
    try {
      const sessions = await apmApi.getSessions(50) as unknown as ApmSession[]
      const map = new Map<string, ApmSession>()
      for (const s of sessions) {
        // Only track non-terminal sessions
        if (s.status !== 'COMPLETED' && s.status !== 'ERROR') {
          map.set(s.projectPath, s)
        }
      }
      activeSessions.value = map
    } catch {
      // Silent — non-critical; interceptor already shows errors
    }
  }

  /**
   * Force-stop a stale session (e.g., left behind from a previous page session).
   * Called when user wants to clear a lingering session for a project.
   */
  async function forceStopSession(projectPath: string): Promise<boolean> {
    const staleSession = activeSessions.value.get(projectPath)
    if (!staleSession) return true
    try {
      await apmApi.stop(staleSession.id)
      activeSessions.value = new Map(
        [...activeSessions.value].filter(([key]) => key !== projectPath)
      )
      ElMessage.success('已停止旧会话')
      return true
    } catch {
      ElMessage.error('停止旧会话失败，请稍后重试')
      return false
    }
  }

  function selectProject(project: KgProject | null): void {
    selectedProject.value = project
    // Reset downstream state
    entryPoints.value = []
    selectedEntry.value = null
    resetRequest()
    if (project) {
      loadEntryPoints(project.projectPath)
    }
  }

  // ============================================================
  // Actions: Entry Points
  // ============================================================
  async function loadEntryPoints(projectPath: string): Promise<void> {
    entryPointsLoading.value = true
    try {
      // Backend stores controller endpoints as 'HTTP' entryType (not 'CONTROLLER')
      const result = await knowledgeGraphApi.getEntryPoints([projectPath], 'HTTP', 1, 10000)
      const entries = (result?.items ?? []) as EntryPoint[]
      entryPoints.value = entries.map((e: EntryPoint) => {
        const parsed = parseEntryKey(e.entryKey)
        const info = parseEntryInfo((e as unknown as Record<string, unknown>).entryInfo as string | null)
        return {
          nodeId: e.nodeId,
          entryType: e.entryType,
          entryKey: e.entryKey,
          entryInfo: (e as unknown as Record<string, unknown>).entryInfo as string | null,
          methodNodeId: (e as unknown as Record<string, unknown>).methodNodeId as string | undefined,
          projectPath: e.projectPath,
          httpMethod: parsed?.httpMethod,
          httpPath: parsed?.httpPath,
          parsedInfo: info ?? undefined,
        }
      })
    } catch {
      // Interceptor already shows ElMessage.error for HTTP failures.
    } finally {
      entryPointsLoading.value = false
    }
  }

  function selectEntry(entry: KgEntryPoint | null): void {
    selectedEntry.value = entry
    bodySchema.value = null
    if (entry) {
      const method = entry.httpMethod || 'GET'
      let url = entry.httpPath || entry.entryKey
      const headers: Record<string, string> = {}
      const queryParams: Array<{ key: string; value: string; enabled: boolean }> = []
      let body = ''

      // Parse entryInfo to auto-populate parameters
      const info = entry.parsedInfo
      let bodyParamType: string | null = null
      if (info && info.parameters.length > 0) {
        for (const param of info.parameters) {
          const annSet = new Set(param.annotations)
          const paramName = param.aliasName || param.name

          if (annSet.has('RequestBody')) {
            bodyParamType = param.type
            body = generateBodyTemplate(param.type)
            headers['Content-Type'] = 'application/json'
          } else if (annSet.has('PathVariable')) {
            // Replace {name} placeholder in URL with empty value hint
            const placeholder = `{${paramName}}`
            if (url.includes(placeholder)) {
              // keep placeholder — user fills it
            } else {
              // URL may not have template syntax, try adding
              url = url.replace(new RegExp(`\\{${paramName}\\}`), `{${paramName}}`)
            }
          } else if (annSet.has('RequestParam')) {
            // Always enable optional params by default — user can uncheck if not needed.
            // Using `required !== false` here previously caused optional params to be
            // silently dropped from the URL, leading to backend 400 errors.
            queryParams.push({
              key: paramName,
              value: param.defaultValue || '',
              enabled: true,
            })
          } else if (annSet.has('RequestHeader')) {
            headers[paramName] = ''
          } else if (!annSet.has('PathVariable') && annSet.size === 0) {
            // Unannotated params in Spring default to @RequestParam
            queryParams.push({ key: paramName, value: '', enabled: true })
          }
        }
      }

      requestConfig.value = {
        method,
        url,
        headers,
        body,
        queryParams,
      }

      // Async-load DTO schema for @RequestBody param (if any)
      if (bodyParamType && entry.projectPath) {
        loadBodySchema(bodyParamType, entry.projectPath)
      }
    }
  }

  /**
   * Strip well-known generic wrappers (single-arg) so we can resolve the actual
   * payload DTO. Multi-pass to handle nested wrappers like
   * {@code ResponseEntity<Page<UserDto>>} → {@code UserDto}.
   *
   * NOTE: keep this in sync with backend {@code DtoSchemaResolver.unwrapCollection}
   * — the backend already recurses into collection element types when expanding
   * nested fields, so here we only need to peel the *outer* request body.
   */
  function unwrapGenericWrappers(typeName: string): { inner: string; wasCollection: boolean } {
    const SINGLE_ARG_WRAPPERS = [
      'List', 'Set', 'Collection', 'Iterable', 'ArrayList', 'LinkedList', 'HashSet',
      'Optional', 'ResponseEntity', 'Mono', 'Flux', 'CompletableFuture', 'Future',
      'Page', 'Slice', 'PageImpl', 'Result',
    ]
    const COLLECTION_WRAPPERS = new Set(['List', 'Set', 'Collection', 'Iterable', 'ArrayList', 'LinkedList', 'HashSet'])
    let current = typeName.trim()
    let wasCollection = false
    let safetyGuard = 6 // avoid infinite loops on malformed input
    while (safetyGuard-- > 0) {
      const m = current.match(/^([A-Za-z_][A-Za-z0-9_]*)<(.+)>$/)
      if (!m) break
      const [, wrapper, arg] = m
      if (!SINGLE_ARG_WRAPPERS.includes(wrapper)) break
      // single-arg only — bail on Map<K,V> etc. (commas at top level)
      if (containsTopLevelComma(arg)) break
      if (COLLECTION_WRAPPERS.has(wrapper)) wasCollection = true
      current = arg.trim()
    }
    // also strip trailing array notation
    if (current.endsWith('[]')) {
      wasCollection = true
      current = current.slice(0, -2).trim()
    }
    return { inner: current, wasCollection }
  }

  function containsTopLevelComma(s: string): boolean {
    let depth = 0
    for (const c of s) {
      if (c === '<') depth++
      else if (c === '>') depth--
      else if (c === ',' && depth === 0) return true
    }
    return false
  }

  /**
   * Fetch DTO field schema for the current @RequestBody parameter, then regenerate
   * a richer JSON body template from the field list.
   */
  async function loadBodySchema(typeName: string, projectPath: string): Promise<void> {
    const { inner, wasCollection } = unwrapGenericWrappers(typeName)
    if (!inner || isPrimitiveLike(inner)) return
    bodySchemaLoading.value = true
    try {
      const schema = await knowledgeGraphApi.getTypeSchema(inner, projectPath) as unknown as DtoSchema | null
      if (schema && schema.fields.length > 0) {
        bodySchema.value = schema
        // Regenerate body skeleton from real fields → valid JSON
        const skeleton = buildJsonSkeletonFromSchema(schema)
        const finalBody = wasCollection ? `[\n${indent(skeleton, 2)}\n]` : skeleton
        requestConfig.value = { ...requestConfig.value, body: finalBody }
      }
    } catch {
      // silent — interceptor surfaces HTTP errors; missing schema is acceptable
    } finally {
      bodySchemaLoading.value = false
    }
  }

  /**
   * Generate a placeholder JSON body when DTO schema isn't yet loaded.
   * Must produce VALID JSON (no comments) so Jackson can parse it.
   */
  function generateBodyTemplate(typeName: string): string {
    const inner = typeName.replace(/^(List|Set|Collection)<(.+)>$/, '$2')
    const isCollection = inner !== typeName
    const obj = '{}'
    return isCollection ? `[\n  ${obj}\n]` : obj
  }

  function isPrimitiveLike(t: string): boolean {
    const lower = t.toLowerCase()
    return [
      'string', 'integer', 'int', 'long', 'short', 'byte',
      'double', 'float', 'bigdecimal', 'biginteger',
      'boolean', 'char', 'character',
      'date', 'localdate', 'localdatetime', 'instant', 'zoneddatetime', 'offsetdatetime',
      'object', 'void',
    ].includes(lower)
  }

  function defaultForType(type: string): unknown {
    const t = type.toLowerCase()
    if (t === 'string' || t.endsWith('.string')) return ''
    if (t === 'boolean') return false
    if (['integer', 'int', 'long', 'short', 'byte', 'double', 'float', 'bigdecimal', 'biginteger'].includes(t)) return 0
    if (t.startsWith('list<') || t.startsWith('set<') || t.startsWith('collection<')) return []
    if (t.startsWith('map<')) return {}
    return null
  }

  function buildJsonSkeletonFromSchema(schema: DtoSchema): string {
    return JSON.stringify(buildJsonObjectFromSchema(schema), null, 2)
  }

  /** Recursive object builder — used both for top-level body and nested DTO fields. */
  function buildJsonObjectFromSchema(schema: DtoSchema): Record<string, unknown> {
    const obj: Record<string, unknown> = {}
    for (const f of schema.fields) {
      const key = f.jsonName || f.name
      if (f.isCollection) {
        // Collection of either nested DTO or primitive
        if (f.itemSchema) {
          obj[key] = [buildJsonObjectFromSchema(f.itemSchema)]
        } else {
          obj[key] = []
        }
      } else if (f.nested) {
        obj[key] = buildJsonObjectFromSchema(f.nested)
      } else {
        obj[key] = defaultForType(f.type)
      }
    }
    return obj
  }

  function indent(text: string, n: number): string {
    const pad = ' '.repeat(n)
    return text.split('\n').map(l => pad + l).join('\n')
  }

  // ============================================================
  // Actions: Request Editing (immutable patterns)
  // ============================================================
  function addQueryParam(): void {
    requestConfig.value = {
      ...requestConfig.value,
      queryParams: [
        ...requestConfig.value.queryParams,
        { key: '', value: '', enabled: true },
      ],
    }
  }

  function removeQueryParam(index: number): void {
    requestConfig.value = {
      ...requestConfig.value,
      queryParams: requestConfig.value.queryParams.filter((_, i) => i !== index),
    }
  }

  function updateQueryParam(index: number, field: 'key' | 'value' | 'enabled', val: string | boolean): void {
    requestConfig.value = {
      ...requestConfig.value,
      queryParams: requestConfig.value.queryParams.map((p, i) =>
        i === index ? { ...p, [field]: val } : p
      ),
    }
  }

  function setHeaders(headers: Record<string, string>): void {
    requestConfig.value = { ...requestConfig.value, headers }
  }

  function setBody(body: string): void {
    requestConfig.value = { ...requestConfig.value, body }
  }

  function setMethod(method: string): void {
    requestConfig.value = { ...requestConfig.value, method }
  }

  function setUrl(url: string): void {
    requestConfig.value = { ...requestConfig.value, url }
  }

  // ============================================================
  // Actions: Session Lifecycle
  // ============================================================
  async function launchSession(): Promise<void> {
    if (!selectedProject.value) {
      ElMessage.warning('请先选择项目')
      return
    }

    // Clear any residual state from a previous (errored/stopped) session
    // so the console panel, status badge and error banner start fresh.
    wsDisconnect()
    wsReset()
    sessionId.value = ''
    serviceName.value = ''
    targetPort.value = 0
    lastResponse.value = null
    report.value = null
    selectedSpan.value = null

    status.value = 'LAUNCHING'
    errorMessage.value = ''

    try {
      const result = await apmApi.launch({
        projectPath: selectedProject.value.projectPath,
        // When the user already chose an entry method, pass its nodeId so the
        // backend can generate OTEL_INSTRUMENTATION_METHODS_INCLUDE from the
        // KG callee tree — this captures method-level spans matching the chain.
        entryNodeId: selectedEntry.value?.nodeId,
        instrumentationMode: instrumentationMode.value,
      })
      sessionId.value = result.sessionId
      serviceName.value = result.serviceName
      targetPort.value = result.targetPort
      status.value = 'READY'
      wsConnect(result.sessionId)
    } catch (err: unknown) {
      status.value = 'ERROR'
      errorMessage.value = err instanceof Error ? err.message : '启动失败'
      // Interceptor already shows ElMessage.error for HTTP failures
    }
  }

  async function executeRequest(): Promise<void> {
    if (!sessionId.value || !canExecute.value) return

    status.value = 'EXECUTING'
    errorMessage.value = ''
    lastResponse.value = null
    report.value = null
    selectedSpan.value = null

    try {
      const result = await apmApi.execute({
        sessionId: sessionId.value,
        method: requestConfig.value.method,
        path: buildUrlWithParams(requestConfig.value.url, requestConfig.value.queryParams),
        body: ['POST', 'PUT', 'PATCH'].includes(requestConfig.value.method)
          ? requestConfig.value.body
          : undefined,
        headers: Object.keys(requestConfig.value.headers).length > 0
          ? requestConfig.value.headers
          : undefined,
      })
      lastResponse.value = result
      status.value = 'STREAMING'
    } catch (err: unknown) {
      status.value = 'ERROR'
      errorMessage.value = err instanceof Error ? err.message : '执行失败'
      // Interceptor already shows ElMessage.error for HTTP failures
    }
  }

  async function stopSession(): Promise<void> {
    if (!sessionId.value) return

    try {
      await apmApi.stop(sessionId.value)
      wsDisconnect()
      status.value = 'IDLE'
      ElMessage.success('已停止')
    } catch {
      // Interceptor already shows ElMessage.error
    }
  }

  async function fetchReport(): Promise<void> {
    if (!sessionId.value) return
    try {
      const rpt = await apmApi.getReport(sessionId.value)
      report.value = rpt
      status.value = 'COMPLETE'
    } catch {
      // Interceptor already shows ElMessage.error
    }
  }

  function handleWsEvent(event: WsMessage): void {
    if (event.type === 'EXECUTION_COMPLETE' && status.value === 'STREAMING') {
      fetchReport()
    }
  }

  // ============================================================
  // Actions: UI State
  // ============================================================
  function selectSpan(span: ApmSpan | null): void {
    selectedSpan.value = span
  }

  function resetRequest(): void {
    requestConfig.value = {
      method: 'GET',
      url: '',
      headers: {},
      body: '',
      queryParams: [],
    }
    lastResponse.value = null
  }

  function resetAll(): void {
    wsDisconnect()
    wsReset()
    sessionId.value = ''
    serviceName.value = ''
    targetPort.value = 0
    errorMessage.value = ''
    lastResponse.value = null
    report.value = null
    selectedSpan.value = null
    selectedEntry.value = null
    status.value = 'IDLE'
    resetRequest()
  }

  /** Explicitly clean up WebSocket -- call from component onUnmounted */
  function cleanup(): void {
    wsDisconnect()
  }

  /**
   * Clear all process console panel state (logs + status + error banner).
   * Used by the "清空" button in ProcessLogViewer so the user can wipe
   * lingering output without losing the current session.
   */
  function clearProcessConsole(): void {
    wsReset()
  }

  return {
    // State
    projects,
    selectedProject,
    sessionId,
    serviceName,
    targetPort,
    status,
    errorMessage,
    projectsLoading,
    activeSessions,
    entryPoints,
    selectedEntry,
    entryPointsLoading,
    instrumentationMode,
    bodySchema,
    bodySchemaLoading,
    requestConfig,
    lastResponse,
    report,
    selectedSpan,

    // WebSocket state
    wsConnected,
    wsSpans,
    wsEvents,
    wsProcessStatus,
    wsProcessLogs,
    wsProcessError,

    // Computed
    traceTree,
    isActive,
    canExecute,
    showTracePanel,

    // Actions: project & entry
    loadProjects,
    loadActiveSessions,
    forceStopSession,
    selectProject,
    loadEntryPoints,
    selectEntry,

    // Actions: request editing (immutable)
    addQueryParam,
    removeQueryParam,
    updateQueryParam,
    setHeaders,
    setBody,
    setMethod,
    setUrl,

    // Actions: session lifecycle
    launchSession,
    executeRequest,
    stopSession,
    fetchReport,
    handleWsEvent,

    // Actions: UI state
    selectSpan,
    resetRequest,
    resetAll,
    cleanup,
    clearProcessConsole,
  }
})

// ============================================================
// Utility: build URL with query params
// ============================================================
function buildUrlWithParams(
  path: string,
  params: Array<{ key: string; value: string; enabled: boolean }>
): string {
  const enabled = params.filter(p => p.enabled && p.key.trim())
  if (enabled.length === 0) return path
  const qs = enabled
    .map(p => `${encodeURIComponent(p.key)}=${encodeURIComponent(p.value)}`)
    .join('&')
  return path.includes('?') ? `${path}&${qs}` : `${path}?${qs}`
}
