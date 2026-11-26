import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/utils/request', () => ({
  default: {
    defaults: { baseURL: '/api' },
    post: vi.fn(),
    get: vi.fn(),
  },
}))

import { workflowStreamUrl } from '../workflow'

describe('workflowStreamUrl', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('追加 afterSeq 与 token 查询参数（EventSource 无法带 Authorization 头）', () => {
    localStorage.setItem('hisi-token', 'jwt-abc')
    expect(workflowStreamUrl('s1', 0)).toBe(
      '/api/v2/workflow/sessions/s1/stream?afterSeq=0&token=jwt-abc',
    )
  })

  it('无 token 时不追加 token 参数', () => {
    expect(workflowStreamUrl('s1', 3)).toBe('/api/v2/workflow/sessions/s1/stream?afterSeq=3')
  })
})
