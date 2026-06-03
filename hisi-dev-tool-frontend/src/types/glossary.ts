export interface GlossaryTerm {
  id?: number
  projectPath: string
  term: string
  synonym: string
  context?: string
  createdAt?: number
  updatedAt?: number
}
