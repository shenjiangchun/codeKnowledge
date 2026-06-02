export interface RemoteProject {
  id: number
  name: string
  gitUrl: string
  username: string
  branch: string
  localPath: string
  cloneStatus: 'PENDING' | 'CLONING' | 'CLONED' | 'FAILED'
  cloneError: string | null
  lastSyncAt: number | null
}

export interface CreateRemoteProjectRequest {
  name: string
  gitUrl: string
  username?: string
  password?: string
  branch: string
}

export interface UpdateRemoteProjectRequest {
  name: string
  gitUrl: string
  username?: string
  password?: string
  branch: string
}

export interface KgSchedule {
  id: number
  projectPath: string
  cronExpression: string
  taskType: 'FULL' | 'INCREMENTAL'
  enabled: boolean
  lastRunAt: number | null
  nextRunAt: number | null
}

export interface CreateKgScheduleRequest {
  projectPath: string
  cronExpression: string
  taskType: 'FULL' | 'INCREMENTAL'
}

export interface UpdateKgScheduleRequest {
  projectPath: string
  cronExpression: string
  taskType: 'FULL' | 'INCREMENTAL'
  enabled: boolean
}
