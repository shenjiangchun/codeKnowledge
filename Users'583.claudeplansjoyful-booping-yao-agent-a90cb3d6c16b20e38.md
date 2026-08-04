# Implementation Plan: Inter-Node Confirmation Gates for RAM DAG Pipeline

## Problem Statement

The RAM DAG pipeline runs all 4 nodes (clarify, impact, implement, verify) straight through without pausing. The user expects each node to pause, present output, and wait for confirmation.

## Key Insight: Most Infrastructure Already Exists

Already declared but never wired:
- SessionStatus.WAITING_HITL
- EventType.HITL_REQ / HITL_RES
- HitlQueue (fully implemented, never injected)
- DagNodeStatus awaiting-hitl in dagModel.ts
- DagFlow.vue edge animation for awaiting-hitl

---

See full plan details in the agent response text.