package com.huawei.hisi.archdiagnosis.domain;

import java.util.List;
import java.util.Set;

/**
 * 垂直领域（命名领域词 × 分层角色贯穿聚合的结果）。
 *
 * <p>不可变值对象：领域词、成员类、出现的分层角色、骨架判定结果。
 * 命名（LLM 消歧/中文名）通过 {@link #withName(String)} 返回新实例，不改原对象。</p>
 */
public final class VerticalDomain {

    private final String domainWord;
    private final List<ClassRoleRecord> members;
    private final Set<String> roles;
    private final boolean complete;
    private final String name;

    public VerticalDomain(String domainWord, List<ClassRoleRecord> members,
                          Set<String> roles, boolean complete) {
        this(domainWord, members, roles, complete, domainWord);
    }

    private VerticalDomain(String domainWord, List<ClassRoleRecord> members,
                           Set<String> roles, boolean complete, String name) {
        this.domainWord = domainWord;
        this.members = List.copyOf(members);
        this.roles = Set.copyOf(roles);
        this.complete = complete;
        this.name = name;
    }

    /** 返回带新领域名的新实例（LLM 命名阶段用，copy-on-write）。 */
    public VerticalDomain withName(String newName) {
        return new VerticalDomain(domainWord, members, roles, complete,
                newName == null || newName.isBlank() ? domainWord : newName);
    }

    /** 核心领域词（技术后缀剥离后的原文，如 {@code Order}）。 */
    public String domainWord() {
        return domainWord;
    }

    /** 成员类（跨层类，含类名与角色）。 */
    public List<ClassRoleRecord> members() {
        return members;
    }

    /** 该领域出现的分层角色集合（已剔除 UNKNOWN）。 */
    public Set<String> roles() {
        return roles;
    }

    /** 骨架判定：是否完整领域（≥2 分层角色且含业务角色）。 */
    public boolean complete() {
        return complete;
    }

    /** 领域展示名（LLM 命名结果；未命名时回退领域词原文）。 */
    public String name() {
        return name;
    }
}
