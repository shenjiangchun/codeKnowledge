import request from '@/utils/request'

export interface KnowledgeGraphStatus {
  projectPath: string
  status: 'not_generated' | 'generated' | 'pending' | 'running' | 'completed' | 'failed'
  methodNodeCount: number
  callRelationCount: number
  entryPointCount: number
  interfaceImplCount: number
  callChainCount: number
  entryCount: number
  // 任务信息
  taskId?: number
  taskStatus?: string
  startTime?: string
  endTime?: string
  costTimeMs?: number
  errorMessage?: string
}

export interface KnowledgeGraphTask {
  id: number
  projectName: string
  projectPath: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  startTime?: string
  endTime?: string
  errorMessage?: string
  recordsProcessed?: number
  methodNodeCount?: number
  callRelationCount?: number
  entryPointCount?: number
  callChainCount?: number
  interfaceImplCount?: number
  costTimeMs?: number
  createdAt?: string
  updatedAt?: string
}

export interface CallerInfo {
  callerId: string
  callerClassName: string
  callerMethodName: string
  callType: string
  callLine: number
}

export interface RootEntryInfo {
  entryId: string
  entryType: string
  entryKey: string
}

/** /root-entries 合并响应：根入口 + 直接调用方 */
export interface RootEntriesResponse {
  rootEntries: RootEntryInfo[]
  directCallers: CallerInfo[]
}

export interface EntryPoint {
  nodeId: string
  methodNodeId: string
  entryType: string
  entryKey: string
  entryInfo: string
  projectPath: string
  /** 简要描述（LLM生成） */
  briefDescription?: string
  /** 详细描述（LLM生成） */
  detailedDescription?: string
  /** 服务名（文根聚合） */
  serviceName?: string
}

/** 入口摘要（用于聚合展示） */
export interface EntrySummary {
  entryId: string
  entryType: string
  entryKey: string
  briefDescription?: string
}

/** 服务入口分组 */
export interface ServiceEntryGroup {
  serviceName: string
  entries: EntrySummary[]
  totalCount: number
}

/**
 * DTO 字段 schema —— APM 调试 RequestBody 表单的渲染数据。
 */
export interface DtoField {
  name: string
  type: string
  jsonName?: string | null
  required: boolean
  constraints: string[]
  /** Nested DTO when field type is itself a project class (single object). */
  nested?: DtoSchema | null
  /** Element DTO when field is a collection of project DTOs. */
  itemSchema?: DtoSchema | null
  /** Raw element type string when field is a collection (e.g. "UserDto" for List<UserDto>). */
  itemType?: string | null
  /** True when field is List/Set/Collection/array (any element type). */
  isCollection?: boolean
}

export interface DtoSchema {
  fqn: string
  simpleName: string
  kind: 'class' | 'record' | 'enum'
  fields: DtoField[]
}

