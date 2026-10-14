import { useEffect, type ReactNode } from 'react'

/** Окно поверх страницы. Закрывается по Esc и по клику на затемнение. */
export function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="dialog-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="card dialog" role="dialog" aria-modal="true" aria-label={title}>
        <div className="card-head" style={{ alignItems: 'center' }}>
          <h2 className="card-title">{title}</h2>
          <button type="button" className="filter-close" style={{ order: 0 }} aria-label="Закрыть" onClick={onClose}>
            ×
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
