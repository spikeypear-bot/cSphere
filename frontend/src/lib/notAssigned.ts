/** True for the server's refusal of a coordinator who is not (or no longer)
 * assigned to a request or event (ECL-C7: 403 with a plain explanation). */
export function isNotAssigned(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { status?: number }).status === 403
}
