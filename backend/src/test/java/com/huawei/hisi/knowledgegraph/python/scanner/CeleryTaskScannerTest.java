package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.List;

import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CeleryTaskScanner Tests")
class CeleryTaskScannerTest {

    private static final String PROJECT_PATH = "/home/projects/myapp";

    private CeleryTaskScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new CeleryTaskScanner();
    }

    @Test
    @DisplayName("@celery.task on top-level function → 1 entry")
    void scanModule_celeryTaskBare() {
        PyFunction fn = PyFunction.builder()
                .name("send_email")
                .qualName("tasks.send_email")
                .decorators(List.of("celery.task"))
                .lineStart(10).lineEnd(15).build();
        PyModule module = PyModule.builder()
                .filePath("tasks.py").modulePath("tasks")
                .topLevelFunctions(List.of(fn)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        EntryPointNode e = entries.get(0);
        assertThat(e.getEntryKey()).isEqualTo("tasks.send_email");
        assertThat(e.getEntryType()).isEqualTo(EntryPointNode.TYPE_MQ_CONSUMER);
        assertThat(e.getLanguage()).isEqualTo("python");
        assertThat(e.getFramework()).isEqualTo("celery");
        assertThat(e.getEntryInfo()).contains("\"subType\":\"CELERY_TASK\"");
    }

    @Test
    @DisplayName("@shared_task → 1 entry")
    void scanModule_sharedTask() {
        PyFunction fn = PyFunction.builder()
                .name("process")
                .qualName("workers.process")
                .decorators(List.of("shared_task"))
                .lineStart(5).lineEnd(8).build();
        PyModule module = PyModule.builder()
                .filePath("workers.py").modulePath("workers")
                .topLevelFunctions(List.of(fn)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryKey()).isEqualTo("workers.process");
    }

    @Test
    @DisplayName("@app.task(name=\"custom.name\") → entryKey = custom.name")
    void scanModule_appTaskWithName() {
        PyFunction fn = PyFunction.builder()
                .name("do_work")
                .qualName("tasks.do_work")
                .decorators(List.of("app.task(name=\"custom.name\")"))
                .lineStart(20).lineEnd(25).build();
        PyModule module = PyModule.builder()
                .filePath("tasks.py").modulePath("tasks")
                .topLevelFunctions(List.of(fn)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryKey()).isEqualTo("custom.name");
    }

    @Test
    @DisplayName("Non-celery decorator → 0 entries")
    void scanModule_nonCeleryDecorator() {
        PyFunction fn = PyFunction.builder()
                .name("helper")
                .decorators(List.of("staticmethod", "lru_cache()"))
                .lineStart(5).lineEnd(10).build();
        PyModule module = PyModule.builder()
                .filePath("utils.py").modulePath("utils")
                .topLevelFunctions(List.of(fn)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("Multiple celery tasks in one module")
    void scanModule_multipleTasks() {
        PyFunction fn1 = PyFunction.builder()
                .name("t1").qualName("tasks.t1")
                .decorators(List.of("celery.task"))
                .lineStart(1).lineEnd(3).build();
        PyFunction fn2 = PyFunction.builder()
                .name("t2").qualName("tasks.t2")
                .decorators(List.of("shared_task"))
                .lineStart(5).lineEnd(8).build();
        PyFunction fn3 = PyFunction.builder()
                .name("t3").qualName("tasks.t3")
                .decorators(List.of("app.task(name=\"queue.t3\")"))
                .lineStart(10).lineEnd(15).build();
        PyModule module = PyModule.builder()
                .filePath("tasks.py").modulePath("tasks")
                .topLevelFunctions(List.of(fn1, fn2, fn3)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(EntryPointNode::getEntryKey)
                .containsExactlyInAnyOrder("tasks.t1", "tasks.t2", "queue.t3");
    }

    @Test
    @DisplayName("@celery.task() with empty parens")
    void scanModule_celeryTaskEmptyParens() {
        PyFunction fn = PyFunction.builder()
                .name("ping")
                .qualName("tasks.ping")
                .decorators(List.of("celery.task()"))
                .lineStart(1).lineEnd(3).build();
        PyModule module = PyModule.builder()
                .filePath("tasks.py").modulePath("tasks")
                .topLevelFunctions(List.of(fn)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryKey()).isEqualTo("tasks.ping");
    }
}
