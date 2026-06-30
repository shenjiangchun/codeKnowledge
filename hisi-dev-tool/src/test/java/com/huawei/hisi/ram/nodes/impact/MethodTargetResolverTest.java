package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.nodes.impact.MethodTargetResolver.MethodTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MethodTargetResolverTest {

    @Mock
    KgMcpClient kg;

    @Test
    void resolve_hashFormat_parsesAndResolves() {
        when(kg.calleesTree("OrderService", "createOrder", "/p", 0))
                .thenReturn(new CallTreeNode("n1", "OrderService", "createOrder", 0, List.of()));

        List<MethodTarget> targets = new MethodTargetResolver(kg)
                .resolve(List.of("OrderService#createOrder"), List.of(), "/p");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).nodeId()).isEqualTo("n1");
        assertThat(targets.get(0).className()).isEqualTo("OrderService");
        assertThat(targets.get(0).methodName()).isEqualTo("createOrder");
    }

    @Test
    void resolve_dotFormat_parsesFullyQualifiedName() {
        when(kg.calleesTree("com.hisilicon.rms.RequireStatusServiceImpl", "syncReqStatus", "/p", 0))
                .thenReturn(new CallTreeNode("n2", "com.hisilicon.rms.RequireStatusServiceImpl", "syncReqStatus", 0, List.of()));

        List<MethodTarget> targets = new MethodTargetResolver(kg)
                .resolve(List.of("com.hisilicon.rms.RequireStatusServiceImpl.syncReqStatus"), List.of(), "/p");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).className()).isEqualTo("com.hisilicon.rms.RequireStatusServiceImpl");
        assertThat(targets.get(0).methodName()).isEqualTo("syncReqStatus");
    }

    @Test
    void resolve_fallbackToSearch_whenTargetMethodsEmpty() {
        List<MethodTarget> targets = new MethodTargetResolver(kg)
                .resolve(List.of(), List.of(new Seed("s1", 0.9, "related")), "/p");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).nodeId()).isEqualTo("s1");
    }

    @Test
    void resolve_hybridSearchFallback_whenCalleesTreeReturnsNull() {
        when(kg.calleesTree("ShortClass", "someMethod", "/p", 0))
                .thenReturn(new CallTreeNode(null, "ShortClass", "someMethod", 0, List.of()));
        when(kg.hybridSearch("ShortClass#someMethod", "/p", 5))
                .thenReturn(List.of(new Seed("found-1", 0.8, "match")));

        List<MethodTarget> targets = new MethodTargetResolver(kg)
                .resolve(List.of("ShortClass#someMethod"), List.of(), "/p");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).nodeId()).isEqualTo("found-1");
    }

    @Test
    void resolve_skipsMalformedInput() {
        List<MethodTarget> targets = new MethodTargetResolver(kg)
                .resolve(List.of("no-separator", ""), List.of(), "/p");

        assertThat(targets).isEmpty();
    }
}
