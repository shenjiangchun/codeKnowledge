package com.huawei.hisi.ram.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InputsHasher unit tests")
class InputsHasherTest {

    @Test
    @DisplayName("hash is stable for equal payloads and changes when input changes")
    void inputsHash_isStable_andChangesWithInput() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 2);
        a.put("a", 1);

        Map<String, Object> b = new TreeMap<>();
        b.put("a", 1);
        b.put("b", 2);

        String hashA = InputsHasher.hash(a);
        String hashB = InputsHasher.hash(b);
        assertThat(hashA).isEqualTo(hashB);
        assertThat(hashA).hasSize(64);

        Map<String, Object> c = new LinkedHashMap<>(a);
        c.put("a", 999);
        assertThat(InputsHasher.hash(c)).isNotEqualTo(hashA);
    }

    @Test
    @DisplayName("hash handles nested Maps and Lists deterministically")
    void inputsHash_handlesNestedStructures() {
        Map<String, Object> nestedA = new LinkedHashMap<>();
        Map<String, Object> innerA = new LinkedHashMap<>();
        innerA.put("y", 2);
        innerA.put("x", 1);
        nestedA.put("inner", innerA);
        nestedA.put("list", List.of("a", "b", "c"));

        Map<String, Object> nestedB = new LinkedHashMap<>();
        nestedB.put("list", List.of("a", "b", "c"));
        Map<String, Object> innerB = new LinkedHashMap<>();
        innerB.put("x", 1);
        innerB.put("y", 2);
        nestedB.put("inner", innerB);

        assertThat(InputsHasher.hash(nestedA)).isEqualTo(InputsHasher.hash(nestedB));

        Map<String, Object> different = new LinkedHashMap<>(nestedA);
        different.put("list", List.of("c", "b", "a"));
        assertThat(InputsHasher.hash(different)).isNotEqualTo(InputsHasher.hash(nestedA));
    }
}
