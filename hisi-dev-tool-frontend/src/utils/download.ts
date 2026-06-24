/**
 * File download utility for handling blob responses.
 */

/**
 * Download a blob as a file with the specified filename.
 * Creates a temporary anchor element to trigger the browser's download mechanism.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

/**
 * Extract filename from Content-Disposition header or use fallback.
 */
export function extractFilename(contentDisposition: string | null, fallback: string): string {
  if (!contentDisposition) return fallback
  
  // Try to extract filename from attachment; filename="xxx"
  const filenameMatch = contentDisposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/)
  if (filenameMatch && filenameMatch[1]) {
    // Remove quotes if present
    return filenameMatch[1].replace(/['"]/g, '')
  }
  
  return fallback
}

/**
 * Get current timestamp string for filename.
 */
export function timestampFilename(): string {
  return new Date().toISOString().slice(0, 19).replace(/[:-]/g, '').replace('T', '_')
}
