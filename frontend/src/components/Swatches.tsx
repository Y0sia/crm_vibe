export function Swatches<K extends string>({
  options,
  value,
  onChange,
  small,
}: {
  options: { key: K; color: string; label: string }[]
  value: K
  onChange: (key: K) => void
  small?: boolean
}) {
  return (
    <div className="swatches" role="group" aria-label="Цвет">
      {options.map((o) => (
        <button
          key={o.key}
          type="button"
          className={`swatch${small ? ' small' : ''}`}
          aria-label={`Цвет ${o.label}`}
          aria-pressed={o.key === value}
          onClick={() => onChange(o.key)}
        >
          <span style={{ background: o.color }} />
        </button>
      ))}
    </div>
  )
}
