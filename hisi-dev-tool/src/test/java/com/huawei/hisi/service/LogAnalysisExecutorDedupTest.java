package com.huawei.hisi.service;

import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LogAnalysisExecutor 单元测试 - 指纹去重功能
 */
@ExtendWith(MockitoExtension.class)
class LogAnalysisExecutorDedupTest {

    @Mock
    private LogAnalysisRepository repository;

    @Mock
    private FingerprintService fingerprintService;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private LogAnalysisExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new LogAnalysisExecutor(repository, snowflakeIdGenerator, fingerprintService);
    }

    @Test
    @DisplayName("提交分析 - 新日志创建新报告")
    void testSubmitForAnalysis_NewLogCreatesReport() {
        // Given
        String message = "NullPointerException: null object";
        String stackTrace = "at com.example.Service.method(Service.java:50)";
        String fingerprint = "abc123def456";
        Long expectedReportId = 12345L;

        when(snowflakeIdGenerator.nextId()).thenReturn(expectedReportId);
        when(fingerprintService.generateFingerprint(anyString())).thenReturn(fingerprint);
        when(repository.findByFingerprint(fingerprint)).thenReturn(null);

        // When
        SubmitResult result = executor.submitForAnalysis(message, stackTrace, "user1", null);

        // Then
        assertEquals(expectedReportId, result.reportId());
        assertTrue(result.isNew());
        assertFalse(result.isDuplicate());
        verify(repository).save(any(LogAnalysisReportEntity.class));
        verify(repository, never()).incrementOccurrenceCount(anyLong());
    }

    @Test
    @DisplayName("提交分析 - 重复日志更新出现次数")
    void testSubmitForAnalysis_DuplicateLogUpdatesOccurrenceCount() {
        // Given
        String message = "NullPointerException: null object";
        String stackTrace1 = "at com.example.Service.method(Service.java:50)";
        String stackTrace2 = "at com.example.Service.method(Service.java:55)";
        String fingerprint = "abc123def456";

        LogAnalysisReportEntity existingReport = new LogAnalysisReportEntity();
        existingReport.setReportId(12345L);
        existingReport.setErrorFingerprint(fingerprint);
        existingReport.setOccurrenceCount(1);

        when(fingerprintService.generateFingerprint(anyString())).thenReturn(fingerprint);
        when(repository.findByFingerprint(fingerprint)).thenReturn(existingReport);

        // When - first log (duplicate)
        SubmitResult result1 = executor.submitForAnalysis(message, stackTrace1, "user1", null);
        // When - second log (duplicate)
        SubmitResult result2 = executor.submitForAnalysis(message, stackTrace2, "user1", null);

        // Then - both should return same reportId and isDuplicate=true
        assertEquals(12345L, result1.reportId());
        assertEquals(12345L, result2.reportId());
        assertEquals(result1.reportId(), result2.reportId());
        assertTrue(result1.isDuplicate());
        assertTrue(result2.isDuplicate());
        assertFalse(result1.isNew());
        assertFalse(result2.isNew());
        verify(repository, times(2)).incrementOccurrenceCount(12345L);
        verify(repository, never()).save(any(LogAnalysisReportEntity.class));
    }

    @Test
    @DisplayName("提交分析 - 生成指纹并保存")
    void testSubmitForAnalysis_GeneratesAndSavesFingerprint() {
        // Given
        String message = "NullPointerException";
        String stackTrace = "at com.example.Service.method(Service.java:50)";
        String expectedFingerprint = "abc123def456";
        Long expectedReportId = 99999L;

        when(snowflakeIdGenerator.nextId()).thenReturn(expectedReportId);
        when(fingerprintService.generateFingerprint(message + "\n" + stackTrace))
            .thenReturn(expectedFingerprint);
        when(repository.findByFingerprint(expectedFingerprint)).thenReturn(null);

        // When
        SubmitResult result = executor.submitForAnalysis(message, stackTrace, "user1", null);

        // Then
        assertTrue(result.isNew());
        verify(fingerprintService).generateFingerprint(message + "\n" + stackTrace);
        verify(repository).save(argThat(report ->
            report.getErrorFingerprint().equals(expectedFingerprint) &&
            report.getOccurrenceCount() == 1 &&
            report.getAnalysisStatus().equals("pending")
        ));
    }
}