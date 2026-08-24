package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LayeredRuleEngine 分层违规检测")
class LayeredRuleEngineTest {

    private final LayeredRuleEngine engine = new LayeredRuleEngine();

    private Map<String, String> layers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("service 反向依赖 controller → 违规")
    void serviceDependsController_violation() {
        Map<String, String> modules = layers(
            "com.foo.controller", "CONTROLLER",
            "com.foo.service", "SERVICE",
            "com.foo.repository", "REPOSITORY",
            "com.foo.model", "MODEL",
            "com.foo.util", "UTILITY");
        List<String[]> depends = List.<String[]>of(new String[]{"com.foo.service", "com.foo.controller"});

        var violations = engine.detect(modules, depends);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).reason()).contains("反向依赖");
    }

    @Test
    @DisplayName("controller 跨层依赖 repository → 不违规（宽松分层，允许跳层）")
    void controllerSkipsService_noViolation() {
        Map<String, String> modules = layers(
            "com.foo.controller", "CONTROLLER",
            "com.foo.service", "SERVICE",
            "com.foo.repository", "REPOSITORY");
        List<String[]> depends = List.<String[]>of(new String[]{"com.foo.controller", "com.foo.repository"});

        var violations = engine.detect(modules, depends);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("controller → service → repository 正常分层 → 无违规")
    void normalLayering_noViolation() {
        Map<String, String> modules = layers(
            "com.foo.controller", "CONTROLLER",
            "com.foo.service", "SERVICE",
            "com.foo.repository", "REPOSITORY");
        List<String[]> depends = List.of(
            new String[]{"com.foo.controller", "com.foo.service"},
            new String[]{"com.foo.service", "com.foo.repository"}
        );

        var violations = engine.detect(modules, depends);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("任意层依赖 util → 不违规（叶子层）")
    void dependsOnUtil_noViolation() {
        Map<String, String> modules = layers(
            "com.foo.controller", "CONTROLLER",
            "com.foo.util", "UTILITY");
        List<String[]> depends = List.<String[]>of(new String[]{"com.foo.controller", "com.foo.util"});

        var violations = engine.detect(modules, depends);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("非 Spring 项目（已知层占比 <30%）→ 跳过检测")
    void nonSpringProject_skips() {
        // 6 个模块只有 1 个已知层，占比 16.7% < 30%
        Map<String, String> modules = layers(
            "com.foo.a", "UNKNOWN",
            "com.foo.b", "UNKNOWN",
            "com.foo.c", "UNKNOWN",
            "com.foo.d", "UNKNOWN",
            "com.foo.e", "UNKNOWN",
            "com.foo.controller", "CONTROLLER");
        List<String[]> depends = List.<String[]>of(new String[]{"com.foo.controller", "com.foo.a"});

        var violations = engine.detect(modules, depends);

        assertThat(violations).isEmpty();
    }
}
