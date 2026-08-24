package com.huawei.hisi.knowledgegraph.service.storage;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 测试全量-复用（REUSE）保存的分组逻辑：codeHash 命中复用、未命中重算、孤儿清理。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Neo4jStorageServiceReuseTest {

  @Mock Neo4jMethodNodeRepository methodNodeRepository;
  @InjectMocks Neo4jStorageService service;

  @Test
  void reuse_hitNodes_callMergeAllReuseHit_notMergeAll() {
    String hash = MethodNode.computeCodeHash("C", "m", "s", null, "body");
    MethodNode node = MethodNode.builder()
        .nodeId("n1").projectPath("/work/svc-a").codeHash(hash).build();
    when(methodNodeRepository.findCodeHashByProjectPath("/work/svc-a"))
        .thenReturn(List.of(new Neo4jMethodNodeRepository.CodeHashProjection("n1", hash)));

    service.saveMethodNodesForReuse(List.of(node), "/work/svc-a");

    verify(methodNodeRepository).mergeAllReuseHit(anyList());
    verify(methodNodeRepository, never()).mergeAll(anyList());
    verify(methodNodeRepository).deleteOrphansByProjectPathAndNotInNodeIds(
        anyString(), anyList());
  }

  @Test
  void reuse_missNodes_callMergeAll_notMergeAllReuseHit() {
    MethodNode node = MethodNode.builder()
        .nodeId("n2").projectPath("/work/svc-a").codeHash("newhash").build();
    when(methodNodeRepository.findCodeHashByProjectPath("/work/svc-a"))
        .thenReturn(List.of(new Neo4jMethodNodeRepository.CodeHashProjection("n2", "oldhash")));

    service.saveMethodNodesForReuse(List.of(node), "/work/svc-a");

    verify(methodNodeRepository).mergeAll(anyList());
    verify(methodNodeRepository, never()).mergeAllReuseHit(anyList());
    // 未命中必须显式清空向量，触发重算
    verify(methodNodeRepository).clearEmbeddingsByNodeIds(anyList());
  }

  @Test
  void reuse_emptyNodes_deletesAllHistoricalMethods() {
    when(methodNodeRepository.findCodeHashByProjectPath("/work/svc-a"))
        .thenReturn(List.of());

    service.saveMethodNodesForReuse(List.of(), "/work/svc-a");

    // 0 方法场景：全量删除历史 Method，而非差集删孤儿
    verify(methodNodeRepository).deleteByProjectPath("/work/svc-a");
    verify(methodNodeRepository, never()).deleteOrphansByProjectPathAndNotInNodeIds(anyString(), anyList());
  }

  @Test
  void reuse_legacyNodeWithoutCodeHash_isMiss() {
    MethodNode node = MethodNode.builder()
        .nodeId("n3").projectPath("/work/svc-a").codeHash("newhash").build();
    // 历史节点无 codeHash（null）→ 视为未命中
    when(methodNodeRepository.findCodeHashByProjectPath("/work/svc-a"))
        .thenReturn(List.of(new Neo4jMethodNodeRepository.CodeHashProjection("n3", null)));

    service.saveMethodNodesForReuse(List.of(node), "/work/svc-a");

    verify(methodNodeRepository).mergeAll(anyList());
    verify(methodNodeRepository, never()).mergeAllReuseHit(anyList());
  }

  @Test
  void reuse_mixedHitAndMiss_routesBoth() {
    String hitHash = MethodNode.computeCodeHash("A", "m", "s", null, "b");
    MethodNode hit = MethodNode.builder()
        .nodeId("hit").projectPath("/work/svc-a").codeHash(hitHash).build();
    MethodNode miss = MethodNode.builder()
        .nodeId("miss").projectPath("/work/svc-a").codeHash("different").build();
    when(methodNodeRepository.findCodeHashByProjectPath("/work/svc-a"))
        .thenReturn(List.of(
            new Neo4jMethodNodeRepository.CodeHashProjection("hit", hitHash),
            new Neo4jMethodNodeRepository.CodeHashProjection("miss", "old")));

    service.saveMethodNodesForReuse(List.of(hit, miss), "/work/svc-a");

    verify(methodNodeRepository).mergeAllReuseHit(anyList());
    verify(methodNodeRepository).mergeAll(anyList());
  }
}
