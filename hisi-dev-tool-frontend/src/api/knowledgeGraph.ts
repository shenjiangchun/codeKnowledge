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

export interface GenerateResult {
  methodNodeCount: number
  callRelationCount: number
  entryPointCount: number
  interfaceImplCount: number
  callChainCount: number
  costTimeMs: number
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
  entryType: string
  entryKey: string
  entryInfo: string
  projectPath: string
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
// 业务流程生成接口定义
// ============================================================

export interface BusinessFlowRequest {
  callChainData: string
  projectPath?: string
  entryPointKey?: string
  maxDepth?: number
  includeDescription?: boolean
}

export interface BusinessFlowResponse {
  requestId: string
  mermaidDiagram: string
  description: string
  steps: FlowStep[]
  keyNodes: KeyNode[]
  generatedAt: string
  success: boolean
  errorMessage?: string
}

export interface FlowStep {
  stepNumber: number
  description: string
  className: string
  methodName: string
  depth: number
}

export interface KeyNode {
  nodeId: string
  className: string
  methodName: string
  reason: string
}

// ============================================================
// 单元测试生成接口定义
// ============================================================

export interface UnitTestRequest {
  methodId: string
  projectPath: string
  className: string
  methodName: string
  includeBusinessFlow?: boolean
  testFramework?: 'junit5' | 'junit4'
}

export interface UnitTestResponse {
  requestId: string
  testClassName: string
  testCode: string
  mockDependencies: MockDependency[]
  testCases: TestCase[]
  estimatedCoverage: number
  generatedAt: string
  success: boolean
  errorMessage?: string
}

export interface MockDependency {
  className: string
  mockType: string
  methods: string[]
}

export interface TestCase {
  name: string
  type: string
  description: string
}

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
  // 项目与类查询接口（替代旧 callchain 接口）
  // ============================================================

  /**
   * 获取所有已生成知识图谱的项目路径列表
   * 替代旧的 callChainApi.getProjects()
   */
  getProjects() {
    return request.get<string[]>('/knowledge-graph/projects')
  },

  /**
   * 获取项目下的所有类名列表
   * 替代旧的 callChainApi.getClasses()
   */
  getClasses(projectPath: string, projectPaths?: string[], page = 1, pageSize = 50, keyword?: string) {
    return request.get<PageResult<string>>('/knowledge-graph/classes', {
      params: { projectPath, projectPaths, page, pageSize, keyword }
    })
  },

  // ============================================================
  // 任务管理接口（异步生成）
  // ============================================================

  /**
   * 启动知识图谱生成任务（异步）
   * @param projectPath 项目路径
   * @param excludePaths 屏蔽目录列表（可选）
   */
  startGenerateTask(projectPath: string, excludePaths?: string[]) {
    const params: Record<string, string> = { projectPath }
    if (excludePaths && excludePaths.length > 0) {
      params.excludePaths = excludePaths.join(',')
    }
    return request.post<KnowledgeGraphTask>('/knowledge-graph/tasks/generate', null, { params })
  },

  /**
   * 批量查询任务状态
   */
  getTaskStatus(projectPaths?: string[]) {
    const params = projectPaths ? { projectPaths: projectPaths.join(',') } : {}
    return request.get<KnowledgeGraphTask[]>('/knowledge-graph/tasks/status', { params })
  },

  /**
   * 获取单个项目的最新任务
   */
  getLatestTask(projectPath: string) {
    return request.get<KnowledgeGraphTask>('/knowledge-graph/tasks/latest', {
      params: { projectPath }
    })
  },

  // ============================================================
  // 同步生成接口（保留，供简单场景使用）
  // ============================================================

  /**
   * 生成知识图谱（同步，阻塞请求）
   */
  generate(projectPath: string) {
    return request.post<GenerateResult>('/knowledge-graph/generate', { projectPath })
  },

  /**
   * 获取知识图谱状态
   */
  getStatus(projectPath: string, projectPaths?: string[]) {
    return request.get<KnowledgeGraphStatus>('/knowledge-graph/status', {
      params: { projectPath, projectPaths }
    })
  },

  /**
   * 批量获取多项目知识图谱状态
   */
  getBatchStatus(projectPaths: string[]) {
    return request.get<KnowledgeGraphStatus[]>('/knowledge-graph/status/batch', {
      params: { projectPaths }
    })
  },

  /**
   * 查询方法的上游信息（根入口 + 直接调用方，合并接口）
   */
  getRootEntries(className: string, methodName: string, projectPath: string, projectPaths?: string[]) {
    return request.get<RootEntriesResponse>('/knowledge-graph/root-entries', {
      params: { className, methodName, projectPath, projectPaths }
    })
  },

  /**
   * 查询方法的完整下游调用树（递归图，nodes+edges+depth）
   */
  getCalleesTree(className: string, methodName: string, projectPath: string, maxDepth?: number, projectPaths?: string[]) {
    return request.get<CallChainGraphData>('/knowledge-graph/callees-tree', {
      params: { className, methodName, projectPath, projectPaths, maxDepth }
    })
  },

