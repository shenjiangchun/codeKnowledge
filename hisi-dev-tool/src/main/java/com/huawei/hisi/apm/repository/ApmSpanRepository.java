package com.huawei.hisi.apm.repository;

import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
@Slf4j
public class ApmSpanRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final RowMapper<ApmSpanEntity> ROW_MAPPER = (rs, rowNum) -> {
        ApmSpanEntity entity = ApmSpanEntity.builder()
            .sessionId(rs.getString("session_id"))
            .traceId(rs.getString("trace_id"))
            .spanId(rs.getString("span_id"))
            .parentSpanId(rs.getString("parent_span_id"))
            .serviceName(rs.getString("service_name"))
            .operationName(rs.getString("operation_name"))
            .spanKind(rs.getString("span_kind"))
            .startTimeNs(rs.getLong("start_time_ns"))
            .endTimeNs(rs.getLong("end_time_ns"))
            .statusCode(rs.getString("status_code"))
            .statusMessage(rs.getString("status_message"))
            .kgNodeId(rs.getString("kg_node_id"))
            .kgMatchLevel(rs.getInt("kg_match_level"))
            .build();

        // Deserialize JSON text columns
        try {
            String attrs = rs.getString("attributes");
            if (attrs != null && !attrs.isEmpty()) {
                entity.setAttributes(OBJECT_MAPPER.readValue(attrs, Map.class));
            }
            String resourceAttrs = rs.getString("resource_attrs");
            if (resourceAttrs != null && !resourceAttrs.isEmpty()) {
                entity.setResourceAttributes(OBJECT_MAPPER.readValue(resourceAttrs, Map.class));
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize span attributes for span_id={}: {}", entity.getSpanId(), e.getMessage());
        }
        return entity;
    };

    /**
     * Batch insert spans in a single transaction for performance.
     */
    public void batchInsert(List<ApmSpanEntity> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO apm_span (session_id, trace_id, span_id, parent_span_id, service_name,
                operation_name, span_kind, start_time_ns, end_time_ns, status_code, status_message,
                attributes, resource_attrs, kg_node_id, kg_match_level)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.batchUpdate(sql, spans, spans.size(), (ps, span) -> {
            ps.setString(1, span.getSessionId());
            ps.setString(2, span.getTraceId());
            ps.setString(3, span.getSpanId());
            ps.setString(4, span.getParentSpanId());
            ps.setString(5, span.getServiceName());
            ps.setString(6, span.getOperationName());
            ps.setString(7, span.getSpanKind());
            ps.setLong(8, span.getStartTimeNs());
            ps.setLong(9, span.getEndTimeNs());
            ps.setString(10, span.getStatusCode());
            ps.setString(11, span.getStatusMessage());
            ps.setString(12, toJson(span.getAttributes()));
            ps.setString(13, toJson(span.getResourceAttributes()));
            ps.setString(14, span.getKgNodeId());
            ps.setInt(15, span.getKgMatchLevel());
        });
    }

    public List<ApmSpanEntity> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
            "SELECT * FROM apm_span WHERE session_id = ? ORDER BY start_time_ns ASC",
            ROW_MAPPER, sessionId
        );
    }

    public List<ApmSpanEntity> findByTraceId(String traceId) {
        return jdbcTemplate.query(
            "SELECT * FROM apm_span WHERE trace_id = ? ORDER BY start_time_ns ASC",
            ROW_MAPPER, traceId
        );
    }

    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update("DELETE FROM apm_span WHERE session_id = ?", sessionId);
    }

    public int deleteOlderThan(long epochSeconds) {
        return jdbcTemplate.update("DELETE FROM apm_span WHERE created_at < ?", epochSeconds);
    }

    public void updateKgMapping(String spanId, String sessionId, String kgNodeId, int matchLevel) {
        jdbcTemplate.update(
            "UPDATE apm_span SET kg_node_id = ?, kg_match_level = ? WHERE span_id = ? AND session_id = ?",
            kgNodeId, matchLevel, spanId, sessionId
        );
    }

    public List<String> findDistinctTraceIds(String sessionId) {
        return jdbcTemplate.queryForList(
            "SELECT trace_id FROM apm_span WHERE session_id = ? GROUP BY trace_id ORDER BY MIN(start_time_ns)",
            String.class, sessionId
        );
    }

    private static String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
