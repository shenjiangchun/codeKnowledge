package com.huawei.hisi.knowledgegraph.python.scanner;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable record describing a single Python message-queue outbound call site.
 *
 * <p>Covers Celery {@code send_task}, Kafka {@code producer.send}, and
 * aio_pika {@code exchange.publish} patterns.
 */
@Value
@Builder
public class PythonMqCall {
    String filePath;
    int lineNumber;
    String enclosingFunction;
    String library;
    String topic;
    String language;
    String framework;
    String projectPath;
}
