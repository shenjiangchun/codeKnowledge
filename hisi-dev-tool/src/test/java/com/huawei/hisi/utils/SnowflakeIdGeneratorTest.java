package com.huawei.hisi.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnowflakeIdGenerator 单元测试
 * 测试雪花算法 ID 生成器的唯一性、时间递增性等特性
 */
class SnowflakeIdGeneratorTest {

    // ==================== Constructor Tests ====================

    @Test
    @DisplayName("构造函数 - 正常参数初始化成功")
    void testConstructor_ValidParameters() {
        // When
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 1L);

        // Then
        assertNotNull(generator);
        assertEquals(1L, generator.getDataCenterId());
        assertEquals(1L, generator.getMachineId());
    }

    @Test
    @DisplayName("构造函数 - 边界值：数据中心 ID 为 0")
    void testConstructor_DataCenterIdZero() {
        // When
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0L, 1L);

        // Then
        assertEquals(0L, generator.getDataCenterId());
    }

    @Test
    @DisplayName("构造函数 - 边界值：数据中心 ID 为最大值 31")
    void testConstructor_DataCenterIdMax() {
        // When
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(31L, 1L);

        // Then
        assertEquals(31L, generator.getDataCenterId());
    }

    @Test
    @DisplayName("构造函数 - 边界值：机器 ID 为 0")
    void testConstructor_MachineIdZero() {
        // When
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);

        // Then
        assertEquals(0L, generator.getMachineId());
    }

    @Test
    @DisplayName("构造函数 - 边界值：机器 ID 为最大值 31")
    void testConstructor_MachineIdMax() {
        // When
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 31L);

        // Then
        assertEquals(31L, generator.getMachineId());
    }

    @Test
    @DisplayName("构造函数 - 异常：数据中心 ID 为负数")
    void testConstructor_NegativeDataCenterId() {
        // When & Then
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new SnowflakeIdGenerator(-1L, 1L)
        );

        assertTrue(exception.getMessage().contains("数据中心 ID"));
    }

    @Test
    @DisplayName("构造函数 - 异常：数据中心 ID 超过最大值")
    void testConstructor_DataCenterIdExceeded() {
        // When & Then
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new SnowflakeIdGenerator(32L, 1L)
        );

        assertTrue(exception.getMessage().contains("数据中心 ID"));
    }

    @Test
    @DisplayName("构造函数 - 异常：机器 ID 为负数")
    void testConstructor_NegativeMachineId() {
        // When & Then
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new SnowflakeIdGenerator(1L, -1L)
        );

        assertTrue(exception.getMessage().contains("机器 ID"));
    }

    @Test
    @DisplayName("构造函数 - 异常：机器 ID 超过最大值")
    void testConstructor_MachineIdExceeded() {
        // When & Then
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new SnowflakeIdGenerator(1L, 32L)
        );

        assertTrue(exception.getMessage().contains("机器 ID"));
    }

    // ==================== nextId Tests ====================

    @Test
    @DisplayName("生成 ID - 单个 ID 生成成功")
    void testNextId_SingleId() {
        // Given
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 1L);

        // When
        long id = generator.nextId();

        // Then
        assertTrue(id > 0);
    }

    @Test
    @DisplayName("生成 ID - 多个 ID 唯一性")
    void testNextId_Uniqueness() {
        // Given
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 1L);
        Set<Long> ids = new HashSet<>();
        int count = 10000;

        // When
        for (int i = 0; i < count; i++) {
            ids.add(generator.nextId());
        }

        // Then
        assertEquals(count, ids.size(), "所有生成的 ID 应该唯一");
    }

    @Test
    @DisplayName("生成 ID - 时间递增性")
    void testNextId_Increasing() {
        // Given
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 1L);

        // When
        long id1 = generator.nextId();
        long id2 = generator.nextId();
        long id3 = generator.nextId();

        // Then
        assertTrue(id2 > id1, "后续 ID 应该大于前一个 ID");
        assertTrue(id3 > id2, "后续 ID 应该大于前一个 ID");
    }

    @Test
    @DisplayName("生成 ID - 连续生成大量 ID")
    void testNextId_LargeVolume() {
        // Given
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 1L);
        Set<Long> ids = new HashSet<>();
        int count = 100000;

        // When
        for (int i = 0; i < count; i++) {
            ids.add(generator.nextId());
        }

        // Then
        assertEquals(count, ids.size(), "生成的 ID 数量应该与请求数量一致");
    }

    @Test
    @DisplayName("生成 ID - 多线程唯一性")
    void testNextId_ThreadSafety() throws InterruptedException {
        // Given
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 1L);
        int threadCount = 10;
        int idsPerThread = 1000;
        Set<Long> ids = new HashSet<>();
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < idsPerThread; j++) {
                        synchronized (ids) {
                            ids.add(generator.nextId());
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Then
        assertEquals(threadCount * idsPerThread, ids.size(), "多线程生成的 ID 应该全部唯一");
    }

    @Test
    @DisplayName("生成 ID - 不同数据中心 ID 生成不同 ID")
    void testNextId_DifferentDataCenters() {
        // Given
        SnowflakeIdGenerator generator1 = new SnowflakeIdGenerator(1L, 1L);
        SnowflakeIdGenerator generator2 = new SnowflakeIdGenerator(2L, 1L);

        // When
        long id1 = generator1.nextId();
        long id2 = generator2.nextId();

        // Then
        assertNotEquals(id1, id2, "不同数据中心的 ID 应该不同");

        // 验证数据中心 ID 可以从 ID 中提取
        assertEquals(1L, SnowflakeIdGenerator.getDataCenterFromId(id1));
        assertEquals(2L, SnowflakeIdGenerator.getDataCenterFromId(id2));
    }

    @Test
    @DisplayName("生成 ID - 不同机器 ID 生成不同 ID")
    void testNextId_DifferentMachines() {
        // Given
        SnowflakeIdGenerator generator1 = new SnowflakeIdGenerator(1L, 1L);
        SnowflakeIdGenerator generator2 = new SnowflakeIdGenerator(1L, 2L);

        // When
        long id1 = generator1.nextId();
        long id2 = generator2.nextId();

        // Then
        assertNotEquals(id1, id2, "不同机器的 ID 应该不同");

        // 验证机器 ID 可以从 ID 中提取
        assertEquals(1L, SnowflakeIdGenerator.getMachineFromId(id1));
        assertEquals(2L, SnowflakeIdGenerator.getMachineFromId(id2));
    }

    // ==================== Static Extraction Tests ====================

    @Test
    @DisplayName("静态方法 - 从 ID 提取时间戳")
    void testGetTimestampFromId() {
        // Given
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 1L);
        long beforeTime = System.currentTimeMillis();

        // When
        long id = generator.nextId();
        long extractedTimestamp = SnowflakeIdGenerator.getTimestampFromId(id);

        // Then
        long afterTime = System.currentTimeMillis();
        assertTrue(extractedTimestamp >= beforeTime);
        assertTrue(extractedTimestamp <= afterTime);
    }

    @Test
    @DisplayName("静态方法 - 从 ID 提取数据中心 ID")
    void testGetDataCenterFromId() {
        // Given
        long dataCenterId = 15L;
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(dataCenterId, 1L);

        // When
        long id = generator.nextId();
        long extractedDataCenterId = SnowflakeIdGenerator.getDataCenterFromId(id);

        // Then
        assertEquals(dataCenterId, extractedDataCenterId);
    }

    @Test
    @DisplayName("静态方法 - 从 ID 提取机器 ID")
    void testGetMachineFromId() {
        // Given
        long machineId = 20L;
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, machineId);

        // When
        long id = generator.nextId();
        long extractedMachineId = SnowflakeIdGenerator.getMachineFromId(id);

        // Then
        assertEquals(machineId, extractedMachineId);
    }

    @Test
    @DisplayName("静态方法 - 从 ID 提取所有组件")
    void testExtractAllComponents() {
        // Given
        long dataCenterId = 10L;
        long machineId = 20L;
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(dataCenterId, machineId);

        // When
        long id = generator.nextId();

        // Then
        assertEquals(dataCenterId, SnowflakeIdGenerator.getDataCenterFromId(id));
        assertEquals(machineId, SnowflakeIdGenerator.getMachineFromId(id));
        assertTrue(SnowflakeIdGenerator.getTimestampFromId(id) > 0);
    }

    // ==================== Performance Tests ====================

    @Test
    @DisplayName("性能测试 - ID 生成速度")
    void testNextId_Performance() {
        // Given
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 1L);
        int count = 100000;
        long startTime = System.currentTimeMillis();

        // When
        for (int i = 0; i < count; i++) {
            generator.nextId();
        }

        // Then
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double idsPerMs = count / (double) duration;

        System.out.println("Generated " + count + " IDs in " + duration + "ms");
        System.out.println("Rate: " + String.format("%.2f", idsPerMs) + " IDs/ms");

        assertTrue(idsPerMs > 100, "ID 生成速度应该大于 100/ms");
    }
}