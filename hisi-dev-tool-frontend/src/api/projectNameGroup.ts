import request from '@/utils/request'

export interface ProjectNameGroup {
  id?: number
  groupName: string
  groupPattern: string
  projectNames: string[]
  description?: string
  createdAt?: string
  updatedAt?: string
}

export const projectNameGroupApi = {
  // 获取所有分组
  getGroups(): Promise<ProjectNameGroup[]> {
    return request.get('/project-name-group') as Promise<ProjectNameGroup[]>
  },

  // 获取单个分组
  getGroup(groupName: string): Promise<ProjectNameGroup> {
    return request.get(`/project-name-group/${groupName}`) as Promise<ProjectNameGroup>
  },

  // 创建或更新分组
  saveGroup(group: ProjectNameGroup): Promise<ProjectNameGroup> {
    return request.post('/project-name-group', group) as Promise<ProjectNameGroup>
  },

  // 删除分组
  deleteGroup(groupName: string): Promise<void> {
    return request.delete(`/project-name-group/${groupName}`)
  }
}