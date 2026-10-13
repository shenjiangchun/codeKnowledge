package com.huawei.hisi.archdiagnosis.domain;

import java.util.List;

/**
 * 垂直领域识别结果：完整领域 + 孤立数据对象（无业务角色或单角色，不判为领域）。
 *
 * @param domains              完整领域（垂直贯穿、含业务角色）
 * @param isolatedDataObjects  孤立数据对象（纯 MODEL/UTILITY 或单角色词）
 */
public record VerticalDomainResult(List<VerticalDomain> domains,
                                   List<VerticalDomain> isolatedDataObjects) {
}
