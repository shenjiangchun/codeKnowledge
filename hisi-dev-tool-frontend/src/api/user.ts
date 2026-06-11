import request from '@/utils/request'
import type { UserInfo } from '@/types/api'

export const userApi = {
  list(): Promise<UserInfo[]> {
    return request.get('/users')
  },

  changeRole(id: number, role: string): Promise<void> {
    return request.put(`/users/${id}/role`, { role })
  },
}