  getEntryTypes(projectPath: string, projectPaths?: string[]) {
    return request.get<string[]>('/knowledge-graph/entry-types', {
      params: { projectPath, projectPaths }
    })
  },

  /**
   * 查询入口点列表
   */
  getEntryPoints(projectPath: string, entryType?: string, projectPaths?: string[], page = 1, pageSize = 20) {
    return request.get<PageResult<EntryPoint>>('/knowledge-graph/entry-points', {
      params: { projectPath, entryType, projectPaths, page, pageSize }
    })
  },

  /**
   * 解析 DTO 类的字段 schema（用于 APM 调试 RequestBody 表单）
   * className 支持简单名或全限定名；解析失败返回 null。
   */
  getTypeSchema(className: string, projectPath: string) {
    return request.get<DtoSchema | null>('/knowledge-graph/type/schema', {
      params: { className, projectPath }
    })
  },

  // ============================================================
  // 调用链查询（替代调用链分析功能）
  // ============================================================

  /**
   * 根据入口标识查询完整调用链（如 URI）
   */
  getCallChainByKey(entryKey: string, projectPath: string, projectPaths?: string[]) {
    return request.get<CallChainView>('/knowledge-graph/call-chain/by-key', {
      params: { entryKey, projectPath, projectPaths }
    })
  },

  /**
   * 根据入口类型查询所有调用链
   */
  getCallChainsByType(entryType: string, projectPath: string, projectPaths?: string[]) {
    return request.get<CallChainView[]>('/knowledge-graph/call-chain/by-type', {
      params: { entryType, projectPath, projectPaths }
    })
  },

  /**
   * 反向查询：哪些入口会调用指定方法
   */
  getCallChainsAffecting(className: string, methodName: string, projectPath: string, projectPaths?: string[]) {
    return request.get<CallChainView[]>('/knowledge-graph/call-chain/affecting', {
      params: { className, methodName, projectPath, projectPaths }
    })
  },

  /**
   * 查询接口的所有实现类
   */
  getImplementations(interfaceName: string, projectPath: string, projectPaths?: string[]) {
    return request.get<string[]>('/knowledge-graph/implementations', {
      params: { interfaceName, projectPath, projectPaths }
    })
  },

  /**
   * 查询类实现的所有接口
   */
  getInterfaces(className: string, projectPath: string, projectPaths?: string[]) {
    return request.get<string[]>('/knowledge-graph/interfaces', {
      params: { className, projectPath, projectPaths }
    })
  },

  /**
   * 查询方法详情（包含方法体）
   */
  getMethodDetail(nodeId: string, projectPath: string, projectPaths?: string[]) {
    return request.get<MethodNode>('/knowledge-graph/method/detail', {
      params: { nodeId, projectPath, projectPaths }
    })
  },

  /**
   * 按类名查询所有方法
   */
  getMethodsByClass(className: string, projectPath: string, projectPaths?: string[]) {
    return request.get<MethodNode[]>('/knowledge-graph/method/by-class', {
      params: { className, projectPath, projectPaths }
    })
  },

  /**
   * 模糊搜索方法（按类名或方法名）
   */
  searchMethods(keyword: string, projectPath: string, limit = 50, projectPaths?: string[]) {
    return request.get<MethodNode[]>('/knowledge-graph/method/search', {
      params: { keyword, projectPath, limit, projectPaths }
    })
  },

  // ============================================================
  // 调用链图数据接口
  // ============================================================

  /**
   * 向下调用链查询
   */
  getDownstreamChain(nodeId: string, projectPath: string, maxDepth?: number, projectPaths?: string[]) {
    const params: Record<string, string | number | string[]> = { nodeId, projectPath }
    if (maxDepth !== undefined) {
      params.maxDepth = maxDepth
    }
    if (projectPaths) {
      params.projectPaths = projectPaths
    }
    return request.get<CallChainView>('/knowledge-graph/call-chain/downstream', {
      params
    })
  },

  /**
   * 获取DAG图数据
   */
  getCallChainGraph(entryKey: string, projectPath: string, includeCycles?: boolean, projectPaths?: string[], maxDepth?: number) {
    const params: Record<string, string | boolean | string[] | number> = { entryKey, projectPath }
    if (includeCycles !== undefined) {
      params.includeCycles = includeCycles
    }
    if (projectPaths) {
      params.projectPaths = projectPaths
    }
    if (maxDepth !== undefined) {
      params.maxDepth = maxDepth
    }
    return request.get<CallChainGraphData>('/knowledge-graph/call-chain/graph', {
      params
    })
  },

  /**
   * 环检测
   */
  detectCycles(projectPath: string, entryKey?: string, nodeId?: string, projectPaths?: string[]) {
    const params: Record<string, string | string[]> = { projectPath }
    if (entryKey !== undefined) {
      params.entryKey = entryKey
    }
    if (nodeId !== undefined) {
      params.nodeId = nodeId
    }
    if (projectPaths) {
      params.projectPaths = projectPaths
    }
    return request.get<CallCycleInfo[]>('/knowledge-graph/call-chain/cycles', {
      params
    })
  },

