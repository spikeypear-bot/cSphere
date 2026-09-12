import './ui.css'

interface ChipGroupProps<T extends string> {
  label: string
  options: readonly T[]
  labels: Record<T, string>
  selected: T[]
  onChange: (selected: T[]) => void
}

export function ChipGroup<T extends string>({
  label,
  options,
  labels,
  selected,
  onChange,
}: ChipGroupProps<T>) {
  function toggle(option: T) {
    onChange(
      selected.includes(option) ? selected.filter((item) => item !== option) : [...selected, option],
    )
  }

  return (
    <div className="field">
      <label>{label}</label>
      <div className="chip-group" role="group" aria-label={label}>
        {options.map((option) => (
          <button
            key={option}
            type="button"
            className="chip"
            aria-pressed={selected.includes(option)}
            onClick={() => toggle(option)}
          >
            {labels[option]}
          </button>
        ))}
      </div>
    </div>
  )
}
