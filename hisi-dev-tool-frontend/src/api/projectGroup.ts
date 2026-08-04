import request from '@/utils/request'

export interface ProjectGroup {
  id?: number
  appId: string
  appName: string
  projectPaths: string[]
  description?: string
  createdAt?: string
  updatedAt?: string
}

export const projectGroupApi = {
  // 获取所有分组
  getGroups(): Promise<ProjectGroup[]> {
    return request.get('/project-group') as Promise<ProjectGroup[]>
  },

  // 获取单个分组
  getGroup(appId: string): Promise<ProjectGroup> {
    return request.get(`/project-group/${appId}`) as Promise<ProjectGroup>
  },

  // 创建或更新分组
  saveGroup(group: ProjectGroup): Promise<ProjectGroup> {
    return request.post('/project-group', group) as Promise<ProjectGroup>
  },

  // 删除分组
  deleteGroup(appId: string): Promise<void> {
    return request.delete(`/project-group/${appId}`)
  },

  // 查询项目路径所属的分组
  getGroupByPath(path: string): Promise<ProjectGroup> {
    return request.get('/project-group/by-path', { params: { path } }) as Promise<ProjectGroup>
  }
}