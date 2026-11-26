package com.huawei.hisi.agentflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidencePackTest {

    /** 只有图谱类证据才带 nodeId；堆栈帧不是图谱节点。 */
    private EvidencePack packWithMethod(String kind, boolean exact) {
        EvidencePack pack = new EvidencePack();
        boolean fromGraph = !"frame".equals(kind);
        pack.add(kind, "说明", fromGraph && exact ? "svc:com.a.B.c.1f" : null, "com.a.B", "c", null, exact);
        return pack;
    }

    @Test
    void anchorsAreNumberedInInsertionOrder() {
        EvidencePack pack = new EvidencePack();
        EvidenceAnchor first = pack.add("frame", "堆栈", null, "com.a.B", "c", "B.java", true);
        EvidenceAnchor second = pack.add("method", "命中", "svc:com.a.B.c.1f", "com.a.B", "c", null, true);

        assertEquals("E1", first.id());
        assertEquals("E2", second.id());
        assertEquals(2, pack.size());
        assertTrue(pack.contains("E2"));
        assertFalse(pack.contains("E9"));
    }

    @Test
    void hasGraphMethodIgnoresFramesAndFuzzyMatches() {
        // 堆栈帧不是图谱节点，不能算作「在图谱中」
        assertFalse(packWithMethod("frame", true).hasGraphMethod("com.a.B", "c"));
        // 语义模糊命中没有精确落点
        assertFalse(packWithMethod("semantic", false).hasGraphMethod("com.a.B", "c"));
        // 精确命中才算
        assertTrue(packWithMethod("method", true).hasGraphMethod("com.a.B", "c"));
        assertFalse(packWithMethod("method", true).hasGraphMethod("com.a.B", "other"));
    }

    @Test
    void citationsAreExtractedInOrderAndDeduplicated() {
        List<String> ids = Citations.extract("步骤一 [[E3]] 步骤二 [[E1]]", "再次提到 [[ E3 ]]，还有 [[E 12]]");

        assertEquals(List.of("E3", "E1", "E12"), ids);
        assertTrue(Citations.extract((String) null, "").isEmpty());
    }

    @Test
    void verifierSeparatesRealAndInventedCitations() {
        EvidencePack pack = new EvidencePack();
        pack.add("frame", "堆栈", null, "com.a.B", "c", null, true);
        pack.add("method", "命中", "svc:com.a.B.c.1f", "com.a.B", "c", null, true);

        GroundingResult clean = GroundingVerifier.verify(pack, "结论 [[E2]]，依据 [[E1]]");
        assertTrue(clean.clean());
        assertEquals(List.of("E2", "E1"), clean.citedIds());

        GroundingResult invented = GroundingVerifier.verify(pack, "结论 [[E2]] 与 [[E7]]");
        assertFalse(invented.clean());
        assertEquals(List.of("E7"), invented.unknownIds());

        GroundingResult none = GroundingVerifier.verify(pack, "没有任何引用的结论");
        assertFalse(none.clean());
        assertFalse(none.hasCitation());
    }

    @Test
    void unknownCitationsAreMarkedInPlaceAndStrippedLater() {
        EvidencePack pack = new EvidencePack();
        pack.add("frame", "堆栈", null, "com.a.B", "c", null, true);

        String marked = Citations.markUnknown("真实 [[E1]]，编造 [[E5]]", pack::contains);

        assertEquals("真实 [[E1]]，编造 " + Citations.UNVERIFIED_MARK, marked);
        assertEquals("真实 ，编造 ", Citations.strip(marked));
        assertEquals("", Citations.strip(null));
    }

    @Test
    void citationsHaveAReadableDisplayForm() {
        assertEquals("依据 [E3] 与 [E12]，未验证的写成 [未验证引用]",
                Citations.toDisplay("依据 [[E3]] 与 [[E 12]]，未验证的写成 " + Citations.UNVERIFIED_MARK));
        assertEquals("", Citations.toDisplay(null));
    }

    @Test
    void renderStopsWhenTooLong() {
        EvidencePack pack = new EvidencePack();
        for (int i = 0; i < 30; i++) {
            pack.add("frame", "很长的说明文字很长的说明文字很长的说明文字" + i, null, "com.a.B", "m" + i, null, true);
        }

        String rendered = pack.render(200);

        assertTrue(rendered.contains("已截断"));
        assertFalse(rendered.contains("m29()"));
    }
}
