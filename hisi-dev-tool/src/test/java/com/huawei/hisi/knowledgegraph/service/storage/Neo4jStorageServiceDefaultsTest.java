package com.huawei.hisi.knowledgegraph.service.storage;

import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Neo4jStorageServiceDefaultsTest {

  @Mock Neo4jMethodNodeRepository methodNodeRepository;
  @Mock Neo4jEntryPointNodeRepository entryPointRepository;
  @InjectMocks Neo4jStorageService service;

  @Test
  void saveMethodNodes_backfillsLanguageDefault_whenNull() {
    MethodNode m = MethodNode.builder()
        .nodeId("n1").projectPath("/work/svc-a").build();
    when(methodNodeRepository.saveAll(any(Iterable.class))).thenReturn(List.of(m));

    service.saveMethodNodes(List.of(m));

    assertThat(m.getLanguage()).isEqualTo("java");
  }

  @Test
  void saveMethodNodes_doesNotOverrideLanguage_whenAlreadySet() {
    MethodNode m = MethodNode.builder()
        .nodeId("n2").projectPath("/work/svc-a")
        .language("python").build();
    when(methodNodeRepository.saveAll(any(Iterable.class))).thenReturn(List.of(m));

    service.saveMethodNodes(List.of(m));

    assertThat(m.getLanguage()).isEqualTo("python");
  }

  @Test
  void saveMethodNodes_doesNotSetFramework() {
    MethodNode m = MethodNode.builder()
        .nodeId("n3").projectPath("/work/svc-a").build();
    when(methodNodeRepository.saveAll(any(Iterable.class))).thenReturn(List.of(m));

    service.saveMethodNodes(List.of(m));

    assertThat(m.getFramework()).isNull();
  }

  @Test
  void saveEntryPoints_backfillsLanguageDefault() {
    EntryPointNode e = EntryPointNode.builder()
        .entryId("e1").projectPath("/work/svc-a").build();
    when(entryPointRepository.saveAll(any(Iterable.class))).thenReturn(List.of(e));

    service.saveEntryPoints(List.of(e));

    assertThat(e.getLanguage()).isEqualTo("java");
  }

  @Test
  void saveEntryPoints_doesNotOverrideExistingLanguage() {
    EntryPointNode e = EntryPointNode.builder()
        .entryId("e2").projectPath("/work/svc-a")
        .language("python").build();
    when(entryPointRepository.saveAll(any(Iterable.class))).thenReturn(List.of(e));

    service.saveEntryPoints(List.of(e));

    assertThat(e.getLanguage()).isEqualTo("python");
  }
}
