package com.huawei.hisi.agentflow;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性的引用校验：检查模型输出引用的证据编号是否真实存在于证据包。
 *
 * <p>这是 v2 agent 流的“外部验证模块”。它不调用模型，只做集合运算，结果可单测、可复现。
 */
public final class GroundingVerifier {

    private GroundingVerifier() {
    }

    /** 校验多段文本里出现的全部证据引用。 */
    public static GroundingResult verify(EvidencePack pack, String... texts) {
        List<String> cited = Citations.extract(texts);
        List<String> unknown = new ArrayList<>();
        for (String id : cited) {
            if (!pack.contains(id)) {
                unknown.add(id);
            }
        }
        return new GroundingResult(cited, unknown, !cited.isEmpty());
    }
}
