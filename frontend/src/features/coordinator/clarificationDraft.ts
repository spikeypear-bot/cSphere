import { FIELD_LABELS } from '../../types/eventRequest'

/** EC01/EO26: longest clarification message or response (matches the server). */
export const MAX_MESSAGE = 2000

/**
 * One clarification message from a question per flagged field, in flag
 * order, each labelled with its field, e.g.
 * "Start date & time: Can it start at 10am?\n\nVenue requirements: ...".
 * The server still receives one message plus the flagged field keys, so the
 * organiser's form highlights the same fields the questions are about.
 * Fields with no question yet are left out.
 */
export function composeClarification(flags: string[], questions: Record<string, string>): string {
  return flags
    .map((field) => [FIELD_LABELS[field] ?? field, (questions[field] ?? '').trim()] as const)
    .filter(([, question]) => question.length > 0)
    .map(([label, question]) => `${label}: ${question}`)
    .join('\n\n')
}
