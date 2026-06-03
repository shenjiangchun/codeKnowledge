import axios from 'axios'
import type { AxiosError, AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse, ValidationError } from '@/types/api'
import { parseValidationErrors } from '@/types/api'

const request = axios.create({
  baseURL: '/api',
  timeout: 120000,  // 默认超时 2 分钟
  headers: {
    'Content-Type': 'application/json'
  },
  // Spring @RequestParam List<String> 期望 ?projectPaths=a&projectPaths=b
  // Axios 默认序列化为 projectPaths[]=a&projectPaths[]=b，Spring 无法识别
  paramsSerializer: {
    indexes: null  // null = 不带下标也不带方括号：projectPaths=a&projectPaths=b
  }
})

// 请求拦截器
request.interceptors.request.use(
  (config) => {
    // 可以在这里添加 token 等
    return config
  },
  (error: AxiosError) => {
    return Promise.reject(error)
  }
)

// 增强的错误类型，包含验证错误详情
interface EnhancedAxiosError extends AxiosError {
  validationErrors?: ValidationError[]
}

// 响应拦截器
request.interceptors.response.use(
  (response: AxiosResponse) => {
    const data = response.data

    // 检查是否是 ApiResponse 格式（有 code 字段）
    if (data && typeof data.code === 'number') {
      // ApiResponse 结构：{ code: 200, message: "success", data: ... }
      if (data.code === 200) {
        return data.data
      }
      // code 不是 200，视为业务错误
      const errorMessage = data.message || '请求失败'
      const businessError = new Error(errorMessage) as EnhancedAxiosError
      businessError.response = response as AxiosResponse<ApiResponse<unknown>>
      return Promise.reject(businessError)
    }

    // 非 ApiResponse 格式（直接返回数据，如 ResponseEntity<T>）
    return data
  },
  (error: EnhancedAxiosError) => {
    // 处理 HTTP 错误响应
    if (error.response) {
      const status = error.response.status
      const apiResponse = error.response.data as ApiResponse<unknown> | undefined

      // 处理 400 验证错误
      if (status === 400 && apiResponse?.message) {
        const validationErrors = parseValidationErrors(apiResponse.message)

        if (validationErrors.length > 0) {
          // 格式化验证错误消息
          const errorMessages = validationErrors.map(
            e => `${e.field}: ${e.message}`
          ).join('\n')

          // 显示验证错误提示
          ElMessage({
            type: 'warning',
            message: `参数验证失败: ${errorMessages}`,
            duration: 5000,
            showClose: true
          })

          // 附加验证错误详情到错误对象
          error.validationErrors = validationErrors

          console.warn('参数验证失败:', validationErrors)
          return Promise.reject(error)
        }

        // 如果无法解析验证错误，显示原始消息
        ElMessage.warning(apiResponse.message)
        return Promise.reject(error)
      }

      // 处理 403 — 非本地访问禁止写入
      if (status === 403) {
        const forbiddenMsg = apiResponse?.message || '拒绝访问：仅限本地操作'
        ElMessage({
          type: 'error',
          message: forbiddenMsg,
          duration: 5000,
          showClose: true
        })
        return Promise.reject(error)
      }

      // 处理其他 HTTP 错误
      const errorMessage = apiResponse?.message || getHttpErrorMessage(status)
      ElMessage.error(errorMessage)

      console.error('请求错误:', {
        status,
        message: errorMessage,
        url: error.config?.url
      })
    } else if (error.request) {
      // 请求已发出但没有收到响应
      ElMessage.error('网络连接失败，请检查网络')
      console.error('网络错误:', error.message)
    } else {
      // 请求配置错误
      ElMessage.error('请求配置错误')
      console.error('请求配置错误:', error.message)
    }

    return Promise.reject(error)
  }
)

/**
 * 根据 HTTP 状态码获取错误消息
 */
function getHttpErrorMessage(status: number): string {
  const messages: Record<number, string> = {
    400: '请求参数错误',
    401: '未授权，请先登录',
    403: '拒绝访问',
    404: '请求的资源不存在',
    405: '请求方法不允许',
    408: '请求超时',
    409: '请求冲突',
    422: '请求格式错误',
    429: '请求过于频繁',
    500: '服务器内部错误',
    501: '服务未实现',
    502: '网关错误',
    503: '服务不可用',
    504: '网关超时'
  }
  return messages[status] || `请求失败 (${status})`
}

export default request