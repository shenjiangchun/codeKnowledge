/**
 * Helpers shared by RAM clarify UI.
 *
 * The orchestrator may emit either a list of bare question strings (legacy)
 * or a list of {@link ClarifyField} descriptors. {@link normalizeClarifyFields}
 * coerces both shapes into the {@link ClarifyField} contract. {@link initialAnswers}
 * builds the matching default-value map.
 */
export interface ClarifyField {
  readonly name: string
  readonly label?: string
  readonly type?: 'string' | 'number' | 'boolean' | 'enum'
  readonly options?: readonly string[]
  readonly required?: boolean
}

export interface ClarifyModalSchema {
  readonly nodeName?: string
  readonly questions: readonly (string | ClarifyField)[]
}

export function normalizeClarifyFields(schema: ClarifyModalSchema | null): ClarifyField[] {
  if (!schema) return []
  return schema.questions.map((q, idx) => {
    if (typeof q === 'string') {
      return { name: `q${idx}`, label: q, type: 'string' as const }
    }
    return { type: 'string' as const, ...q }
  })
}

export function initialAnswers(fields: readonly ClarifyField[]): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const f of fields) {
    if (f.type === 'boolean') out[f.name] = false
    else if (f.type === 'number') out[f.name] = 0
    else if (f.type === 'enum') out[f.name] = f.options?.[0] ?? ''
    else out[f.name] = ''
  }
  return out
}
