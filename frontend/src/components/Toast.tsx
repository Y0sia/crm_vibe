import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'

type Toast = { text: string; kind: 'info' | 'error' }

const ToastContext = createContext<{ show: (text: string, kind?: Toast['kind']) => void; error: (e: unknown) => void }>({
  show: () => {},
  error: () => {},
})

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<Toast | null>(null)
  const timer = useRef<number | undefined>(undefined)

  const show = useCallback((text: string, kind: Toast['kind'] = 'info') => {
    window.clearTimeout(timer.current)
    setToast({ text, kind })
    timer.current = window.setTimeout(() => setToast(null), 4000)
  }, [])

  const error = useCallback(
    (e: unknown) => show(e instanceof ApiError ? e.message : 'Что-то пошло не так. Попробуйте ещё раз.', 'error'),
    [show],
  )

  useEffect(() => () => window.clearTimeout(timer.current), [])

  return (
    <ToastContext.Provider value={{ show, error }}>
      {children}
      {toast && (
        <div className={`toast ${toast.kind === 'error' ? 'error' : ''}`} role="status" aria-live="polite">
          {toast.text}
        </div>
      )}
    </ToastContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export const useToast = () => useContext(ToastContext)
