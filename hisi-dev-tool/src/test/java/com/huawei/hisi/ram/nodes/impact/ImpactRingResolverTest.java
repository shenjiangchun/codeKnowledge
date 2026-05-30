package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImpactRingResolverTest {

    @Mock
    KgMcpClient kg;

    @Test
    void resolve_collectsUpstreamDownstreamBridgesAndCrossService() {
        // ModifiedRing with one parseable nodeId
        CallTreeNode tree = new CallTreeNode("com.foo.Bar#method", "com.foo.Bar", "method", 0, List.of());
        ModifiedRing modified = new ModifiedRing(List.of(tree));

        Entry up = new Entry("up1", "U", "m", "CONTROLLER");
        Entry down = new Entry("down1", "D", "m", null);
        Bridge feignBridge = new Bridge("br1", "FEIGN_CLIENT", "order-service");
        Bridge feignChainBridge = new Bridge("br-feign-chain", "FEIGN", "order-service");

        when(kg.affecting(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of(up));
        when(kg.downstream(eq("com.foo.Bar#method"), anyString(), anyInt())).thenReturn(List.of(down));
        when(kg.bridges(eq("com.foo.Bar#method"), anyString())).thenReturn(List.of(feignBridge));
        when(kg.feignChain(eq("order-service"), anyString())).thenReturn(List.of(feignChainBridge));

        ImpactRing impact = new ImpactRingResolver(kg).resolve(modified, "/p");

        assertThat(impact.upstream()).containsExactly(up);
        assertThat(impact.downstream()).containsExactly(down);
        assertThat(impact.bridges()).containsExactly(feignBridge);
        assertThat(impact.crossService()).containsExactly(feignChainBridge);
        assertThat(impact.allNodeIds())
                .containsExactlyInAnyOrder("up1", "down1", "br1", "br-feign-chain");
    }
}
