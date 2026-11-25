import { createContext, useCallback, useContext, useState } from 'react'

type ToastKind = 'success' | 'error' | 'info'

interface ToastItem {
  id: number
  kind: ToastKind
  message: string
}

interface ToastContextValue {
  showToast: (message: string, kind?: ToastKind) => void
}

const ToastContext = createContext<ToastContextValue>({ showToast: () => {} })

let nextId = 1

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])

  const showToast = useCallback((message: string, kind: ToastKind = 'info') => {
    const id = nextId++
    setItems(prev => [...prev.slice(-3), { id, kind, message }])
    window.setTimeout(() => {
      setItems(prev => prev.filter(t => t.id !== id))
    }, 2800)
  }, [])

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      {items.length > 0 && (
        <div className="toast-viewport" role="status" aria-live="polite">
          {items.map(t => (
            <div key={t.id} className={`toast-item ${t.kind}`}>
              <span>{t.message}</span>
            </div>
          ))}
        </div>
      )}
    </ToastContext.Provider>
  )
}

export function useToast() {
  return useContext(ToastContext)
}
