import request from '@/utils/request'
import type { KgSchedule, CreateKgScheduleRequest, UpdateKgScheduleRequest } from '@/types/remote-project'

export function listKgSchedules(): Promise<KgSchedule[]> {
  return request.get('/kg-schedules')
}

export function createKgSchedule(data: CreateKgScheduleRequest): Promise<{ id: number }> {
  return request.post('/kg-schedules', data)
}

export function updateKgSchedule(id: number, data: UpdateKgScheduleRequest): Promise<string> {
  return request.put(`/kg-schedules/${id}`, data)
}

export function deleteKgSchedule(id: number): Promise<string> {
  return request.delete(`/kg-schedules/${id}`)
}
