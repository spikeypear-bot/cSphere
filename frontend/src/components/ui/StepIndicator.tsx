import './ui.css'

export interface Step {
  key: string
  label: string
}

interface StepIndicatorProps {
  steps: Step[]
  currentIndex: number
}

/** Named steps, not a percentage bar — 2026 wizard-UX guidance favours knowing
 * *what's* left over a bare progress number (see docs/product-context.md's
 * design notes). */
export function StepIndicator({ steps, currentIndex }: StepIndicatorProps) {
  return (
    <ol className="step-indicator">
      {steps.map((step, index) => (
        <li
          key={step.key}
          data-state={index === currentIndex ? 'current' : index < currentIndex ? 'done' : 'upcoming'}
        >
          {step.label}
        </li>
      ))}
    </ol>
  )
}
