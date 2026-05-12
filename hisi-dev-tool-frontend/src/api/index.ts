import request from '@/utils/request'

// 示例 API 接口
export const api = {
  // 健康检查
  healthCheck() {
    return request.get('/health')
  },

  // 获取设备列表
  getDevices() {
    return request.get('/devices')
  },

  // 获取设备详情
  getDeviceDetail(id: string) {
    return request.get(`/devices/${id}`)
  }
}

export default request