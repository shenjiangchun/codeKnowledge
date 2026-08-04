package com.huawei.hisi.service;

import com.huawei.hisi.model.LogEntry;
import com.huawei.hisi.model.LogQueryDto;

import java.util.List;

/**
 * 日志云服务接口
 * 支持 HTTP API 直接调用方式
 */
public interface LogCloudService {

    /**
     * 初始化 API 认证
     * HTTP API 方式使用 AppKey 认证，无需登录
     */
    void login();

    /**
     * 查询日志
     *
     * @param query 查询条件
     * @return 日志列表
     */
    List<LogEntry> queryLogs(LogQueryDto query);

    /**
     * 清理会话
     * HTTP API 方式无需退出登录
     */
    void logout();
}