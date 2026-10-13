package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类级职责识别单测：复数后缀（*Utils/*Constants 等）与既有后缀行为保持。
 */
class ClassLayerRoleDetectorTest {

    private final ClassLayerRoleDetector detector = new ClassLayerRoleDetector();

    @Test
    @DisplayName("复数类名后缀：*Utils → UTILITY(NAME)")
    void pluralClassNameSuffix() {
        var r = detector.detectWithSource(List.of(), "com.hisi.utils.CodeAnalysisUtils", "com.hisi.utils");
        assertThat(r.role()).isEqualTo("UTILITY");
        assertThat(r.source()).isEqualTo("NAME");
    }

    @Test
    @DisplayName("复数包名后缀：xx.utils → UTILITY(PACKAGE)")
    void pluralPackageNameSuffix() {
        var r = detector.detectWithSource(List.of(), "com.hisi.helper.StringHelperFacade", "com.hisi.utils");
        assertThat(r.role()).isEqualTo("UTILITY");
        assertThat(r.source()).isEqualTo("PACKAGE");
    }

    @Test
    @DisplayName("复数 Constants/DTOs 命中")
    void pluralConstantsAndDtos() {
        assertThat(detector.detect(List.of(), "com.hisi.GlobalConstants", "com.hisi").equals("UTILITY")).isTrue();
        assertThat(detector.detect(List.of(), "com.hisi.dto.UserDtos", "com.hisi.dto").equals("MODEL")).isTrue();
    }

    @Test
    @DisplayName("既有行为保持：注解优先 / 单数后缀 / 未知")
    void existingBehaviorPreserved() {
        assertThat(detector.detectWithSource(List.of("RestController"), "a.B", "p").role()).isEqualTo("CONTROLLER");
        assertThat(detector.detectWithSource(List.of(), "a.UserController", "p").role()).isEqualTo("CONTROLLER");
        assertThat(detector.detectWithSource(List.of(), "a.OrderService", "p").role()).isEqualTo("SERVICE");
        assertThat(detector.detectWithSource(List.of(), "a.UserMapper", "p").role()).isEqualTo("REPOSITORY");
        assertThat(detector.detectWithSource(List.of(), "a.UserDTO", "p").role()).isEqualTo("MODEL");
        assertThat(detector.detectWithSource(List.of(), "a.FooBar", "p.weird").role()).isEqualTo("UNKNOWN");
    }
}
