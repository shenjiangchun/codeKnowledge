/**
 * Tiny polar-coordinate helper shared by RAM ring visualizations.
 *
 * {@link polarPoint} distributes a given index evenly around a circle of
 * radius {@code radius} centered at ({@code cx}, {@code cy}). The first node
 * (index 0) sits at the 12-o'clock position (-π/2).
 */
export interface PolarPoint {
  readonly x: number
  readonly y: number
}

export function polarPoint(
  cx: number,
  cy: number,
  radius: number,
  index: number,
  total: number
): PolarPoint {
  const safeTotal = total > 0 ? total : 1
  const angle = (2 * Math.PI * index) / safeTotal - Math.PI / 2
  return {
    x: cx + radius * Math.cos(angle),
    y: cy + radius * Math.sin(angle)
  }
}
