import request from '@/utils/request'

export interface GitStatus {
  branch: string
  clean: boolean
  ahead: number
  behind: number
  modified: string[]
  untracked: string[]
}

export interface GitLog {
  commitId: string
  message: string
  author: string
  date: string
}

export interface GitCommit {
  commitId: string
  fullCommitId: string
  shortMessage: string
  fullMessage: string
  author: string
  authorEmail: string
  date: string
}

export interface CommitDiff {
  commitId: string
  fullCommitId: string
  message: string
  author: string
  date: string
  files: Array<{
    changeType: string
    oldPath: string
    newPath: string
  }>
}

export interface UpdateAllResult {
  path: string
  absolutePath: string
  branch: string
  success: boolean
  message: string
}

export interface UpdateAllResponse {
  totalRepos: number
  successCount: number
  failCount: number
  results: UpdateAllResult[]
}

export const gitApi = {
  // 获取 Git 状态
  getStatus(path: string) {
    return request.get<GitStatus>('/git/status', { params: { path } })
  },

  // 切换分支
  checkout(path: string, branch: string) {
    return request.post('/git/checkout', { path, branch })
  },

  // 拉取最新代码
  pull(path: string) {
    return request.post('/git/pull', { path })
  },

  // 获取提交日志
  getLogs(path: string, limit: number = 10) {
    return request.get<GitLog[]>('/git/logs', { params: { path, limit } })
  },

  // 获取提交列表（带详细信息）
  getCommits(path: string, limit: number = 50) {
    return request.get<GitCommit[]>('/git/commits', { params: { path, limit } })
  },

  // 获取提交详情
  getCommitDiff(path: string, commitId: string) {
    return request.get<CommitDiff>('/git/commit-diff', { params: { path, commitId } })
  },

  // 一键更新所有仓库
  updateAll(projectDir: string) {
    return request.post<UpdateAllResponse>('/git/update-all', { projectDir })
  }
}