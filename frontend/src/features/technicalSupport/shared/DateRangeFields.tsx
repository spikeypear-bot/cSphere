import type { DateRange } from './dateWindow'
import { rangeToWindow } from './dateWindow'
import '../technicalSupport.css'

/** First and last day of a window, as two date inputs. */
export function DateRangeFields({ value, onChange, legend = 'Dates' }: {
  value: DateRange
  onChange: (range: DateRange) => void
  legend?: string
}) {
  const valid = rangeToWindow(value) !== null
  return (
    <>
      <fieldset className="tech-period">
        <legend>{legend}</legend>
        <div className="field">
          <label htmlFor="range-from">From</label>
          <input id="range-from" type="date" value={value.from}
            onChange={(e) => onChange({ ...value, from: e.target.value })} aria-invalid={!valid} />
        </div>
        <div className="field">
          <label htmlFor="range-to">To (inclusive)</label>
          <input id="range-to" type="date" value={value.to}
            onChange={(e) => onChange({ ...value, to: e.target.value })} aria-invalid={!valid} />
        </div>
      </fieldset>
      {!valid && <p role="alert" className="error-text">Choose a last day on or after the first day.</p>}
    </>
  )
}
