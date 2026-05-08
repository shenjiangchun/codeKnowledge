package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.List;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PythonMqCallScanner Tests")
class PythonMqCallScannerTest {

    private static final String PROJECT_PATH = "/home/projects/myapp";

    private PythonMqCallScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new PythonMqCallScanner();
    }

    @Test
    @DisplayName("celery.send_task(\"order.process\") → celery MQ call")
    void scanModule_celerySendTask() {
        PyCall call = PyCall.builder()
                .calleeExpression("celery.send_task")
                .lineNumber(10)
                .enclosingFunction("dispatch_order")
                .firstStringArg("order.process")
                .build();
        PyModule module = PyModule.builder()
                .filePath("dispatch.py").modulePath("dispatch")
                .calls(List.of(call)).build();

        List<PythonMqCall> results = scanner.scanModule(module, PROJECT_PATH, "celery");

        assertThat(results).hasSize(1);
        PythonMqCall mq = results.get(0);
        assertThat(mq.getLibrary()).isEqualTo("celery");
        assertThat(mq.getTopic()).isEqualTo("order.process");
        assertThat(mq.getLanguage()).isEqualTo("python");
    }

    @Test
    @DisplayName("producer.send(\"my-topic\", value) → kafka")
    void scanModule_kafkaSend() {
        PyCall call = PyCall.builder()
                .calleeExpression("producer.send")
                .lineNumber(20)
                .enclosingFunction("publish_event")
                .firstStringArg("my-topic")
                .build();
        PyModule module = PyModule.builder()
                .filePath("events.py").modulePath("events")
                .calls(List.of(call)).build();

        List<PythonMqCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getLibrary()).isEqualTo("kafka");
        assertThat(results.get(0).getTopic()).isEqualTo("my-topic");
    }

    @Test
    @DisplayName("exchange.publish(message) → aio_pika")
    void scanModule_aioPikaPublish() {
        PyCall call = PyCall.builder()
                .calleeExpression("exchange.publish")
                .lineNumber(30)
                .enclosingFunction("send_notification")
                .firstStringArg("notifications")
                .build();
        PyModule module = PyModule.builder()
                .filePath("notify.py").modulePath("notify")
                .calls(List.of(call)).build();

        List<PythonMqCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getLibrary()).isEqualTo("aio_pika");
        assertThat(results.get(0).getTopic()).isEqualTo("notifications");
    }

    @Test
    @DisplayName("Non-MQ call → 0 results")
    void scanModule_nonMqCall() {
        PyCall call = PyCall.builder()
                .calleeExpression("requests.get")
                .lineNumber(5)
                .enclosingFunction("fetch_data")
                .firstStringArg("http://example.com")
                .build();
        PyModule module = PyModule.builder()
                .filePath("client.py").modulePath("client")
                .calls(List.of(call)).build();

        List<PythonMqCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Multiple MQ calls in one module")
    void scanModule_multipleMqCalls() {
        PyCall call1 = PyCall.builder()
                .calleeExpression("celery.send_task")
                .lineNumber(10).firstStringArg("task.a")
                .enclosingFunction("fn1").build();
        PyCall call2 = PyCall.builder()
                .calleeExpression("producer.send")
                .lineNumber(20).firstStringArg("topic-b")
                .enclosingFunction("fn2").build();
        PyCall call3 = PyCall.builder()
                .calleeExpression("exchange.publish")
                .lineNumber(30).firstStringArg("queue-c")
                .enclosingFunction("fn3").build();
        PyModule module = PyModule.builder()
                .filePath("multi.py").modulePath("multi")
                .calls(List.of(call1, call2, call3)).build();

        List<PythonMqCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).hasSize(3);
        assertThat(results).extracting(PythonMqCall::getLibrary)
                .containsExactly("celery", "kafka", "aio_pika");
    }

    @Test
    @DisplayName("producer.send without firstStringArg → not classified as kafka")
    void scanModule_sendWithoutStringArg() {
        PyCall call = PyCall.builder()
                .calleeExpression("producer.send")
                .lineNumber(10)
                .enclosingFunction("fn")
                .firstStringArg(null)
                .build();
        PyModule module = PyModule.builder()
                .filePath("events.py").modulePath("events")
                .calls(List.of(call)).build();

        List<PythonMqCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).isEmpty();
    }
}
