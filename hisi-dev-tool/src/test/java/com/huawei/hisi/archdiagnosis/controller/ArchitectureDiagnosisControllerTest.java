package com.huawei.hisi.archdiagnosis.controller;

import com.huawei.hisi.archdiagnosis.domain.VerticalDomainNamer;
import com.huawei.hisi.archdiagnosis.model.ArchDiagnosisSnapshot;
import com.huawei.hisi.archdiagnosis.repository.ArchDiagnosisSnapshotRepository;
import com.huawei.hisi.archdiagnosis.service.ArchDiagnosisReportService;
import com.huawei.hisi.archdiagnosis.service.ArchReportMarkdownExporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告端点 persist 语义单测：显式 persist=false 不落快照，缺省等价 persist=true。
 */
@ExtendWith(MockitoExtension.class)
class ArchitectureDiagnosisControllerTest {

    @Mock
    private ArchDiagnosisReportService reportService;
    @Mock
    private ArchDiagnosisSnapshotRepository snapshotRepository;
    @Mock
    private ArchReportMarkdownExporter markdownExporter;
    @Mock
    private VerticalDomainNamer verticalDomainNamer;

    private ArchitectureDiagnosisController controller() {
        return new ArchitectureDiagnosisController(
                reportService, snapshotRepository, markdownExporter, verticalDomainNamer, new ObjectMapper());
    }

    @Test
    @DisplayName("persist=false 透传到 generate，不落快照，且刷新命名缓存")
    void getReport_persistFalse() {
        when(reportService.generate(anyList(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Map.of("healthScore", 90));
        var controller = controller();

        controller.getReport(List.of("p1"), "java", false);

        ArgumentCaptor<Boolean> persist = ArgumentCaptor.forClass(Boolean.class);
        verify(reportService).generate(anyList(), anyString(), persist.capture());
        assertThat(persist.getValue()).isFalse();
        verify(snapshotRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any(ArchDiagnosisSnapshot.class));
        verify(verticalDomainNamer).clearCache();
    }

    @Test
    @DisplayName("缺省 persist 等价 persist=true（向后兼容）")
    void getReport_defaultPersistTrue() {
        when(reportService.generate(anyList(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Map.of("healthScore", 90));
        var controller = controller();

        controller.getReport(List.of("p1"), "java", true);

        ArgumentCaptor<Boolean> persist = ArgumentCaptor.forClass(Boolean.class);
        verify(reportService).generate(anyList(), anyString(), persist.capture());
        assertThat(persist.getValue()).isTrue();
    }

    @Test
    @DisplayName("快照 scope 归一化：反斜杠 scope 转正斜杠后再查，与落库时的归一化 scope 对齐")
    void getSnapshots_normalizesScope() {
        when(snapshotRepository.listTrendByScope(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        var controller = controller();

        controller.getSnapshots("C:\\projects\\demo", null, 20);

        verify(snapshotRepository).listTrendByScope("C:/projects/demo", 20);
    }

    @Test
    @DisplayName("快照报告体端点：findById 命中返回完整报告体")
    void getSnapshotReport_returnsFullBody() {
        ArchDiagnosisSnapshot snapshot = ArchDiagnosisSnapshot.builder()
                .id(1L).scope("p1").reportJson("{\"healthScore\": 88}").build();
        when(snapshotRepository.findById(1L)).thenReturn(snapshot);
        var controller = controller();

        var resp = controller.getSnapshotReport(1L);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData()).containsEntry("healthScore", 88);
    }

    @Test
    @DisplayName("快照报告体端点：findById 未命中返回 404")
    void getSnapshotReport_notFound() {
        when(snapshotRepository.findById(99L)).thenReturn(null);
        var controller = controller();

        var resp = controller.getSnapshotReport(99L);

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getCode()).isEqualTo(404);
    }
}
