package com.huawei.hisi.service;

import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.repository.LogAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * ReportExportService 单元测试
 * 
 * 测试 RAM Session 导出为 Markdown 的功能
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportExportService 测试")
class ReportExportServiceTest {

    @Mock
    private LogAnalysisRepository logAnalysisRepository;

    @Mock
    private AgentSessionRepository sessionRepository;

    @Mock
    private AgentEventRepository eventRepository;

    @InjectMocks
    private ReportExportService reportExportService;

    private String testUuid;
    private long testBackendId;
    private AgentSession testSession;

    @BeforeEach
    void setUp() {
        testUuid = UUID.randomUUID().toString();
        testBackendId = 42L;
        
        testSession = AgentSession.builder()
                .id(testBackendId)
                .uuid(testUuid)
                .userId("test-user")
                .status(SessionStatus.DONE)
                .currentNode("tech_plan")
                .stepCount(5)
                .intent("实现用户登录功能")
                .projectPaths("[\"/project/login\"]")
                .sessionType(SessionType.DEMAND)
                .createdAt(System.currentTimeMillis() / 1000L)
                .updatedAt(System.currentTimeMillis() / 1000L)
                .build();
    }

    @Test
    @DisplayName("exportRamSessionAsMd - 正常导出包含基本信息")
    void exportRamSessionAsMd_shouldContainBasicInfo() {
        // Arrange
        when(sessionRepository.findByUuid(testUuid)).thenReturn(Optional.of(testSession));
        when(eventRepository.findBySessionId(testBackendId)).thenReturn(List.of());

        // Act
        String result = reportExportService.exportRamSessionAsMd(testUuid);

        // Assert
        assertThat(result).contains("# RAM 需求分析会话");
        assertThat(result).contains(testUuid);
        assertThat(result).contains("test-user");
        assertThat(result).contains("DONE");
        assertThat(result).contains("实现用户登录功能");
    }

    @Test
    @DisplayName("exportRamSessionAsMd - 包含事件历史")
    void exportRamSessionAsMd_shouldContainEventHistory() {
        // Arrange
        when(sessionRepository.findByUuid(testUuid)).thenReturn(Optional.of(testSession));
        
        AgentEvent event1 = AgentEvent.builder()
                .id(1L)
                .sessionId(testBackendId)
                .seq(1L)
                .type(EventType.USER_MSG)
                .payload("{\"text\":\"请帮我分析登录功能\"}")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        
        AgentEvent event2 = AgentEvent.builder()
                .id(2L)
                .sessionId(testBackendId)
                .seq(2L)
                .type(EventType.CHECKPOINT)
                .payload("{\"nodeName\":\"clarify\",\"output\":{\"questions\":[\"需要什么认证方式\"]}}")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        
        AgentEvent event3 = AgentEvent.builder()
                .id(3L)
                .sessionId(testBackendId)
                .seq(3L)
                .type(EventType.ASSISTANT_DELTA)
                .payload("{\"text\":\"分析完成\"}")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        
        when(eventRepository.findBySessionId(testBackendId)).thenReturn(List.of(event1, event2, event3));

        // Act
        String result = reportExportService.exportRamSessionAsMd(testUuid);

        // Assert
        assertThat(result).contains("## 事件历史");
        assertThat(result).contains("USER_MSG");
        assertThat(result).contains("CHECKPOINT");
        assertThat(result).contains("ASSISTANT_DELTA");
        assertThat(result).contains("clarify");
    }

