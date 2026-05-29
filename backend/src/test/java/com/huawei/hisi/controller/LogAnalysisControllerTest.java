package com.huawei.hisi.controller;

import com.huawei.hisi.model.*;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.repository.LogAnalysisRepository.PaginatedReports;
import com.huawei.hisi.service.LogAnalysisExecutor;
import com.huawei.hisi.service.LogCloudService;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("LogAnalysisController 单元测试")
class LogAnalysisControllerTest {

    @Mock
    private LogCloudService logCloudService;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock
    private LogAnalysisRepository repository;
    @Mock
    private LogAnalysisExecutor logAnalysisExecutor;

    @InjectMocks
    private LogAnalysisController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("查询日志 - 正常返回")
    void queryLogs_shouldReturnLogs() {
        LogQueryDto query = new LogQueryDto();
        query.setAppId("test-app");

        List<LogEntry> logs = Arrays.asList(new LogEntry());
        when(logCloudService.queryLogs(query)).thenReturn(logs);

        ApiResponse<Object> response = controller.queryLogs(query);

        assertEquals(200, response.getCode());
        verify(logCloudService).queryLogs(query);
    }

    @Test
    @DisplayName("查询日志 - 异常应返回错误")
    void queryLogs_withException_shouldReturnError() {
        LogQueryDto query = new LogQueryDto();
        when(logCloudService.queryLogs(query)).thenThrow(new RuntimeException("查询失败"));

        ApiResponse<Object> response = controller.queryLogs(query);

        assertEquals(500, response.getCode());
        assertTrue(response.getMessage().contains("查询失败"));
    }

    @Test
    @DisplayName("提交分析任务 - 参数为空应返回错误")
    void analyze_withNullRequest_shouldReturnError() {
        ApiResponse<AnalyzeTaskResponse> response = controller.analyze(null);

        assertEquals(400, response.getCode());
        assertTrue(response.getMessage().contains("参数不能为空"));
    }

    @Test
    @DisplayName("提交分析任务 - message 和 stackTrace 都为空应返回错误")
    void analyze_withEmptyRequest_shouldReturnError() {
        LogAnalyzeRequest request = new LogAnalyzeRequest();

        ApiResponse<AnalyzeTaskResponse> response = controller.analyze(request);

        assertEquals(400, response.getCode());
        assertTrue(response.getMessage().contains("参数不能为空"));
    }

    @Test
    @DisplayName("提交分析任务 - 正常流程")
    void analyze_shouldCreateTask() {
        LogAnalyzeRequest request = new LogAnalyzeRequest();
        request.setMessage("NullPointerException");

        when(snowflakeIdGenerator.nextId()).thenReturn(123456L);
        doNothing().when(repository).save(any());
        doNothing().when(logAnalysisExecutor).executeAnalysis(anyLong());

        ApiResponse<AnalyzeTaskResponse> response = controller.analyze(request);

        assertEquals(200, response.getCode());
        assertNotNull(response.getData());
        assertEquals(123456L, response.getData().getReportId());
        assertEquals("pending", response.getData().getStatus());

        verify(repository).save(any(LogAnalysisReportEntity.class));
        verify(logAnalysisExecutor).executeAnalysis(123456L);
    }

    @Test
    @DisplayName("获取报告详情 - 报告不存在应返回 404")
    void getReport_notFound_shouldReturn404() {

        when(repository.findById(999L)).thenReturn(null);

        ApiResponse<DetailedAnalysisReport> response = controller.getReport(999L);

        assertEquals(404, response.getCode());
    }

    @Test
    @DisplayName("获取报告详情 - pending 状态应返回错误")
    void getReport_pendingStatus_shouldReturnError() {
        LogAnalysisReportEntity entity = new LogAnalysisReportEntity();
        entity.setReportId(1L);
        entity.setStatus("pending");


        when(repository.findById(1L)).thenReturn(entity);

        ApiResponse<DetailedAnalysisReport> response = controller.getReport(1L);

        assertEquals(400, response.getCode());
        assertTrue(response.getMessage().contains("尚未完成"));
    }

    @Test
    @DisplayName("获取报告详情 - completed 状态应返回详情")
    void getReport_completedStatus_shouldReturnDetails() {
        LogAnalysisReportEntity entity = new LogAnalysisReportEntity();
        entity.setReportId(1L);
        entity.setStatus("completed");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());


        when(repository.findById(1L)).thenReturn(entity);

        ApiResponse<DetailedAnalysisReport> response = controller.getReport(1L);

        assertEquals(200, response.getCode());
        assertEquals(1L, response.getData().getReportId());
        assertEquals("completed", response.getData().getStatus());
    }

    @Test
    @DisplayName("获取任务状态 - 正常返回")
    void getReportStatus_shouldReturnStatus() {
        LogAnalysisReportEntity entity = new LogAnalysisReportEntity();
        entity.setReportId(1L);
        entity.setStatus("completed");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());


        when(repository.findById(1L)).thenReturn(entity);

        ApiResponse<Map<String, Object>> response = controller.getReportStatus(1L);

        assertEquals(200, response.getCode());
        assertEquals(1L, response.getData().get("reportId"));
        assertEquals("completed", response.getData().get("status"));
    }

    @Test
    @DisplayName("获取任务列表 - 分页查询")
    void getReports_withPagination_shouldReturnList() {
        List<LogAnalysisReportEntity> reports = new ArrayList<>();
        LogAnalysisReportEntity entity = new LogAnalysisReportEntity();
        entity.setReportId(1L);
        entity.setStatus("completed");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        reports.add(entity);

        PaginatedReports paginated = new PaginatedReports(1, reports);

        when(repository.findByUserIdPagination("sys_admin", 1, 10)).thenReturn(paginated);

        ApiResponse<ReportListResponse> response = controller.getReports(null, null, 1, 10);

        assertEquals(200, response.getCode());
        assertEquals(1, response.getData().getTotal());
        assertEquals(1, response.getData().getList().size());
    }

    @Test
    @DisplayName("获取任务列表 - 按状态过滤")
    void getReports_withStatusFilter_shouldReturnList() {
        List<LogAnalysisReportEntity> reports = new ArrayList<>();
        LogAnalysisReportEntity entity = new LogAnalysisReportEntity();
        entity.setReportId(1L);
        entity.setStatus("completed");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        reports.add(entity);

        when(repository.findByUserIdAndStatus("sys_admin", "completed")).thenReturn(reports);

        ApiResponse<ReportListResponse> response = controller.getReports(null, "completed", 1, 10);

        assertEquals(200, response.getCode());
        assertEquals(1, response.getData().getTotal());
        verify(repository).findByUserIdAndStatus("sys_admin", "completed");
    }
}