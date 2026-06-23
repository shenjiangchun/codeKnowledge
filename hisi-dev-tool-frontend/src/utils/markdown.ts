/**
 * Secure Markdown rendering utility with XSS protection
 *
 * This module provides a safe way to render Markdown content by sanitizing
 * the output with DOMPurify before returning HTML.
 */
import { marked } from 'marked'
import DOMPurify from 'dompurify'

// Configure marked for security
marked.setOptions({
  breaks: true,        // Convert line breaks to <br>
  gfm: true,           // GitHub Flavored Markdown
})

/**
 * Escapes HTML special characters to prevent XSS attacks
 *
 * @param str - Raw string that may contain HTML special characters
 * @returns Escaped string safe for HTML rendering
 */
export function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

/**
 * Escapes special regex characters to prevent ReDoS attacks
 *
 * @param str - Raw string that may contain regex special characters
 * @returns Escaped string safe for use in RegExp constructor
 */
export function escapeRegExp(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Safely renders Markdown content with XSS protection
 *
 * Handles three content types:
 * - String: Parse as Markdown directly
 * - Array: Format as bullet points
 * - Object: Format as key-value pairs
 *
 * @param content - Content to render (string, array, or object)
 * @returns Sanitized HTML string
 */
export function renderMarkdown(content: unknown): string {
  if (!content) return ''

  let rawHtml: string

  if (typeof content === 'object') {
    if (Array.isArray(content)) {
      // Format array items as bullet points
      const items = content.map(item => {
        if (typeof item === 'object' && item !== null) {
          return Object.entries(item)
            .map(([k, v]) => `**${k}:** ${v}`)
            .join('  \n')
        }
        return String(item)
      }).map(item => `- ${item}`)
      rawHtml = marked.parse(items.join('\n\n')) as string
    } else {
      // Object: format as key-value pairs
      const pairs = Object.entries(content)
        .map(([k, v]) => `**${k}:** ${typeof v === 'object' ? JSON.stringify(v) : v}`)
        .join('\n\n')
      rawHtml = marked.parse(pairs) as string
    }
  } else {
    // String: parse as markdown directly
    rawHtml = marked.parse(String(content)) as string
  }

  // Sanitize HTML to prevent XSS attacks
  return DOMPurify.sanitize(rawHtml)
}