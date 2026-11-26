import { defineStore } from 'pinia'
import { workbenchApi } from '@/api/workbench'
import type { WorkbenchItem, WorkbenchSummary, WorkbenchFixRun } from '@/types/workbench'

/** 工作台页面与监视器共享的状态。 */
export const useWorkbenchStore = defineStore('workbench', {
  state: () => ({
    summary: null as WorkbenchSummary | null,
    items: [] as WorkbenchItem[],
    runs: [] as WorkbenchFixRun[],
    loaded: false,
    loading: false,
  }),
  actions: {
    async load(force = false) {
      if (this.loading) return
      if (this.loaded && !force) return
      this.loading = true
      try {
        const [summary, items, runs] = await Promise.all([
          workbenchApi.summary(),
          workbenchApi.items(),
          workbenchApi.runs(),
        ])
        this.summary = summary
        this.items = items
        this.runs = runs
        this.loaded = true
      } finally {
        this.loading = false
      }
    },
    async refresh() {
      await this.load(true)
    },
    applyRun(run: WorkbenchFixRun) {
      const index = this.runs.findIndex(r => r.id === run.id)
      if (index >= 0) {
        this.runs[index] = { ...this.runs[index], ...run }
      } else {
        this.runs.unshift(run)
      }
    },
  },
})
