export interface RemoteProject {
  id: number
  name: string
  gitUrl: string
  username: string
  branch: string
  localPath: string  // Project name slug (e.g., "activity-management-service")
  fullPath: string   // Complete physical path where project is cloned (e.g., "D:/codeknowledge/remote-repos/activity-management-service")
  cloneStatus: 'PENDING' | 'CLONING' | 'CLONED' | 'FAILED'
  cloneError: string | null
  lastSyncAt: number | null
  authType: 'PASSWORD' | 'SSH_KEY' | 'TOKEN'
  sshKeyPath?: string
  groupId?: string
  groupName?: string
}

export interface CreateRemoteProjectRequest {
  name: string
  gitUrl: string
  username?: string
  password?: string
  branch?: string
  authType?: 'PASSWORD' | 'SSH_KEY' | 'TOKEN'
  sshKeyPath?: string
  token?: string
  groupId?: string
}

export interface UpdateRemoteProjectRequest {
  name: string
  gitUrl: string
  username?: string
  password?: string
  branch?: string
  authType?: 'PASSWORD' | 'SSH_KEY' | 'TOKEN'
  sshKeyPath?: string
  token?: string
  groupId?: string
}

export interface KgSchedule {
  id: number
  projectPath: string
  cronExpression: string
  buildMode: 'INCREMENTAL' | 'REUSE' | 'WIPE'
  enabled: boolean
  gitPullEnabled: boolean
  branch: string
  refreshDescription: boolean
  refreshArchitecture: boolean
  lastRunAt: number | null
  nextRunAt: number | null
}

export interface CreateKgScheduleRequest {
  projectPath: string
  cronExpression: string
  buildMode: 'INCREMENTAL' | 'REUSE' | 'WIPE'
  gitPullEnabled?: boolean
  branch?: string
  refreshDescription?: boolean
  refreshArchitecture?: boolean
}

export interface UpdateKgScheduleRequest {
  projectPath: string
  cronExpression: string
  buildMode: 'INCREMENTAL' | 'REUSE' | 'WIPE'
  enabled: boolean
  gitPullEnabled: boolean
  branch: string
  refreshDescription: boolean
  refreshArchitecture: boolean
}
