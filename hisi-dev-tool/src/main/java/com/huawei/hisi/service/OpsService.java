package com.huawei.hisi.service;

import com.huawei.hisi.model.*;

import java.util.Map;

/**
 * 运维服务接口
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
public interface OpsService {

    /**
     * 服务健康检查
     *
     * @return 健康状态
     */
    HealthStatus checkHealth();

    /**
     * 影响范围分析
     *
     * @param request 分析请求
     * @return 影响分析结果
     */
    ImpactAnalysisResponse analyzeImpact(ImpactAnalysisRequest request);

    /**
     * 生成接口文档
     *
     * @param uri 接口 URI
     * @return 接口文档
     */
    Map<String, Object> generateInterfaceDoc(String uri);

    /**
     * 下载错误日志
     *
     * @param service   服务名
     * @param timeRange 时间范围
     * @param level     日志级别
     * @return 日志列表
     */
    Map<String, Object> downloadErrorLogs(String service, String timeRange, String level);
}