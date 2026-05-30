/**
 * request.ts 单元测试
 * 测试 HTTP 请求响应拦截器的行为
 *
 * TDD 流程:
 * 1. RED - 编写失败的测试，验证期望的行为
 * 2. GREEN - 确保现有实现通过测试
 * 3. REFACTOR - 优化测试代码
 */

import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import axios from 'axios'
import type { AxiosResponse, AxiosError } from 'axios'

// Mock axios
vi.mock('axios', () => {
  const mockAxios = {
    create: vi.fn(() => mockAxios),
    interceptors: {
      request: {
        use: vi.fn()
      },
      response: {
        use: vi.fn((successHandler, errorHandler) => {
          mockAxios._successHandler = successHandler
          mockAxios._errorHandler = errorHandler
        })
      }
    },
    _successHandler: null as ((response: AxiosResponse) => any) | null,
    _errorHandler: null as ((error: AxiosError) => any) | null,
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
  return {
    default: mockAxios
  }
})

// Mock ElMessage - 需要支持 ElMessage.error(), ElMessage.warning() 等调用方式
const mockElMessageFn = vi.fn()
const mockElMessage = Object.assign(mockElMessageFn, {
  error: vi.fn(),
  warning: vi.fn(),
  success: vi.fn(),
  info: vi.fn()
})
vi.mock('element-plus', () => ({
  ElMessage: mockElMessage
}))

describe('request.ts 响应拦截器', () => {
  let successHandler: (response: AxiosResponse) => any
  let errorHandler: (error: AxiosError) => any

  beforeEach(async () => {
    vi.clearAllMocks()
    // 重新导入以触发拦截器注册
    const requestModule = await import('@/utils/request')
    const mockAxios = vi.mocked(axios)
    successHandler = mockAxios._successHandler!
    errorHandler = mockAxios._errorHandler!
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  describe('ApiResponse 格式响应处理', () => {
    test('code=200 时返回 data 部分', async () => {
      // RED: 验证期望的行为
      const response = {
        data: {
          code: 200,
          message: 'success',
          data: { id: 1, name: 'test' }
        }
      } as AxiosResponse

      const result = successHandler(response)

      expect(result).toEqual({ id: 1, name: 'test' })
    })

    test('code!=200 时视为业务错误并 reject', async () => {
      const response = {
        data: {
          code: 400,
          message: '参数错误',
          data: null
        }
      } as AxiosResponse

      await expect(successHandler(response)).rejects.toThrow('参数错误')
    })

    test('code=500 时返回错误消息', async () => {
      const response = {
        data: {
          code: 500,
          message: '服务器内部错误',
          data: null
        }
      } as AxiosResponse

      await expect(successHandler(response)).rejects.toThrow('服务器内部错误')
    })

    test('code 不是数字时不视为 ApiResponse', async () => {
      // 边界情况: code 为字符串
      const response = {
        data: {
          code: '200',  // 字符串不是 number
          message: 'success',
          data: { id: 1 }
        }
      } as AxiosResponse

      // 应该直接返回整个 data 对象
      const result = successHandler(response)
      expect(result).toEqual({
        code: '200',
        message: 'success',
        data: { id: 1 }
      })
    })
  })

  describe('非 ApiResponse 格式响应处理', () => {
    test('ResponseEntity 格式直接返回 data', async () => {
      // Spring ResponseEntity<T> 格式: 直接返回数据，无 code 字段
      const response = {
        data: { id: 1, name: 'test', status: 'active' }
      } as AxiosResponse

      const result = successHandler(response)

      expect(result).toEqual({ id: 1, name: 'test', status: 'active' })
    })

    test('空对象响应直接返回', async () => {
      const response = {
        data: {}
      } as AxiosResponse

      const result = successHandler(response)

      expect(result).toEqual({})
    })

    test('null 响应直接返回', async () => {
      const response = {
        data: null
      } as AxiosResponse

      const result = successHandler(response)

      expect(result).toBeNull()
    })

    test('数组响应直接返回', async () => {
      const response = {
        data: [1, 2, 3]
      } as AxiosResponse

      const result = successHandler(response)

      expect(result).toEqual([1, 2, 3])
    })
  })

  describe('HTTP 错误处理', () => {
    test('400 验证错误解析字段错误', async () => {
      const error = {
        response: {
          status: 400,
          data: {
            code: 400,
            message: '参数校验失败: name: 不能为空, age: 必须大于0'
          }
        }
      } as AxiosError

      await expect(errorHandler(error)).rejects.toThrow()

      const { ElMessage } = await import('element-plus')
      // 400 验证错误使用 ElMessage({...}) 形式，type 为 'warning'
      expect(ElMessage).toHaveBeenCalledWith(expect.objectContaining({
        type: 'warning'
      }))
    })

    test('401 未授权错误 - 使用 API 消息', async () => {
      const error = {
        response: {
          status: 401,
          data: {
            code: 401,
            message: '未授权'
          }
        }
      } as AxiosError

      await expect(errorHandler(error)).rejects.toThrow()

      const { ElMessage } = await import('element-plus')
      // 代码优先使用 API 返回的 message
      expect(ElMessage.error).toHaveBeenCalledWith('未授权')
    })

    test('401 未授权错误 - 无 message 时使用默认消息', async () => {
      const error = {
        response: {
          status: 401,
          data: {}
        }
      } as AxiosError

      await expect(errorHandler(error)).rejects.toThrow()

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('未授权，请先登录')
    })

    test('404 资源不存在错误 - 使用 API 消息', async () => {
      const error = {
        response: {
          status: 404,
          data: {
            code: 404,
            message: '资源不存在'
          }
        }
      } as AxiosError

      await expect(errorHandler(error)).rejects.toThrow()

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('资源不存在')
    })

    test('404 资源不存在错误 - 无 message 时使用默认消息', async () => {
      const error = {
        response: {
          status: 404,
          data: {}
        }
      } as AxiosError

      await expect(errorHandler(error)).rejects.toThrow()

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('请求的资源不存在')
    })

    test('500 服务器错误 - 使用 API 消息', async () => {
      const error = {
        response: {
          status: 500,
          data: {
            code: 500,
            message: '服务器错误'
          }
        }
      } as AxiosError

      await expect(errorHandler(error)).rejects.toThrow()

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('服务器错误')
    })

    test('500 服务器错误 - 无 message 时使用默认消息', async () => {
      const error = {
        response: {
          status: 500,
          data: {}
        }
      } as AxiosError

      await expect(errorHandler(error)).rejects.toThrow()

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('服务器内部错误')
    })
  })

  describe('网络错误处理', () => {
    test('网络连接失败', async () => {
      const error = {
        request: {},
        message: 'Network Error'
      } as AxiosError

      await expect(errorHandler(error)).rejects.toThrow()

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('网络连接失败，请检查网络')
    })

    test('请求配置错误', async () => {
      const error = {
        message: 'Invalid URL'
      } as AxiosError

      await expect(errorHandler(error)).rejects.toThrow()

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('请求配置错误')
    })
  })
})

describe('parseValidationErrors', () => {
  test('解析单个字段错误', async () => {
    const { parseValidationErrors } = await import('@/types/api')

    const result = parseValidationErrors('参数校验失败: name: 不能为空')

    expect(result).toEqual([
      { field: 'name', message: '不能为空' }
    ])
  })

  test('解析多个字段错误', async () => {
    const { parseValidationErrors } = await import('@/types/api')

    const result = parseValidationErrors('参数校验失败: name: 不能为空, age: 必须大于0')

    expect(result).toEqual([
      { field: 'name', message: '不能为空' },
      { field: 'age', message: '必须大于0' }
    ])
  })

  test('解析带冒号的错误消息', async () => {
    const { parseValidationErrors } = await import('@/types/api')

    const result = parseValidationErrors('参数校验失败: url: 格式错误: 必须以http开头')

    expect(result).toEqual([
      { field: 'url', message: '格式错误: 必须以http开头' }
    ])
  })

  test('空消息返回空数组', async () => {
    const { parseValidationErrors } = await import('@/types/api')

    const result = parseValidationErrors('')

    expect(result).toEqual([])
  })

  test('无前缀消息仍然解析', async () => {
    const { parseValidationErrors } = await import('@/types/api')

    const result = parseValidationErrors('name: 不能为空')

    expect(result).toEqual([
      { field: 'name', message: '不能为空' }
    ])
  })
})