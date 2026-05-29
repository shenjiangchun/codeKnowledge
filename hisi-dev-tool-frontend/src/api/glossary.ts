import request from '@/utils/request'
import type { GlossaryTerm } from '@/types/glossary'

export const glossaryApi = {
  list(projectPath: string) {
    return request.get<GlossaryTerm[]>('/glossary', {
      params: { projectPath }
    })
  },

  create(term: GlossaryTerm) {
    return request.post<GlossaryTerm>('/glossary', term)
  },

  update(id: number, term: GlossaryTerm) {
    return request.put<GlossaryTerm>(`/glossary/${id}`, term)
  },

  delete(id: number) {
    return request.delete<{ id: number; deleted: boolean }>(`/glossary/${id}`)
  }
}