export interface MethodNode {
  nodeId: string
  className: string
  methodName: string
  signature: string
  filePath: string
  startLine: number
  endLine: number
  complexity: number
  thrownExceptions: string[]
  caughtExceptions: string[]
  methodBody: string
  projectPath: string
  description?: string
  serviceName?: string
  language?: string
  framework?: string
  packageName?: string
  inDegree?: number
  outDegree?: number
  communityId?: number
  riskScore?: number
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

export interface CallChainNode {
  nodeId: string
  callerId: string | null
  depth: number
  callPath: string[]
  className: string
  methodName: string
  signature: string
  filePath: string
  startLine: number
  endLine: number
  complexity: number
  methodBody: string
  thrownExceptions: string[]
  // Runtime fields used by EntryDetail.vue and related KG components
  description?: string
  isNoMatch?: boolean
  bridgeType?: string
  bridgeInfo?: unknown
}

export interface CallChainView {
  entryId: string
  entryType: string
  entryKey: string
  maxDepth: number
  totalNodes: number
  chain: Record<number, CallChainNode[]>
}

export interface CallCycleInfo {
  cycleId: string
  cyclePath: string[]
  startNodeId: string
  cycleLength: number
}

// ============================================================
// MyBatis 相关接口定义
// ============================================================

export interface MyBatisMapperNode {
  id: number
  mapperInterface: string
  xmlFilePath: string
  namespace: string
  projectPath: string
}

export interface MyBatisSqlNode {
  id: number
  sqlId: string
  statementType: string
  sqlStatement: string
  parameterType: string | null
  resultType: string | null
  resultMap: string | null
  mapperInterface: string
  methodName: string
  xmlFilePath: string
  projectPath: string
}

export interface MyBatisScanResult {
  success: boolean
  errorMessage: string | null
  mapperCount: number
  sqlCount: number
  errors: string[]
}

// ============================================================
// 图节点/边定义
// ============================================================

export interface GraphNode {
  id: string
  name: string
  className: string
  depth: number
  inCycle: boolean
  callType: string
  signature?: string
  filePath?: string
  startLine?: number
  description?: string
  // Runtime fields used by EntryDetail.vue / KG components
  isNoMatch?: boolean
  bridgeType?: string
  bridgeInfo?: unknown
  methodSignature?: string
  methodBody?: string
}

// ============================================================
// 桥接关系接口定义
// ============================================================

export type BridgeType = 'MAPPER' | 'JPA' | 'MQ' | 'FEIGN' | 'HTTP' | 'ASPECT' | 'DIRECT'

export interface BridgeRelation {
  callerId: string
  calleeId: string
  bridgeType: BridgeType
  sqlId?: string
  targetService?: string
  targetEndpoint?: string
  topic?: string
  messageTypes?: string[]
  httpMethod?: string
  httpUri?: string
  feignClient?: string
}

export interface BridgeStats {
  mapperCount: number
  jpaCount: number
  mqCount: number
  feignCount: number
  httpCount: number
  aspectCount: number
  totalBridges: number
}

export interface MapperSqlDetail {
  sqlId: string
  statementType: string
  sqlStatement: string
  parameterType: string | null
  resultType: string | null
  mapperInterface: string
  methodName: string
  xmlFilePath: string
}

export interface FeignCallChain {
  serviceName: string
  url: string
  httpMethod: string
  targetService: string
  targetEndpoint: string
  feignClientClass: string
  methodName: string
}

export interface MQCallChain {
  topic: string
  producerMethod: string
  consumerMethods: string[]
  messageTypes: string[]
}

export interface GraphEdge {
  source: string
  target: string
  callType: string
  callLine: number
  isCycleEdge: boolean
}

export interface GitStatus {
  clean: boolean
  commitHash: string
  branch: string
  hasUncommittedChanges: boolean
}

export interface CallChainGraphData {
  entryId: string
  entryType: string
  entryKey: string
  maxDepth: number
  totalNodes: number
  nodes: GraphNode[]
  edges: GraphEdge[]
  cycles: CallCycleInfo[]
  cycleCount: number
  nodesInCycle: string[]
}

export const knowledgeGraphApi = {
  // ============================================================
  // 项目与类查询接口 — V2（projectPaths 必填）
  // ============================================================

  getProjects() {
    return request.get<string[]>('/v2/knowledge-graph/projects')
  },

  getClasses(projectPaths: string[], page = 1, pageSize = 50, keyword?: string) {
    return request.get<PageResult<string>>('/v2/knowledge-graph/classes', {
      params: { projectPaths, page, pageSize, keyword }
    })
  },

  // ============================================================
  // 任务管理接口（V1，写操作保持 projectPath）
  // ============================================================

  startGenerateTask(projectPath: string, excludePaths?: string[], generateVector = true, generateArchitecture = true, buildMode = 'reuse') {
    const params: Record<string, string> = { projectPath }
    if (excludePaths && excludePaths.length > 0) {
      params.excludePaths = excludePaths.join(',')
    }
    params.generateVector = String(generateVector)
    params.generateArchitecture = String(generateArchitecture)
    params.buildMode = buildMode
    return request.post<KnowledgeGraphTask>('/knowledge-graph/tasks/generate', null, { params })
  },

  /** 批量入队：多项目排队生成，完整完成一个再做下一个 */
  startGenerateTaskBatch(projectPaths: string[], excludePaths?: string[], generateVector = true, generateArchitecture = true, buildMode = 'reuse') {
    return request.post<KnowledgeGraphTask[]>('/knowledge-graph/tasks/generate-batch', {
      projectPaths,
      excludePaths: excludePaths || undefined,
      generateVector,
      generateArchitecture,
      buildMode
    })
  },

  /** 获取生成队列状态（当前执行中 + 排队列表） */
  getQueueStatus() {
    return request.get<{ currentProject: string | null; queueSize: number; queue: Array<{ projectPath: string; taskId: string }> }>('/knowledge-graph/tasks/queue')
  },

  getTaskStatus(projectPaths?: string[]) {
    const params = projectPaths ? { projectPaths: projectPaths.join(',') } : {}
    return request.get<KnowledgeGraphTask[]>('/knowledge-graph/tasks/status', { params })
  },

  getLatestTask(projectPath: string) {
    return request.get<KnowledgeGraphTask>('/knowledge-graph/tasks/latest', {
      params: { projectPath }
    })
  },

  // ============================================================
  // 同步生成接口已删除（后端 POST /knowledge-graph/generate 已移除，
  // 前端无调用方）。生成统一走 startGenerateTask / startGenerateTaskBatch 异步队列。
  // ============================================================

  // ============================================================
  // 状态查询 — V2
  // ============================================================

  getStatus(projectPaths: string[]) {
    return request.get<KnowledgeGraphStatus>('/v2/knowledge-graph/status', {
      params: { projectPaths }
    })
  },

  getBatchStatus(projectPaths: string[]) {
    return request.get<KnowledgeGraphStatus[]>('/v2/knowledge-graph/status/batch', {
      params: { projectPaths }
    })
  },

  // ============================================================
  // 调用链 & 入口查询 — V2
  // ============================================================

  getRootEntries(className: string, methodName: string, projectPaths: string[]) {
    return request.get<RootEntriesResponse>('/v2/knowledge-graph/root-entries', {
      params: { className, methodName, projectPaths }
    })
  },

  getCalleesTree(className: string, methodName: string, projectPaths: string[], maxDepth?: number) {
    return request.get<CallChainGraphData>('/v2/knowledge-graph/callees-tree', {
      params: { className, methodName, projectPaths, maxDepth }
    })
  },

  getEntryTypes(projectPaths: string[]) {
    return request.get<string[]>('/v2/knowledge-graph/entry-types', {
      params: { projectPaths }
    })
  },

  getEntryPoints(projectPaths: string[], entryType?: string, page = 1, pageSize = 20) {
    return request.get<PageResult<EntryPoint>>('/v2/knowledge-graph/entry-points', {
      params: { projectPaths, entryType, page, pageSize }
    })
  },

  /** 按 serviceName 聚合查询入口点（支持分页） */
  getEntryPointsGrouped(projectPaths: string[], page = 1, pageSize = 10) {
    return request.get<PageResult<ServiceEntryGroup>>('/v2/knowledge-graph/entry-points/grouped', {
      params: { projectPaths, page, pageSize }
    })
  },

  /**
   * 解析 DTO 类的字段 schema（用于 APM 调试 RequestBody 表单）
   * 保持 V1 —— 不在图谱总览页面使用
   */
  getTypeSchema(className: string, projectPath: string) {
    return request.get<DtoSchema | null>('/knowledge-graph/type/schema', {
      params: { className, projectPath }
    })
  },

  // ============================================================
  // 调用链查询 — V2
  // ============================================================

  getCallChainByKey(entryKey: string, projectPaths: string[]) {
    return request.get<CallChainView>('/v2/knowledge-graph/call-chain/by-key', {
      params: { entryKey, projectPaths }
    })
  },

  getCallChainsByType(entryType: string, projectPaths: string[]) {
    return request.get<CallChainView[]>('/v2/knowledge-graph/call-chain/by-type', {
      params: { entryType, projectPaths }
    })
  },

  getCallChainsAffecting(className: string, methodName: string, projectPaths: string[]) {
    return request.get<CallChainView[]>('/v2/knowledge-graph/call-chain/affecting', {
      params: { className, methodName, projectPaths }
    })
  },

  getImplementations(interfaceName: string, projectPaths: string[]) {
    return request.get<string[]>('/v2/knowledge-graph/implementations', {
      params: { interfaceName, projectPaths }
    })
  },

  getInterfaces(className: string, projectPaths: string[]) {
    return request.get<string[]>('/v2/knowledge-graph/interfaces', {
      params: { className, projectPaths }
    })
  },

  getMethodDetail(nodeId: string, projectPaths: string[]) {
    return request.get<MethodNode>('/v2/knowledge-graph/method/detail', {
      params: { nodeId, projectPaths }
    })
  },

  getMethodsByClass(className: string, projectPaths: string[]) {
    return request.get<MethodNode[]>('/v2/knowledge-graph/method/by-class', {
      params: { className, projectPaths }
    })
  },

  searchMethods(keyword: string, projectPaths: string[], limit = 50) {
    return request.get<MethodNode[]>('/v2/knowledge-graph/method/search', {
      params: { keyword, projectPaths, limit }
    })
  },

  // ============================================================
  // 调用链图数据接口 — V2
  // ============================================================

  getDownstreamChain(nodeId: string, projectPaths: string[], maxDepth?: number) {
    const params: Record<string, string | number | string[]> = { nodeId, projectPaths }
    if (maxDepth !== undefined) {
      params.maxDepth = maxDepth
    }
    return request.get<CallChainView>('/v2/knowledge-graph/call-chain/downstream', {
      params
    })
  },

  getCallChainGraph(entryKey: string, projectPaths: string[], includeCycles?: boolean, maxDepth?: number) {
    const params: Record<string, string | boolean | string[] | number> = { entryKey, projectPaths }
    if (includeCycles !== undefined) {
      params.includeCycles = includeCycles
    }
    if (maxDepth !== undefined) {
      params.maxDepth = maxDepth
    }
    return request.get<CallChainGraphData>('/v2/knowledge-graph/call-chain/graph', {
      params
    })
  },

  detectCycles(projectPaths: string[], entryKey?: string, nodeId?: string) {
    const params: Record<string, string | string[]> = { projectPaths }
    if (entryKey !== undefined) {
      params.entryKey = entryKey
    }
    if (nodeId !== undefined) {
      params.nodeId = nodeId
    }
    return request.get<CallCycleInfo[]>('/v2/knowledge-graph/cycles/detect', {
      params
    })
  },

  // ============================================================
  // MyBatis 相关接口
  // ============================================================

  scanMyBatis(projectPath: string) {
    return request.post<MyBatisScanResult>('/knowledge-graph/mybatis/scan', {
      projectPath
    })
  },

  getMyBatisMappers(projectPaths: string[]) {
    return request.get<MyBatisMapperNode[]>('/v2/knowledge-graph/mybatis/mappers', {
      params: { projectPaths }
    })
  },

  getMyBatisSqlStatements(projectPaths: string[], mapperInterface?: string, statementType?: string) {
    const params: Record<string, string | string[]> = { projectPaths }
    if (mapperInterface !== undefined) {
      params.mapperInterface = mapperInterface
    }
    if (statementType !== undefined) {
      params.statementType = statementType
    }
    return request.get<MyBatisSqlNode[]>('/v2/knowledge-graph/mybatis/sql', {
      params
    })
  },

  // ============================================================
  // 桥接关系查询接口 — V2
  // ============================================================

  getMethodBridges(nodeId: string, projectPaths: string[]) {
    return request.get<BridgeRelation[]>(`/v2/knowledge-graph/call-chain/${nodeId}/bridges`, {
      params: { projectPaths }
    })
  },

  getMapperSql(mapperInterface: string, projectPaths: string[]) {
    return request.get<MapperSqlDetail[]>(`/v2/knowledge-graph/mapper/${mapperInterface}/sql`, {
      params: { projectPaths }
    })
  },

  getFeignCallChain(serviceName: string, projectPaths: string[]) {
    return request.get<FeignCallChain[]>(`/v2/knowledge-graph/feign/${serviceName}/call-chain`, {
      params: { projectPaths }
    })
  },

  getMQCallChain(topic: string, projectPaths: string[]) {
    return request.get<MQCallChain[]>(`/v2/knowledge-graph/mq/${topic}/call-chain`, {
      params: { projectPaths }
    })
  },

  getBridgeStats(projectPaths: string[]) {
    return request.get<BridgeStats>('/v2/knowledge-graph/bridge-stats', {
      params: { projectPaths }
    })
  },

  getBridgesByType(bridgeType: string, projectPaths: string[]) {
    return request.get<Array<{
      callerClassName: string
      callerMethodName: string
      calleeClassName: string
      calleeMethodName: string
      bridgeType: string
      callLine: number | null
      targetService: string | null
      targetEndpoint: string | null
      sqlId: string | null
    }>>('/v2/knowledge-graph/bridges/by-type', {
      params: { bridgeType, projectPaths }
    })
  },

  // ============================================================
  // Git 状态 — V2
  // ============================================================

  getGitStatus(projectPaths: string[]) {
    return request.get<GitStatus>('/v2/knowledge-graph/git-status', {
      params: { projectPaths }
    })
  },

  // ============================================================
  // 写操作 — V1（保持 projectPath）
  // ============================================================

  incrementalGenerate(projectPath: string) {
    return request.post<KnowledgeGraphTask>('/knowledge-graph/incremental', {
      projectPath
    })
  },

  crossServiceBuild(projectPaths: string[]) {
    return request.post<{ taskId: number }>('/knowledge-graph/cross-service/build', { projectPaths })
  },

  refresh(projectPath: string) {
    return request.post<{
      projectPath: string
      lastCommit: string
      currentCommit: string
      changedFiles: number
      deletedNodes: number
      rebuiltNodes: number
      rebuiltEdges: number
      rebuiltEntryPoints: number
      vectorsGenerated: number
      success: boolean
    }>('/knowledge-graph/refresh', {
      projectPath
    })
  },

  // ============================================================
  // KG 路径管理接口
  // ============================================================

  /** KG 路径诊断：检查 KG 数据路径与当前配置是否一致 */
  diagnosePaths() {
    return request.get<{
      currentProjectDir: string
      kgProjectPaths: string[]
      totalKgPaths: number
      inconsistentPaths: Array<{
        path: string
        normalized: string
        reason: string
        projectName: string
        expectedPath: string
      }>
      inconsistentCount: number
    }>('/knowledge-graph/admin/paths/diagnosis')
  },

  /** KG 路径迁移：将旧路径更新为当前配置 */
  migratePaths(oldBaseDir: string, dryRun = true) {
    return request.post<{
      oldBaseDir: string
      newBaseDir: string
      dryRun: boolean
      methodCount: number
      entryCount: number
      sqlCount: number
      totalAffected: number
      affectedPaths: string[]
      message: string
    }>('/knowledge-graph/admin/paths/migrate', null, {
      params: { oldBaseDir, dryRun }
    })
  },

  // ==================== Aggregation APIs (Phase 4) ====================
  getDashboard(projectPaths: string[], language?: string) {
    return request.get<DashboardData>('/v2/knowledge-graph/dashboard', { params: { projectPaths, language } })
  },
  getDsm(projectPaths: string[], language?: string, level?: string) {
    return request.get<DsmData>('/v2/knowledge-graph/dsm', { params: { projectPaths, language, level } })
  },
  getDsmDrillDown(projectPaths: string[], modules: string[]) {
    return request.get<DsmData>('/v2/knowledge-graph/dsm/drill-down', { params: { projectPaths, modules } })
  },
  getHotspots(projectPaths: string[], language?: string, limit?: number) {
    return request.get<{ hotspots: HotspotItem[]; total: number }>('/v2/knowledge-graph/hotspots', { params: { projectPaths, language, limit } })
  },
  getDomains(projectPaths: string[], language?: string) {
    return request.get<{ domains: DomainItem[]; interactions: DomainEdge[] }>('/v2/knowledge-graph/domains', { params: { projectPaths, language } })
  },
  getDomainClasses(domainId: string, projectPaths: string[]) {
    return request.get<{ domainId: string; classes: DomainClass[] }>(`/v2/knowledge-graph/domains/${encodeURIComponent(domainId)}/classes`, { params: { projectPaths } })
  },
  runArchitectureAnalysis(projectPaths: string[]) {
    return request.post<{ results: { projectPath: string; taskId: number; status: string }[] }>('/v2/knowledge-graph/architecture-analysis', null, { params: { projectPaths } })
  },
  getArchAnalysisStatus(projectPaths: string[]) {
    return request.get<ArchAnalysisTask[]>('/v2/knowledge-graph/architecture-analysis/status', { params: { projectPaths } })
  },
  getServiceTopology(projectPaths: string[], language?: string) {
    return request.get<ServiceTopology>('/v2/knowledge-graph/service-topology', { params: { projectPaths, language } })
  },
  getBlastRadius(nodeId: string, projectPaths: string[], maxDepth?: number) {
    return request.get<BlastRadiusData>(`/v2/knowledge-graph/blast-radius/${encodeURIComponent(nodeId)}`, { params: { projectPaths, maxDepth } })
  },
  generateTestSuggestions(nodeId: string, projectPaths: string[]) {
    return request.post<{ nodeId: string; testCases: TestSuggestion[] }>('/v2/knowledge-graph/test-suggestions', null, { params: { nodeId, projectPaths } })
  },
  generateRefactorSuggestions(moduleName: string, projectPaths: string[]) {
    return request.post<{ moduleName: string; suggestions: RefactorSuggestion[] }>('/v2/knowledge-graph/refactor-suggestions', null, { params: { moduleName, projectPaths } })
  },
  getModuleDependencyGraph(projectPaths: string[], sourceModule: string, targetModule: string) {
    return request.get<ModuleDependencyGraph>('/v2/knowledge-graph/module-dependency-graph', { params: { projectPaths, sourceModule, targetModule } })
  },
  getDomainDependencyGraph(projectPaths: string[], sourceDomain: string, targetDomain: string) {
    return request.get<ModuleDependencyGraph>('/v2/knowledge-graph/domain-dependency-graph', { params: { projectPaths, sourceDomain, targetDomain } })
  },
  getBuildModules(projectPaths: string[]) {
    return request.get<BuildModuleGraphData>('/v2/knowledge-graph/build-modules', { params: { projectPaths } })
  },
  getBuildModuleCycles(projectPaths: string[]) {
    return request.get<{ cycles: string[][]; cycleCount: number }>('/v2/knowledge-graph/build-module-cycles', { params: { projectPaths } })
  },
  getBuildModuleLayerViolations(projectPaths: string[]) {
    return request.get<{ violations: ModuleLayerViolation[] }>('/v2/knowledge-graph/build-module-layer-violations', { params: { projectPaths } })
  },
  getPackageCycles(projectPaths: string[]) {
    return request.get<{ cycles: ClassifiedCycle[]; cycleCount: number }>('/v2/knowledge-graph/package-cycles', { params: { projectPaths } })
  },
  getPackageDependencies(projectPaths: string[]) {
    return request.get<PackageDependencyGraph>('/v2/knowledge-graph/package-dependencies', { params: { projectPaths } })
  },
  getModuleCycles(projectPaths: string[]) {
    return request.get<{ cycles: ClassifiedCycle[]; cycleCount: number }>('/v2/knowledge-graph/module-cycles', { params: { projectPaths } })
  },
  getClassLayerViolations(projectPaths: string[]) {
    return request.get<{ violations: ClassLayerViolation[] }>('/v2/knowledge-graph/class-layer-violations', { params: { projectPaths } })
  },
  getClassDependencies(projectPaths: string[], packages?: string[]) {
    return request.get<ClassDependencyGraph>('/v2/knowledge-graph/class-dependencies', { params: { projectPaths, packages } })
  },
  getLayerDomainMatrix(projectPaths: string[]) {
    return request.get<{ classes: LayerDomainClass[] }>('/v2/knowledge-graph/layer-domain-matrix', { params: { projectPaths } })
  },
  getClassEgoNet(projectPaths: string[], packages: string[]) {
    return request.get<ClassEgoNet>('/v2/knowledge-graph/class-ego-net', { params: { projectPaths, packages } })
  },
}

// --- Aggregation Types ---
export interface DashboardData {
  domains: DashboardDomain[]
  interactions: { source: string; target: string; weight: number }[]
  kpis: { totalMethods: number; totalDomains: number; cyclicDependencies: number; layeredViolations: number; avgCoupling: number }
  risks: { severity: string; type: 'cyclic' | 'layered'; source: string; target: string; message: string }[]
  hotspots: HotspotItem[]
}
export interface ModuleDependencyGraph {
  nodes: GraphNode[]
  edges: GraphEdge[]
}
export interface BuildModuleGraphData {
  nodes: BuildModule[]
  edges: { source: string; target: string }[]
}
export interface BuildModule {
  moduleName: string; groupId: string; artifactId: string; version: string; projectPath: string
}
export interface ModuleLayerViolation {
  source: string; target: string; type: string; sourceLayer: string; targetLayer: string; message: string
}
export interface ClassifiedCycle {
  nodes: string[]; level: string; message: string
}
export interface ClassLayerViolation {
  source: string; target: string; sourceRole: string; targetRole: string; message: string
}
export interface ClassDependencyGraph {
  nodes: { className: string; classRole: string; classRoleSource: string; packageName: string }[]
  edges: { source: string; target: string }[]
}
export interface LayerDomainClass {
  className: string; classRole: string; domainName: string
}
export interface ClassEgoNetNode {
  className: string; classRole: string; classRoleSource: string; packageName: string; center: boolean
}
export interface ClassEgoNet {
  nodes: ClassEgoNetNode[]
  edges: { source: string; target: string }[]
}
export interface PackageDependencyGraph {
  nodes: { moduleName: string; layerRole: string; methodCount: number }[]
  edges: { source: string; target: string; weight: number }[]
}
export interface DashboardDomain {
  domainId: string; name: string; confidence: number; methodCount: number; classCount: number
}
export interface ModuleInfo {
  name: string; level: string; methodCount: number; classCount: number; entryPointCount: number
  avgComplexity: number; inDegree: number; outDegree: number; instability: number
  layerRole: string; language: string
}
export interface DsmData { modules: string[]; cells: { sourceIdx: number; targetIdx: number; weight: number }[]; level: string }
export interface HotspotItem {
  filePath: string; commitCount90d: number; complexity: number; riskScore: number; layerRole: string
}
export interface DomainItem { id: string; name: string; confidence: number; methodCount: number; classCount: number }
export interface DomainEdge { source: string; target: string; weight: number }
export interface DomainClass { id: string; className: string; methodCount: number; description?: string }
export interface ArchAnalysisTask {
  id: number
  taskType: string
  projectPath: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  progress: number
  totalCount: number
  successCount: number
  failCount: number
  errorMessage?: string
}
export interface ServiceTopology {
  services: { name: string; methodCount: number; language: string; framework: string }[]
  edges: { source: string; target: string; type: string; weight: number }[]
}
export interface BlastRadiusData {
  centerNode: { nodeId: string; className: string; methodName: string }
  downstream: { totalAffectedMethods: number; maxDepth: number }
  upstream: { totalCallers: number; maxDepth: number }
  affectedEntryPoints: number
  riskSummary: { overallRisk: 'LOW' | 'MEDIUM' | 'HIGH'; reasons: string[] }
}
export interface TestSuggestion {
  scenario: string; type: 'UNIT' | 'INTEGRATION' | 'EXCEPTION' | 'BOUNDARY'; priority: 'HIGH' | 'MEDIUM' | 'LOW'
}
export interface RefactorSuggestion {
  issue: string; direction: string; impact: string; priority: 'HIGH' | 'MEDIUM' | 'LOW'
}
