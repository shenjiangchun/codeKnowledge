package com.huawei.hisi.ram.model;

/**
 * Type classification for RAM (Requirement Analysis Master) sessions.
 * Used to distinguish different analysis modes in the session list.
 */
public enum SessionType {
    /** Demand analysis - the original RAM requirement analysis flow */
    DEMAND,
    /** Status analysis - project overview/status analysis */
    STATUS,
    /** Phase2 analysis - precise location analysis */
    PHASE2,
    /** Merge analysis - branch merge conflict/impact analysis */
    MERGE_ANALYSIS
}