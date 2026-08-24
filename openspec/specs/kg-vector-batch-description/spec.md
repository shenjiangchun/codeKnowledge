# KG Vector Batch Description

## Purpose
Provide batch LLM description generation for methods via `LLMDescriptionService.generateDescriptionsBatch(List<MethodNode>)`, reducing LLM API calls by ~95% (from ~5K to ~250 for 5051 methods). Includes adaptive batch size control (Chiron v3 algorithm) with token pre-flight checks and automatic fallback to single-call on failure.

## Requirements

### Requirement: Batch LLM Description Generation
`UnifiedTextService` SHALL provide a batch description generation method that accepts a list of method infos and returns a list of descriptions.
The batch method SHALL construct a structured prompt containing all methods' class name, method name, signature, comment, and method body.
The batch method SHALL use `response_format: json_object` (when supported by the model) to request a `{"descriptions": [...]}` JSON response.
The batch method SHALL validate that the returned array length matches the input count.
The batch size SHALL be adaptive (default starting at 20, configurable).

#### Scenario: Batch description returns correct count
- GIVEN a list of 20 method infos
- WHEN batch description generation is called
- THEN the response SHALL contain exactly 20 descriptions
- AND `result[i]` SHALL correspond to `input[i]`

#### Scenario: Mismatched count triggers fallback
- GIVEN a list of 20 method infos
- WHEN the LLM returns an array of 19 descriptions (one missing)
- THEN the method SHALL retry once with error feedback
- AND if retry fails, SHALL fall back to individual `generateText` calls for all methods in the batch

#### Scenario: Single method falls back to existing path
- GIVEN a list with a single method info
- WHEN batch description generation is called
- THEN the method MAY delegate to the existing `generateText(String)` individual call
