package com.huawei.hisi.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花算法 ID 生成器
 * 生成全局唯一的、时间递增的 64 位 ID
 *
 * ID 结构 (64 位):
 * - 1 位符号位 (始终为 0)
 * - 41 位时间戳 (毫秒级，可使用约 69 年)
 * - 5 位数据中心 ID (最多 32 个)
 * - 5 位机器 ID (最多 32 个)
 * - 12 位序列号 (每毫秒最多生成 4096 个 ID)
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    // 起始时间戳：2024-01-01 00:00:00
    private static final long START_TIMESTAMP = 1704067200000L;

    // 位数配置
    private static final long DATA_CENTER_BITS = 5L;
    private static final long MACHINE_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    // 最大位移量
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    // 位移配置
    private static final long SEQUENCE_SHIFT = 0L;
    private static final long MACHINE_SHIFT = SEQUENCE_BITS;
    private static final long DATA_CENTER_SHIFT = SEQUENCE_BITS + MACHINE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_BITS + DATA_CENTER_BITS;

    // 数据中心 ID 和机器 ID (从配置文件读取，默认值为 1)
    private final long dataCenterId;
    private final long machineId;

    // 序列号和时间戳
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    /**
     * 构造函数 - 从配置文件读取参数
     *
     * @param dataCenterId 数据中心 ID (0-31)，默认 1
     * @param machineId 机器 ID (0-31)，默认 1
     */
    public SnowflakeIdGenerator(
            @Value("${snowflake.datacenter.id:1}") long dataCenterId,
            @Value("${snowflake.machine.id:1}") long machineId) {
        if (dataCenterId < 0 || dataCenterId >= (1L << DATA_CENTER_BITS)) {
            throw new IllegalArgumentException(
                String.format("数据中心 ID 必须在 0-%d 范围内", (1L << DATA_CENTER_BITS) - 1));
        }
        if (machineId < 0 || machineId >= (1L << MACHINE_BITS)) {
            throw new IllegalArgumentException(
                String.format("机器 ID 必须在 0-%d 范围内", (1L << MACHINE_BITS) - 1));
        }

        this.dataCenterId = dataCenterId;
        this.machineId = machineId;
        log.info("SnowflakeIdGenerator initialized: dataCenterId={}, machineId={}", dataCenterId, machineId);
    }

    /**
     * 生成下一个唯一 ID (线程安全)
     *
     * @return 全局唯一的雪花 ID
     */
    public synchronized long nextId() {
        long timestamp = getCurrentTimestamp();

        // 时钟回拨检测
        if (timestamp < lastTimestamp) {
            log.error("检测到时钟回拨！当前时间：{}, 上次时间：{}", timestamp, lastTimestamp);
            throw new RuntimeException(
                String.format("时钟回拨！当前时间 %d 小于上次时间 %d", timestamp, lastTimestamp));
        }

        // 同一毫秒内，序列号自增
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 序列号溢出，等待下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // 新毫秒，序列号重置为 0
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 组装 ID
        return ((timestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (dataCenterId << DATA_CENTER_SHIFT)
                | (machineId << MACHINE_SHIFT)
                | sequence;
    }

    /**
     * 获取当前时间戳（毫秒）
     */
    private long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    /**
     * 等待下一毫秒
     */
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = getCurrentTimestamp();
        while (timestamp <= lastTimestamp) {
            timestamp = getCurrentTimestamp();
        }
        return timestamp;
    }

    /**
     * 获取数据中心 ID
     */
    public long getDataCenterId() {
        return dataCenterId;
    }

    /**
     * 获取机器 ID
     */
    public long getMachineId() {
        return machineId;
    }

    /**
     * 从 ID 中提取时间戳
     */
    public static long getTimestampFromId(long id) {
        long timestamp = (id >> TIMESTAMP_SHIFT) + START_TIMESTAMP;
        return timestamp;
    }

    /**
     * 从 ID 中提取数据中心 ID
     */
    public static long getDataCenterFromId(long id) {
        return (id >> DATA_CENTER_SHIFT) & ((1L << DATA_CENTER_BITS) - 1);
    }

    /**
     * 从 ID 中提取机器 ID
     */
    public static long getMachineFromId(long id) {
        return (id >> MACHINE_SHIFT) & ((1L << MACHINE_BITS) - 1);
    }
}