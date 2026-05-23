/**
 * Pinia store for the Requirement Analysis Master (RAM) workflow.
 *
 * Holds the latest Impact payload between {@code DraftPage} (where it is
 * captured) and {@code GraphPreviewPage} (where it is visualized). The store
 * intentionally stays tiny — long-lived session state is owned by the SSE
 * composable {@code useRamSession}.
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface ImpactPayload {
  readonly involved: readonly string[]
  readonly modified: readonly string[]
  readonly impacted: readonly string[]
  readonly riskScores?: Readonly<Record<string, number>>
}

export const useRamStore = defineStore('ram', () => {
  const impact = ref<ImpactPayload | null>(null)
  const lastSessionId = ref<string | null>(null)

  function setImpact(sessionId: string, payload: ImpactPayload): void {
    lastSessionId.value = sessionId
    impact.value = payload
  }

  function clear(): void {
    impact.value = null
    lastSessionId.value = null
  }

  return { impact, lastSessionId, setImpact, clear }
})