  // ============================================================
  // MyBatis 相关接口
  // ============================================================

  /**
   * 扫描项目中的 MyBatis Mapper
   */
  scanMyBatis(projectPath: string) {
    return request.post<MyBatisScanResult>('/knowledge-graph/mybatis/scan', {
      projectPath
    })
  },

  /**
   * 获取 MyBatis Mapper 列表
   */
  getMyBatisMappers(projectPath: string, projectPaths?: string[]) {
    return request.get<MyBatisMapperNode[]>('/knowledge-graph/mybatis/mappers', {
      params: { projectPath, projectPaths }
    })
  },

  /**
   * 获取 MyBatis SQL 语句列表
   */
  getMyBatisSqlStatements(projectPath: string, mapperInterface?: string, statementType?: string, projectPaths?: string[]) {
    const params: Record<string, string | string[]> = { projectPath }
    if (mapperInterface !== undefined) {
      params.mapperInterface = mapperInterface
    }
    if (statementType !== undefined) {
      params.statementType = statementType
    }
    if (projectPaths) {
      params.projectPaths = projectPaths
    }
    return request.get<MyBatisSqlNode[]>('/knowledge-graph/mybatis/sql-statements', {
      params
    })
  },

  // ============================================================
  // 业务流程生成接口
  // ============================================================

  /**
   * 生成业务流程图
   */
  generateBusinessFlow(data: BusinessFlowRequest) {
    return request.post<BusinessFlowResponse>('/knowledge-graph/business-flow/generate', data)
  },

  // ============================================================
  // 单元测试生成接口
  // ============================================================

  /**
   * 生成单元测试
   */
  generateUnitTest(data: UnitTestRequest) {
    return request.post<UnitTestResponse>('/knowledge-graph/unit-test/generate', data)
  },

  // ============================================================
  // 桥接关系查询接口
  // ============================================================

  /**
   * 获取方法的桥接关系
   */
  getMethodBridges(nodeId: string, projectPath: string, projectPaths?: string[]) {
    return request.get<BridgeRelation[]>('/knowledge-graph/bridges/method', {
      params: { nodeId, projectPath, projectPaths }
    })
  },

  /**
   * 获取 Mapper SQL 详情
   */
  getMapperSql(mapperInterface: string, projectPath: string, methodName?: string, projectPaths?: string[]) {
    const params: Record<string, string | string[]> = { mapperInterface, projectPath }
    if (methodName) {
      params.methodName = methodName
    }
    if (projectPaths) {
      params.projectPaths = projectPaths
    }
    return request.get<MapperSqlDetail[]>('/knowledge-graph/bridges/mapper-sql', {
      params
    })
  },

  /**
   * 获取 Feign 调用链
   */
  getFeignCallChain(serviceName: string, projectPath: string, projectPaths?: string[]) {
    return request.get<FeignCallChain[]>('/knowledge-graph/bridges/feign-chain', {
      params: { serviceName, projectPath, projectPaths }
    })
  },

  /**
   * 获取 MQ 调用链
   */
  getMQCallChain(topic: string, projectPath: string, projectPaths?: string[]) {
    return request.get<MQCallChain[]>('/knowledge-graph/bridges/mq-chain', {
      params: { topic, projectPath, projectPaths }
    })
  },

  /**
   * 获取项目桥接统计
   */
  getBridgeStats(projectPath: string, projectPaths?: string[]) {
    return request.get<BridgeStats>('/knowledge-graph/bridge-stats', {
      params: { projectPath, projectPaths }
    })
  },

  /**
   * 获取入口点桥接关系
   */
  getEntryBridges(entryKey: string, projectPath: string, projectPaths?: string[]) {
    return request.get<BridgeRelation[]>('/knowledge-graph/bridges/entry', {
      params: { entryKey, projectPath, projectPaths }
    })
  },

  /**
   * 按类型查询桥接关系列表
   */
  getBridgesByType(bridgeType: string, projectPath: string, projectPaths?: string[]) {
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
    }>>('/knowledge-graph/bridges/by-type', {
      params: { bridgeType, projectPath, projectPaths }
    })
  },

  // ============================================================
  // Git 状态和增量生成接口
  // ============================================================

  /**
   * 获取 Git 状态
   */
  getGitStatus(projectPath: string) {
    return request.get<GitStatus>('/knowledge-graph/git-status', {
      params: { projectPath }
    })
  },

  /**
   * 增量生成知识图谱
   */
  incrementalGenerate(projectPath: string) {
    return request.post<KnowledgeGraphTask>('/knowledge-graph/incremental', {
      projectPath
    })
  },

  // ============================================================
  // 跨服务依赖构建接口
  // ============================================================
  crossServiceBuild(projectPaths: string[]) {
    return request.post<{ taskId: number }>('/knowledge-graph/cross-service/build', { projectPaths })
  },

  refresh(projectPath: string) {
    return request.post<{ taskId: number; changedFiles?: string[] }>('/knowledge-graph/refresh', {
      projectPath
    })
  },
}