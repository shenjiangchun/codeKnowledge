/**
 * 工作台 API：聚合待办与 SDD 自动修复运行。
 * 读接口需要登录（跨项目聚合），写接口需要管理员——与后端 AdminOnlyInterceptor 一致。
 */
import request from '@/utils/request'
import type {
  FixRunDetail, WorkbenchFixRun, WorkbenchItem, WorkbenchSummary,
} from '@/types/workbench'

const BASE = '/workbench'

export const workbenchApi = {
  summary(): Promise<WorkbenchSummary> {
    return request.get(`${BASE}/summary`)
  },
  items(): Promise<WorkbenchItem[]> {
    return request.get(`${BASE}/items`)
  },
  sync(): Promise<{ logItems: number; archItems: number; created: number }> {
    return request.post(`${BASE}/items/sync`)
  },
  // itemKey 含冒号/斜杠不能进 URL 路径：操作接口一律用数字 id 定位
  ignore(id: number): Promise<void> {
    return request.post(`${BASE}/items/id/${id}/ignore`)
  },
  restore(id: number): Promise<void> {
    return request.post(`${BASE}/items/id/${id}/restore`)
  },
  autofix(id: number, pushRequested: boolean): Promise<WorkbenchFixRun> {
    return request.post(`${BASE}/items/id/${id}/autofix`, { pushRequested })
  },
  autofixBatch(itemIds: number[], pushRequested: boolean): Promise<{ started: string[]; failed: string[] }> {
    return request.post(`${BASE}/items/autofix`, { itemIds, pushRequested })
  },
  runs(): Promise<WorkbenchFixRun[]> {
    return request.get(`${BASE}/runs`)
  },
  runDetail(id: number): Promise<FixRunDetail> {
    return request.get(`${BASE}/runs/${id}`)
  },
  confirm(id: number, optionId: string, pushRequested: boolean): Promise<WorkbenchFixRun> {
    return request.post(`${BASE}/runs/${id}/confirm`, { optionId, pushRequested })
  },
  reject(id: number, feedback: string): Promise<WorkbenchFixRun> {
    return request.post(`${BASE}/runs/${id}/reject`, { feedback })
  },
  abort(id: number): Promise<void> {
    return request.post(`${BASE}/runs/${id}/abort`)
  },
  resume(id: number): Promise<WorkbenchFixRun> {
    return request.post(`${BASE}/runs/${id}/resume`)
  },
}
