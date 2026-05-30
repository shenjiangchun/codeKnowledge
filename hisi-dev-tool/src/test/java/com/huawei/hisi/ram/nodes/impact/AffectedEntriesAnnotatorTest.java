package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AffectedEntriesAnnotator} — record structure and deep analysis fields.
 */
@ExtendWith(MockitoExtension.class)
class AffectedEntriesAnnotatorTest {

    @Mock
    RamClaudeJsonClient claude;
    @Mock
    KgMcpClient kg;

    @Test
    void annotatedEntry_holdsDeepAnalysisFields() {
        AffectedEntriesAnnotator.AnnotatedEntry entry =
            new AffectedEntriesAnnotator.AnnotatedEntry(
                "n1", "ReqController", "deliver", "CONTROLLER",
                "DIRECT", "直接相关",
                "协作交付HTTP端点", "deliver调用syncReqStatus",
                "原逻辑：状态不变；新逻辑：下游>上游则回卷",
                "deliver → RequireStatusService.syncReqStatus");
        assertThat(entry.businessFunction()).isEqualTo("协作交付HTTP端点");
        assertThat(entry.impactMechanism()).isEqualTo("deliver调用syncReqStatus");
        assertThat(entry.changeBehavior()).isEqualTo("原逻辑：状态不变；新逻辑：下游>上游则回卷");
        assertThat(entry.callPath()).isEqualTo("deliver → RequireStatusService.syncReqStatus");
    }

    @Test
    void shallow_factoryCreatesEntryWithEmptyDeepFields() {
        AffectedEntriesAnnotator.AnnotatedEntry entry =
            AffectedEntriesAnnotator.AnnotatedEntry.shallow(
                "n1", "ReqController", "deliver", "CONTROLLER",
                "INDIRECT", "AI不可用");
        assertThat(entry.nodeId()).isEqualTo("n1");
        assertThat(entry.className()).isEqualTo("ReqController");
        assertThat(entry.methodName()).isEqualTo("deliver");
        assertThat(entry.businessFunction()).isEmpty();
        assertThat(entry.impactMechanism()).isEmpty();
        assertThat(entry.changeBehavior()).isEmpty();
        assertThat(entry.callPath()).isEmpty();
    }

    @Test
    void annotate_returnsDeepAnalysisFields() {
        when(claude.isAvailable()).thenReturn(true);
        Map<String, Object> aiResponse = Map.of("analysis", List.of(
            Map.of("nodeId", "n1", "relevance", "DIRECT",
                   "reason", "直接相关",
                   "business_function", "协作交付端点",
                   "impact_mechanism", "deliver调用syncReqStatus",
                   "change_behavior", "交付后状态回卷")));
        when(claude.callJson(anyString(), anyString(), any(SendOptions.class))).thenReturn(aiResponse);

        AffectedEntriesAnnotator annotator = new AffectedEntriesAnnotator(claude, kg);
        List<Entry> upstream = List.of(new Entry("n1", "ReqController", "deliver", "CONTROLLER"));
        AffectedEntriesAnnotator.AnnotatedEntries result =
            annotator.annotate("需求状态回卷", upstream, "syncReqStatus", "/p");

        assertThat(result.direct()).hasSize(1);
        assertThat(result.direct().get(0).businessFunction()).isEqualTo("协作交付端点");
        assertThat(result.direct().get(0).impactMechanism()).isEqualTo("deliver调用syncReqStatus");
        assertThat(result.direct().get(0).changeBehavior()).isEqualTo("交付后状态回卷");
    }

    @Test
    void annotate_fallsBackGracefully_whenDeepFieldsMissing() {
        when(claude.isAvailable()).thenReturn(true);
        // AI response without deep analysis fields (backward compat)
        Map<String, Object> aiResponse = Map.of("analysis", List.of(
            Map.of("nodeId", "n1", "relevance", "DIRECT", "reason", "直接相关")));
        when(claude.callJson(anyString(), anyString(), any(SendOptions.class))).thenReturn(aiResponse);

        AffectedEntriesAnnotator annotator = new AffectedEntriesAnnotator(claude, kg);
        List<Entry> upstream = List.of(new Entry("n1", "ReqController", "deliver", "CONTROLLER"));
        AffectedEntriesAnnotator.AnnotatedEntries result =
            annotator.annotate("需求状态回卷", upstream, "syncReqStatus", "/p");

        assertThat(result.direct()).hasSize(1);
        assertThat(result.direct().get(0).businessFunction()).isEmpty();
        assertThat(result.direct().get(0).impactMechanism()).isEmpty();
        assertThat(result.direct().get(0).changeBehavior()).isEmpty();
    }
}