    @Test
    @DisplayName("exportRamSessionAsMd - Session不存在时抛出异常")
    void exportRamSessionAsMd_shouldThrowWhenSessionNotFound() {
        // Arrange
        when(sessionRepository.findByUuid("non-existent-uuid")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> reportExportService.exportRamSessionAsMd("non-existent-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    @DisplayName("exportRamSessionAsMd - 包含澄清问答记录")
    void exportRamSessionAsMd_shouldContainClarifyRecords() {
        // Arrange
        when(sessionRepository.findByUuid(testUuid)).thenReturn(Optional.of(testSession));
        
        AgentEvent clarifyReq = AgentEvent.builder()
                .id(1L)
                .sessionId(testBackendId)
                .seq(1L)
                .type(EventType.CLARIFY_REQ)
                .payload("{\"questions\":[\"是否需要JWT认证?\",\"密码加密方式?\"]}")
                .clarifyRoundNo(1)
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        
        AgentEvent clarifyRes = AgentEvent.builder()
                .id(2L)
                .sessionId(testBackendId)
                .seq(2L)
                .type(EventType.CLARIFY_RES)
                .payload("{\"answers\":{\"q1\":\"需要JWT\",\"q2\":\"bcrypt加密\"}}")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        
        when(eventRepository.findBySessionId(testBackendId)).thenReturn(List.of(clarifyReq, clarifyRes));

        // Act
        String result = reportExportService.exportRamSessionAsMd(testUuid);

        // Assert
        assertThat(result).contains("CLARIFY_REQ");
        assertThat(result).contains("CLARIFY_RES");
    }

    @Test
    @DisplayName("exportRamSessionAsMd - 包含CHECKPOINT输出")
    void exportRamSessionAsMd_shouldContainCheckpointOutputs() {
        // Arrange
        when(sessionRepository.findByUuid(testUuid)).thenReturn(Optional.of(testSession));
        
        AgentEvent checkpoint = AgentEvent.builder()
                .id(1L)
                .sessionId(testBackendId)
                .seq(10L)
                .type(EventType.CHECKPOINT)
                .payload("{\"nodeName\":\"impact\",\"output\":{\"affectedFiles\":[\"LoginController.java\",\"AuthService.java\"]}}")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        
        when(eventRepository.findBySessionId(testBackendId)).thenReturn(List.of(checkpoint));

        // Act
        String result = reportExportService.exportRamSessionAsMd(testUuid);

        // Assert
        assertThat(result).contains("CHECKPOINT");
        assertThat(result).contains("impact");
        assertThat(result).contains("LoginController.java");
    }
    // ========== MergeAnalysis Export Tests ==========

    @Test
    @DisplayName("exportMergeReportAsMd - 正常导出包含基本信息")
    void exportMergeReportAsMd_shouldContainBasicInfo() {
        // Arrange
        AgentSession mergeSession = AgentSession.builder()
                .id(testBackendId)
                .uuid(testUuid)
                .userId("merge-analysis")
                .status(SessionStatus.DONE)
                .currentNode("test_scope")
                .stepCount(3)
                .intent("合入分析: feature/login -> main")
                .projectPaths("/project/hisi-dev-tool")
                .sourceBranch("feature/login")
                .targetBranch("main")
                .createdAt(System.currentTimeMillis() / 1000L)
                .updatedAt(System.currentTimeMillis() / 1000L)
                .build();
        
        when(sessionRepository.findByUuid(testUuid)).thenReturn(Optional.of(mergeSession));
        when(eventRepository.findBySessionId(testBackendId)).thenReturn(List.of());

        // Act
        String result = reportExportService.exportMergeReportAsMd(testUuid);

        // Assert
        assertThat(result).contains("# 合入分析报告");
        assertThat(result).contains(testUuid);
        assertThat(result).contains("/project/hisi-dev-tool");
        assertThat(result).contains("feature/login");
        assertThat(result).contains("main");
        assertThat(result).contains("DONE");
    }

    @Test
    @DisplayName("exportMergeReportAsMd - Session不存在时抛出异常")
    void exportMergeReportAsMd_shouldThrowWhenSessionNotFound() {
        // Arrange
        when(sessionRepository.findByUuid("non-existent-uuid")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> reportExportService.exportMergeReportAsMd("non-existent-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    @DisplayName("exportMergeReportAsMd - 包含分析阶段事件")
    void exportMergeReportAsMd_shouldContainAnalysisEvents() {
        // Arrange
        AgentSession mergeSession = AgentSession.builder()
                .id(testBackendId)
                .uuid(testUuid)
                .userId("merge-analysis")
                .status(SessionStatus.DONE)
                .currentNode("test_scope")
                .stepCount(3)
                .intent("合入分析: feature/login -> main")
                .projectPaths("/project/hisi-dev-tool")
                .sourceBranch("feature/login")
                .targetBranch("main")
                .createdAt(System.currentTimeMillis() / 1000L)
                .updatedAt(System.currentTimeMillis() / 1000L)
                .build();
        
        AgentEvent diffEvent = AgentEvent.builder()
                .id(1L)
                .sessionId(testBackendId)
                .seq(1L)
                .type(EventType.CHECKPOINT)
                .payload("{\"nodeName\":\"diff_extract\",\"output\":{\"totalFiles\":5,\"totalAdditions\":120,\"totalDeletions\":30}}")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        
        AgentEvent impactEvent = AgentEvent.builder()
                .id(2L)
                .sessionId(testBackendId)
                .seq(2L)
                .type(EventType.CHECKPOINT)
                .payload("{\"nodeName\":\"impact_analysis\",\"output\":{\"affectedEntryPoints\":3,\"riskLevel\":\"medium\"}}")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        
        AgentEvent testScopeEvent = AgentEvent.builder()
                .id(3L)
                .sessionId(testBackendId)
                .seq(3L)
                .type(EventType.CHECKPOINT)
                .payload("{\"nodeName\":\"test_scope\",\"output\":{\"testCaseCount\":8}}")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        
        when(sessionRepository.findByUuid(testUuid)).thenReturn(Optional.of(mergeSession));
        when(eventRepository.findBySessionId(testBackendId)).thenReturn(List.of(diffEvent, impactEvent, testScopeEvent));

        // Act
        String result = reportExportService.exportMergeReportAsMd(testUuid);

        // Assert
        assertThat(result).contains("## 分析过程");
        assertThat(result).contains("diff_extract");
        assertThat(result).contains("impact_analysis");
        assertThat(result).contains("test_scope");
        assertThat(result).contains("totalFiles");
        assertThat(result).contains("affectedEntryPoints");
    }

    // ========== LogAnalysis Export Tests ==========

    @Test
    @DisplayName("exportLogReportAsMd - 报告不存在时抛出异常")
    void exportLogReportAsMd_shouldThrowWhenReportNotFound() {
        // Arrange
        Long nonExistentId = 99999L;
        when(logAnalysisRepository.findById(nonExistentId)).thenReturn(null);

        // Act & Assert
        assertThatThrownBy(() -> reportExportService.exportLogReportAsMd(nonExistentId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("报告不存在");
    }

    @Test
    @DisplayName("exportLogReportsAsZip - 空结果返回空ZIP")
    void exportLogReportsAsZip_emptyResults_returnsEmptyZip() throws Exception {
        // Arrange
        LocalDateTime startTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime endTime = LocalDateTime.of(2024, 1, 2, 0, 0);
        when(logAnalysisRepository.findByCreatedAtBetween(startTime, endTime)).thenReturn(List.of());

        // Act
        byte[] result = reportExportService.exportLogReportsAsZip(startTime, endTime);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.length).isGreaterThan(0); // ZIP 结构本身有最小大小

        // 验证可以解析为 ZIP（没有文件条目）
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(result))) {
            java.util.zip.ZipEntry entry = zis.getNextEntry();
            assertThat(entry).isNull(); // 空 ZIP 没有任何条目
        }
    }
}
