package com.huawei.hisi.knowledgegraph.aggregation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AggregationCheckpointManager {

    private final Driver neo4jDriver;

    public void markSuccess(String projectPath, String stageName, String dataHash) {
        try (Session session = neo4jDriver.session()) {
            session.run(
                "MERGE (a:AggregationCheckpoint {checkpointId: $id})\n" +
                "SET a.projectPath = $path,\n" +
                "    a.stageName = $stage,\n" +
                "    a.status = 'SUCCESS',\n" +
                "    a.lastSuccessAt = $now,\n" +
                "    a.dataHash = $hash,\n" +
                "    a.errorMessage = null",
                Map.of("id", projectPath + ":" + stageName,
                    "path", projectPath, "stage", stageName,
                    "now", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    "hash", dataHash != null ? dataHash : ""));
        }
        log.info("[Aggregation] Stage={} SUCCESS, projectPath={}", stageName, projectPath);
    }

    public void markFailed(String projectPath, String stageName, String errorMessage) {
        String lastSuccess = getLastSuccessTimestamp(projectPath, stageName);
        try (Session session = neo4jDriver.session()) {
            session.run(
                "MERGE (a:AggregationCheckpoint {checkpointId: $id})\n" +
                "SET a.projectPath = $path,\n" +
                "    a.stageName = $stage,\n" +
                "    a.status = 'FAILED',\n" +
                "    a.errorMessage = $err,\n" +
                "    a.lastSuccessAt = $last",
                Map.of("id", projectPath + ":" + stageName,
                    "path", projectPath, "stage", stageName,
                    "err", errorMessage != null ? errorMessage : "unknown",
                    "last", lastSuccess != null ? lastSuccess : ""));
        }
        log.warn("[Aggregation] Stage={} FAILED: {}", stageName, errorMessage);
    }

    public String getLastSuccessTimestamp(String projectPath, String stageName) {
        try (Session session = neo4jDriver.session()) {
            var r = session.run(
                "MATCH (a:AggregationCheckpoint {projectPath: $path, stageName: $stage})\n" +
                "WHERE a.status = 'SUCCESS'\n" +
                "RETURN a.lastSuccessAt AS ts",
                Map.of("path", projectPath, "stage", stageName));
            return r.hasNext() ? r.next().get("ts").asString(null) : null;
        }
    }

    public boolean isStageSuccessful(String projectPath, String stageName) {
        try (Session session = neo4jDriver.session()) {
            var r = session.run(
                "MATCH (a:AggregationCheckpoint {projectPath: $path, stageName: $stage})\n" +
                "RETURN a.status AS status",
                Map.of("path", projectPath, "stage", stageName));
            return r.hasNext() && "SUCCESS".equals(r.next().get("status").asString(null));
        }
    }

    public Optional<String> getCheckpointDataHash(String projectPath, String stageName) {
        try (Session session = neo4jDriver.session()) {
            var r = session.run(
                "MATCH (a:AggregationCheckpoint {projectPath: $path, stageName: $stage})\n" +
                "RETURN a.dataHash AS hash, a.status AS status",
                Map.of("path", projectPath, "stage", stageName));
            if (r.hasNext()) {
                var rec = r.next();
                return Optional.ofNullable(rec.get("hash").asString(null));
            }
            return Optional.empty();
        }
    }
}
