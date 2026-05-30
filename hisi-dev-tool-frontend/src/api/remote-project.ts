import request from '@/utils/request'
import type { RemoteProject, CreateRemoteProjectRequest, UpdateRemoteProjectRequest } from '@/types/remote-project'

export function listRemoteProjects(): Promise<RemoteProject[]> {
  return request.get('/remote-projects')
}

export function createRemoteProject(data: CreateRemoteProjectRequest): Promise<{ id: number }> {
  return request.post('/remote-projects', data)
}

export function updateRemoteProject(id: number, data: UpdateRemoteProjectRequest): Promise<string> {
  return request.put(`/remote-projects/${id}`, data)
}

export function deleteRemoteProject(id: number): Promise<string> {
  return request.delete(`/remote-projects/${id}`)
}

export function cloneRemoteProject(id: number): Promise<string> {
  return request.post(`/remote-projects/${id}/clone`)
}

export function pullRemoteProject(id: number): Promise<string> {
  return request.post(`/remote-projects/${id}/pull`)
}
