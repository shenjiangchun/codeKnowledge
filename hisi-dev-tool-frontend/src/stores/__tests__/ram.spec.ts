import { setActivePinia, createPinia } from 'pinia'
import { describe, expect, it, beforeEach } from 'vitest'
import { useRamStore } from '../ram'

describe('useRamStore linkage state', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('tracks selectedFile', () => {
    const s = useRamStore()
    s.selectFile('A.java')
    expect(s.selectedFile).toBe('A.java')
  })

  it('tracks hoveredFile and clears it', () => {
    const s = useRamStore()
    s.hoverFile('B.java')
    expect(s.hoveredFile).toBe('B.java')
    s.hoverFile(null)
    expect(s.hoveredFile).toBeNull()
  })

  it('stores upstream highlight path as a Set', () => {
    const s = useRamStore()
    s.setHighlightPath(['A.java', 'B.java'])
    expect(s.highlightPath.has('A.java')).toBe(true)
    expect(s.highlightPath.has('B.java')).toBe(true)
  })
})
