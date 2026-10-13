package com.huawei.hisi.archdiagnosis.domain;

import com.huawei.hisi.knowledgegraph.aggregation.llm.DeepseekJsonClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 垂直领域命名（LLM 消歧 + SQLite 缓存）。
 *
 * <p>只做「命名」——复合词拆分、缩写还原、中文名，不改变领域边界（不合并、不拆分）。
 * 命名结果按领域词缓存到 SQLite，复用 {@link DeepseekJsonClient}（json_object 强制）；
 * LLM 失败回退领域词原文。「实时生成/刷新」时清空缓存重算。</p>
 */
@Slf4j
@Service
public class VerticalDomainNamer {

    private final DeepseekJsonClient deepseekJsonClient;
    private final JdbcTemplate jdbcTemplate;

    public VerticalDomainNamer(DeepseekJsonClient deepseekJsonClient, JdbcTemplate jdbcTemplate) {
        this.deepseekJsonClient = deepseekJsonClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    static final String SYSTEM_PROMPT = """
        你是 Java 项目的领域命名专家。给定一个垂直领域的领域词（从类名剥离技术后缀得到，如 Order、Payment、UserAuth），
        为它给出简洁准确的中文领域名。约束：
        - 只命名，不改变领域边界、不合并、不拆分领域
        - 复合词拆分还原：如 OrderPayment → 「订单支付域」、UserAuth → 「用户认证域」
        - 常见缩写还原：如 Acct → 账户、Ord → 订单、Cfg/Config → 配置、Mgmt → 管理
        - 中文名简短（2~6 字），以「域」字结尾（如「订单域」）
        - 确实无法判断时，name 原样回显领域词原文
        """;

    /** LLM 结构化输出目标。 */
    public record DomainNameItem(String word, String name) {}
    public record DomainNameGrouping(List<DomainNameItem> items) {}

    /** 命名领域：缓存优先，miss 走 LLM 批量命名，失败回退领域词原文。 */
    public List<VerticalDomain> nameDomains(List<VerticalDomain> domains) {
        if (domains == null || domains.isEmpty()) {
            return domains == null ? List.of() : domains;
        }
        Map<String, String> names = new HashMap<>();
        List<VerticalDomain> misses = new ArrayList<>();
        for (VerticalDomain domain : domains) {
            String cached = lookup(domain.domainWord());
            if (cached != null) {
                names.put(domain.domainWord(), cached);
            } else {
                misses.add(domain);
            }
        }
        names.putAll(resolveNames(misses));

        List<VerticalDomain> result = new ArrayList<>();
        for (VerticalDomain domain : domains) {
            result.add(domain.withName(names.getOrDefault(domain.domainWord(), domain.domainWord())));
        }
        return result;
    }

    /** 清空命名缓存（「实时生成/刷新」时重算）。 */
    public void clearCache() {
        try {
            jdbcTemplate.update("DELETE FROM vertical_domain_name");
        } catch (Exception e) {
            log.warn("[VerticalDomainNamer] 清缓存失败: {}", e.getMessage());
        }
    }

    /** 批量 LLM 命名未命中领域；返回 word → name（LLM 未覆盖者由调用方回退原文）。 */
    private Map<String, String> resolveNames(List<VerticalDomain> domains) {
        if (domains.isEmpty()) {
            return Map.of();
        }
        DomainNameGrouping grouping =
            deepseekJsonClient.chatJson(SYSTEM_PROMPT, buildPrompt(domains), DomainNameGrouping.class);
        Map<String, String> result = new HashMap<>();
        if (grouping != null && grouping.items() != null) {
            for (DomainNameItem item : grouping.items()) {
                if (item.word() == null || item.word().isBlank()) {
                    continue;
                }
                String name = item.name() == null || item.name().isBlank() ? item.word() : item.name();
                result.put(item.word(), name);
                store(item.word(), name);
            }
        }
        return result;
    }

    private String buildPrompt(List<VerticalDomain> domains) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下垂直领域词命名（中文领域名）：\n");
        for (VerticalDomain domain : domains) {
            sb.append("- 领域词: ").append(domain.domainWord())
              .append("（分层角色: ").append(String.join("/", domain.roles())).append("）\n");
        }
        sb.append("\n只输出一个 JSON 对象，格式：{\"items\":[{\"word\":\"领域词\",\"name\":\"中文名\"}]}，")
          .append("覆盖输入的全部领域词，不要遗漏、不要重复、不要 markdown、不要解释。");
        return sb.toString();
    }

    private String lookup(String word) {
        try {
            List<String> names = jdbcTemplate.query(
                "SELECT name FROM vertical_domain_name WHERE domain_word = ?",
                (RowMapper<String>) (rs, rowNum) -> rs.getString(1), word);
            return names.isEmpty() ? null : names.get(0);
        } catch (Exception e) {
            log.warn("[VerticalDomainNamer] 读缓存失败 {}: {}", word, e.getMessage());
            return null;
        }
    }

    private void store(String word, String name) {
        try {
            jdbcTemplate.update(
                "INSERT OR REPLACE INTO vertical_domain_name (domain_word, name) VALUES (?, ?)",
                word, name);
        } catch (Exception e) {
            log.warn("[VerticalDomainNamer] 写缓存失败 {}: {}", word, e.getMessage());
        }
    }
}
