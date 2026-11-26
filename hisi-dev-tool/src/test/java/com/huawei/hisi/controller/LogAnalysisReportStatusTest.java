package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.service.LogAnalysisExecutor;
import com.huawei.hisi.service.LogCloudService;
import com.huawei.hisi.service.ReportExportService;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 任务状态接口：通过 /analyze 创建的报告 analysisStatus 为空，接口不应因此报错。 */
class LogAnalysisReportStatusTest {

    @Test
    void statusOfReportWithoutAnalysisStatusIsReturned() {
        LogAnalysisRepository repository = mock(LogAnalysisRepository.class);
        LogAnalysisReportEntity report = new LogAnalysisReportEntity();
        report.setReportId(7L);
        report.setStatus("completed");
        report.setAnalysisStatus(null);
        report.setCreatedAt(LocalDateTime.now());
        when(repository.findById(7L)).thenReturn(report);
        LogAnalysisController controller = new LogAnalysisController(mock(LogCloudService.class),
                mock(SnowflakeIdGenerator.class), repository, mock(LogAnalysisExecutor.class),
                mock(ReportExportService.class));

        ApiResponse<Map<String, Object>> response = controller.getReportStatus(7L);

        assertTrue(response.isSuccess());
        assertEquals("completed", response.getData().get("status"));
        assertEquals("pending", response.getData().get("stage"));
    }
}
