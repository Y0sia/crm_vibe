import type { Status } from '../api/types'
import { STATUS_COLORS } from '../lib/palette'

/** Статус лида «пилюлей» с выпадающим списком, как в строке таблицы макета. */
export function StatusSelect({
  statuses,
  value,
  onChange,
  disabled,
  label = 'Статус лида',
}: {
  statuses: Status[]
  value: number
  onChange: (statusId: number) => void
  disabled?: boolean
  label?: string
}) {
  const current = statuses.find((s) => s.id === value)
  const colors = STATUS_COLORS[current?.color ?? 'GRAY']
  return (
    <select
      className="status-select"
      aria-label={label}
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(Number(e.target.value))}
      style={{ background: colors.bg, color: colors.fg }}
    >
      {statuses.map((s) => (
        <option key={s.id} value={s.id}>
          {s.name}
        </option>
      ))}
    </select>
  )
}
