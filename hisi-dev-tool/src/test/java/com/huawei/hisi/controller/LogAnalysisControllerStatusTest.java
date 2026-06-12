package com.huawei.hisi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.service.LogAnalysisExecutor;
import com.huawei.hisi.service.LogCloudService;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * LogAnalysisController 单元测试 - 扩展状态API
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogAnalysisControllerStatusTest {

    @Mock
    private LogCloudService logCloudService;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private LogAnalysisRepository repository;

    @Mock
    private LogAnalysisExecutor logAnalysisExecutor;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        LogAnalysisController controller = new LogAnalysisController(
            logCloudService, snowflakeIdGenerator, repository, logAnalysisExecutor
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("获取报告状态 - 包含进度信息")
    void testGetReportStatusWithProgress() throws Exception {
        // Given
        Long reportId = 12345L;
        LogAnalysisReportEntity report = new LogAnalysisReportEntity();
        report.setReportId(reportId);
        report.setStatus("processing");
        report.setAnalysisStatus("parsing");
        report.setOccurrenceCount(1);
        report.setCreatedAt(LocalDateTime.now());
        report.setUpdatedAt(LocalDateTime.now());

        when(repository.findById(reportId)).thenReturn(report);
        when(repository.countPendingBefore(any(LocalDateTime.class))).thenReturn(5);

        // When & Then
        mockMvc.perform(get("/api/log/report/{id}/status", reportId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reportId").value(12345))
            .andExpect(jsonPath("$.data.status").value("processing"))
            .andExpect(jsonPath("$.data.progress").value(25))
            .andExpect(jsonPath("$.data.stage").value("parsing"))
            .andExpect(jsonPath("$.data.etaSeconds").exists())
            .andExpect(jsonPath("$.data.queuePosition").exists())
            .andExpect(jsonPath("$.data.occurrenceCount").value(1));
    }

    @Test
    @DisplayName("获取报告状态 - 完成状态进度100")
    void testGetReportStatusCompleted() throws Exception {
        // Given
        Long reportId = 12345L;
        LogAnalysisReportEntity report = new LogAnalysisReportEntity();
        report.setReportId(reportId);
        report.setStatus("completed");
        report.setAnalysisStatus("completed");
        report.setCreatedAt(LocalDateTime.now());

        when(repository.findById(reportId)).thenReturn(report);

        // When & Then
        mockMvc.perform(get("/api/log/report/{id}/status", reportId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.progress").value(100))
            .andExpect(jsonPath("$.data.etaSeconds").value(0));
    }

    @Test
    @DisplayName("获取报告状态 - 报告不存在")
    void testGetReportStatusNotFound() throws Exception {
        // Given
        when(repository.findById(999L)).thenReturn(null);

        // When & Then
        mockMvc.perform(get("/api/log/report/{id}/status", 999))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false));
    }
}