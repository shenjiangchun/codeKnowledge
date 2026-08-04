import request from '@/utils/request'
import type { AuthResponse } from '@/types/api'

export const authApi = {
  login(username: string, password: string): Promise<AuthResponse> {
    return request.post('/auth/login', { username, password })
  },

  register(username: string, password: string): Promise<AuthResponse> {
    return request.post('/auth/register', { username, password })
  },

  me(): Promise<{ username: string; role: string } | null> {
    return request.get('/auth/me')
  },
}
