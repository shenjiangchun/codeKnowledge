package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.neo4j.model.IntentType;
import com.huawei.hisi.neo4j.model.SubQuery;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Impl;
import com.huawei.hisi.ram.kg.dto.Seed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvolvedRingResolverTest {

    @Mock
    KgMcpClient kg;
    @Mock
    QueryDecomposer decomposer;
    @Mock
    MultiQuerySearcher searcher;

    @Test
    void resolve_unionsSeedsEntriesImpls() {
        Seed seed = new Seed("s1", 0.9, "sum");
        Entry entry = new Entry("e1", "Cls", "m", "CONTROLLER");
        Impl impl = new Impl("i1", "Impl", "Iface");

        when(decomposer.decompose(anyString())).thenReturn(List.of(SubQuery.general("query")));
        when(searcher.search(any(), anyList(), anyInt(), anyInt())).thenReturn(List.of(seed));
        when(kg.entryPoints(anyList(), eq("ALL"))).thenReturn(List.of(entry));
        when(kg.implementations(eq("s1"), anyList())).thenReturn(List.of(impl));

        InvolvedRing ring = new InvolvedRingResolver(kg, decomposer, searcher).resolve("query", "/p");

        assertThat(ring.seeds()).containsExactly(seed);
        assertThat(ring.entries()).containsExactly(entry);
        assertThat(ring.impls()).containsExactly(impl);
        assertThat(ring.allNodeIds()).containsExactlyInAnyOrder("s1", "e1", "i1");
    }
}
