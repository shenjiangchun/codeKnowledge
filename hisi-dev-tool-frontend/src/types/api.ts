/**
 * API 响应类型定义
 * 与后端 ApiResponse.java 保持一致
 */

/**
 * 通用 API 响应结构
 */
export interface ApiResponse<T = unknown> {
  /**
   * 响应码：200 成功，其他失败
   */
  code: number

  /**
   * 响应消息
   */
  message: string

  /**
   * 响应数据
   */
  data: T | null
}

export interface UserInfo {
  id: number
  username: string
  role: 'ADMIN' | 'MEMBER'
  createdAt: number
}

export interface AuthResponse {
  token: string
  username: string
  role: string
}

/**
 * 验证错误详情
 * 解析后端 400 响应中的 message 字段
 */
export interface ValidationError {
  field: string
  message: string
}

/**
 * 解析验证错误消息
 * 后端格式: "参数校验失败: field1: message1, field2: message2"
 */
export function parseValidationErrors(errorMessage: string): ValidationError[] {
  const errors: ValidationError[] = []

  // 去除前缀 "参数校验失败: "
  const cleanedMessage = errorMessage.replace(/^参数校验失败:\s*/, '')

  // 按 ", " 分割各个字段错误
  const fieldErrors = cleanedMessage.split(/,\s+/)

  for (const fieldError of fieldErrors) {
    // 按 ": " 分割字段名和错误消息
    const parts = fieldError.split(/:\s+/)
    if (parts.length >= 2) {
      errors.push({
        field: parts[0].trim(),
        message: parts.slice(1).join(': ').trim()
      })
    }
  }

  return errors
}

/**
 * 判断响应是否成功
 */
export function isSuccessResponse<T>(response: ApiResponse<T>): boolean {
  return response.code === 200
}

/**
 * 创建错误响应
 */
export function createErrorResponse(code: number, message: string): ApiResponse<null> {
  return {
    code,
    message,
    data: null
  }
}