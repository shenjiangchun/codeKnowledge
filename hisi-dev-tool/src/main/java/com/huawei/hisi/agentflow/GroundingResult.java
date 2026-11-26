package com.huawei.hisi.agentflow;

import java.util.List;

/**
 * 引用校验结果。
 *
 * @param citedIds    文本里出现的全部证据编号（去重）
 * @param unknownIds  其中证据包里不存在的编号，即模型编造的引用
 * @param hasCitation 文本里是否至少有一处引用
 */
public record GroundingResult(List<String> citedIds, List<String> unknownIds, boolean hasCitation) {

    /** 至少有一处引用，且没有编造的引用。 */
    public boolean clean() {
        return hasCitation && unknownIds.isEmpty();
    }
}
