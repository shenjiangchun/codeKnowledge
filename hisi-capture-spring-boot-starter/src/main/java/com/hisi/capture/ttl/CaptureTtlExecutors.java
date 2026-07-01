package com.hisi.capture.ttl;

import com.alibaba.ttl.threadpool.TtlExecutors;

import java.util.concurrent.ExecutorService;

/**
 * 业务方手动包装时用此工具类（explicit 模式）。
 *
 * 用法：
 *   ExecutorService pool = CaptureTtlExecutors.wrap(new ThreadPoolExecutor(...));
 */
public class CaptureTtlExecutors {

    public static ExecutorService wrap(ExecutorService pool) {
        return TtlExecutors.getTtlExecutorService(pool);
    }
}
