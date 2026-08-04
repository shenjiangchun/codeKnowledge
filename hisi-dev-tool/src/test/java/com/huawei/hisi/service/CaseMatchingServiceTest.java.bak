package com.huawei.hisi.service;

import com.huawei.hisi.model.DiagnosisCase;
import com.huawei.hisi.service.impl.CaseMatchingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * CaseMatchingService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CaseMatchingService 单元测试")
class CaseMatchingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private CaseMatchingServiceImpl caseMatchingService;

    private DiagnosisCase testCase;

    @BeforeEach
    void setUp() {
        testCase = DiagnosisCase.builder()
                .id("test-case-001")
                .projectPath("/test/project")
                .errorType("NullPointerException")
                .errorMessage("Cannot invoke method on null object")
                .stackTraceSummary("at com.example.Test.test(Test.java:10)")
                .rootCauseAnalysis("Object was not initialized")
                .solutionDescription("Add null check before method call")
                .verificationStatus(DiagnosisCase.VerificationStatus.PENDING)
                .usageCount(0)
                .build();
    }

    @Test
    @DisplayName("测试保存诊断案例 - 成功")
    void testSaveCase_Success() {
        lenient().when(jdbcTemplate.update(anyString(), (Object) any())).thenReturn(1);

        DiagnosisCase result = caseMatchingService.saveCase(testCase);

        assertNotNull(result);
        assertEquals(testCase.getErrorType(), result.getErrorType());
    }

    @Test
    @DisplayName("测试根据ID查找案例 - 找到")
    void testFindById_Found() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of(testCase));

        Optional<DiagnosisCase> result = caseMatchingService.findById("test-case-001");

        assertTrue(result.isPresent());
        assertEquals("NullPointerException", result.get().getErrorType());
    }

    @Test
    @DisplayName("测试根据ID查找案例 - 未找到")
    void testFindById_NotFound() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of());

        Optional<DiagnosisCase> result = caseMatchingService.findById("non-existent");

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("测试根据错误类型查找案例")
    void testFindByErrorType() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of(testCase));

        List<DiagnosisCase> results = caseMatchingService.findByErrorType("NullPointerException");

        assertFalse(results.isEmpty());
        assertEquals(1, results.size());
    }

    @Test
    @DisplayName("测试匹配相似案例")
    void testMatchSimilarCases() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString(), anyInt()))
                .thenReturn(List.of(testCase));

        CaseMatchingService.DiagnosisContext context = new CaseMatchingService.DiagnosisContext();
        context.setErrorType("NullPointerException");
        context.setErrorMessage("Cannot invoke method");

        List<DiagnosisCase> results = caseMatchingService.matchSimilarCases(context, 5);

        assertNotNull(results);
    }

    @Test
    @DisplayName("测试验证案例")
    void testVerifyCase() {
        when(jdbcTemplate.update(anyString(), (Object) any(), (Object) any(), anyString())).thenReturn(1);

        assertDoesNotThrow(() -> caseMatchingService.verifyCase("test-case-001", "valid"));

        verify(jdbcTemplate, times(1)).update(anyString(), (Object) any(), (Object) any(), anyString());
    }

    @Test
    @DisplayName("测试增加使用次数")
    void testIncrementUsageCount() {
        when(jdbcTemplate.update(anyString(), any(), anyString())).thenReturn(1);

        assertDoesNotThrow(() -> caseMatchingService.incrementUsageCount("test-case-001"));
    }

    @Test
    @DisplayName("测试获取案例总数")
    void testGetCaseCount() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(10L);

        long count = caseMatchingService.getCaseCount();

        assertEquals(10L, count);
    }
}