import request from '@/utils/request'

/**
 * APM Test Case — a saved request configuration for replay.
 */
export interface ApmTestCase {
  id?: number
  name: string
  projectPath: string
  entryNodeId?: string | null
  method?: string | null
  url?: string | null
  headers?: string | null   // JSON string
  params?: string | null    // JSON string
  body?: string | null
  createdAt?: number
  updatedAt?: number
}

/**
 * APM Test Case CRUD API module.
 *
 * Note: The Axios response interceptor in utils/request.ts unwraps
 * `ApiResponse.data`, so the runtime return value is the inner payload.
 * We cast once here at the API boundary.
 */
export const testCaseApi = {
  /** List all test cases for a project. */
  list(projectPath: string): Promise<ApmTestCase[]> {
    return request.get('/apm/test-cases', {
      params: { projectPath },
    }) as unknown as Promise<ApmTestCase[]>
  },

  /** Get a single test case by ID. */
  getById(id: number): Promise<ApmTestCase> {
    return request.get(`/apm/test-cases/${id}`) as unknown as Promise<ApmTestCase>
  },

  /** Create a new test case. */
  create(testCase: ApmTestCase): Promise<ApmTestCase> {
    return request.post('/apm/test-cases', testCase) as unknown as Promise<ApmTestCase>
  },

  /** Update an existing test case. */
  update(id: number, testCase: ApmTestCase): Promise<ApmTestCase> {
    return request.put(`/apm/test-cases/${id}`, testCase) as unknown as Promise<ApmTestCase>
  },

  /** Delete a test case. */
  delete(id: number): Promise<{ id: number; deleted: boolean }> {
    return request.delete(`/apm/test-cases/${id}`) as unknown as Promise<{ id: number; deleted: boolean }>
  },
}
