package com.huawei.hisi.archdiagnosis.domain;

import com.huawei.hisi.knowledgegraph.aggregation.llm.DeepseekJsonClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 垂直领域命名单测：缓存命中跳过 LLM；LLM 成功命名并写缓存；LLM 失败回退领域词原文。
 */
@ExtendWith(MockitoExtension.class)
class VerticalDomainNamerTest {

    @Mock
    private DeepseekJsonClient deepseekJsonClient;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private VerticalDomainNamer namer;

    @BeforeEach
    void setUp() {
        namer = new VerticalDomainNamer(deepseekJsonClient, jdbcTemplate);
    }

    private static VerticalDomain domain(String word, String... roles) {
        List<ClassRoleRecord> members = List.of(
            new ClassRoleRecord(word + "Controller", "CONTROLLER"));
        return new VerticalDomain(word, members, Set.of(roles), true);
    }

    @Test
    @DisplayName("LLM 失败（返回 null）回退领域词原文")
    void llmFailureFallsBackToDomainWord() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
            .thenReturn(List.of()); // 缓存未命中
        when(deepseekJsonClient.chatJson(any(), any(), any())).thenReturn(null);

        List<VerticalDomain> result = namer.nameDomains(List.of(domain("Order", "CONTROLLER", "SERVICE")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Order");
    }

    @Test
    @DisplayName("LLM 成功命名并写缓存")
    void llmSuccessAppliesNameAndStoresToCache() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
            .thenReturn(List.of()); // 缓存未命中
        VerticalDomainNamer.DomainNameGrouping grouping = new VerticalDomainNamer.DomainNameGrouping(
            List.of(new VerticalDomainNamer.DomainNameItem("Order", "订单域")));
        when(deepseekJsonClient.chatJson(any(), any(), any())).thenReturn(grouping);

        List<VerticalDomain> result = namer.nameDomains(List.of(domain("Order", "CONTROLLER", "SERVICE")));

        assertThat(result.get(0).name()).isEqualTo("订单域");
        verify(jdbcTemplate).update(contains("INSERT OR REPLACE"), eq("Order"), eq("订单域"));
    }

    @Test
    @DisplayName("缓存命中直接复用，跳过 LLM")
    void cacheHitSkipsLlm() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
            .thenReturn(List.of("订单域")); // 缓存命中

        List<VerticalDomain> result = namer.nameDomains(List.of(domain("Order", "CONTROLLER", "SERVICE")));

        assertThat(result.get(0).name()).isEqualTo("订单域");
        verify(deepseekJsonClient, never()).chatJson(any(), any(), any());
    }

    @Test
    @DisplayName("清空缓存执行 DELETE")
    void clearCacheDeletesAll() {
        namer.clearCache();

        verify(jdbcTemplate).update(eq("DELETE FROM vertical_domain_name"));
    }
}
