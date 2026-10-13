/**
 * 权威分层偏序（高 → 低，UTILITY 为叶子）
 * 与后端 ClassLayerRoleDetector.layerOrderList() 保持一致
 * 单一来源：前端可视化与后端报告共用此层序
 */
export const LAYER_ORDER = ['CONTROLLER', 'SERVICE', 'REPOSITORY', 'MODEL', 'UTILITY'] as const
