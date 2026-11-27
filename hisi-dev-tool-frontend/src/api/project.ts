import request from '@/utils/request'
import type { GitRepositoryInfo } from '@/types/callchain'

export const projectApi = {
  // 获取项目列表
  getProjects() {
    return request.get('/projects/list')
  },

  // 克隆项目
  clone(data: { url: string; branch?: string; directory?: string }) {
    return request.post('/projects/clone', data)
  },

  // 扫描项目目录中的 Git 仓库
  scanGitRepos() {
    return request.get<GitRepositoryInfo[]>('/projects/scan-git-repos')
  }
}