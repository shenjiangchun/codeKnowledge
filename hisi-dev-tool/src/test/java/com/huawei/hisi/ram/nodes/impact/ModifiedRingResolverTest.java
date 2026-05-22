package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Seed;
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
class ModifiedRingResolverTest {

    @Mock
    KgMcpClient kg;

    @Test
    void resolve_collectsCalleesTreeFromEverySeed() {
        Seed s1 = new Seed("a", 1.0, "");
        Seed s2 = new Seed("b", 1.0, "");
        CallTreeNode child = new CallTreeNode("child", "C", "m", 1, List.of());
        CallTreeNode rootA = new CallTreeNode("a", "A", "ma", 0, List.of(child));
        CallTreeNode rootB = new CallTreeNode("b", "B", "mb", 0, List.of());
        when(kg.calleesTree(eq("a"), anyString(), anyString(), anyInt())).thenReturn(rootA);
        when(kg.calleesTree(eq("b"), anyString(), anyString(), anyInt())).thenReturn(rootB);

        InvolvedRing involved = new InvolvedRing(List.of(s1, s2), List.of(), List.of());
        ModifiedRing modified = new ModifiedRingResolver(kg).resolve(involved, "/p", 2);

        assertThat(modified.tree()).containsExactly(rootA, rootB);
        assertThat(modified.allNodeIds()).containsExactlyInAnyOrder("a", "child", "b");
    }
}
