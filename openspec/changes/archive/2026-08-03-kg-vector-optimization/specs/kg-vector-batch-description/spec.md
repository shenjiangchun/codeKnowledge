## ADDED Requirements

### Requirement: Batch LLM Description Generation
`UnifiedTextService` SHALL provide a batch description generation method that accepts a list of method infos and returns a list of descriptions.
The batch method SHALL construct a structured prompt containing all methods' class name, method name, signature, and comment.
The batch method SHALL request the LLM to return a JSON array of descriptions.
The batch method SHALL validate that the returned array length matches the input count.
The batch size SHALL be configurable (default 20).

#### Scenario: Batch description returns correct count
- GIVEN a list of 20 method infos
- WHEN batch description generation is called
- THEN the response SHALL contain exactly 20 descriptions
- AND `result[i]` SHALL correspond to `input[i]`

#### Scenario: Mismatched count triggers fallback
- GIVEN a list of 20 method infos
- WHEN the LLM returns an array of 19 descriptions (one missing)
- THEN the method SHALL fall back to individual `generateText` calls for all methods in the batch
- AND SHALL log a warning about the count mismatch

#### Scenario: Single method falls back to existing path
- GIVEN a list with a single method info
- WHEN batch description generation is called
- THEN the method MAY delegate to the existing `generateText(String)` individual call
