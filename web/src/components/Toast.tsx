import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { Toaster, toast } from 'sonner'

type ToastKind = 'success' | 'error' | 'info'

export interface ToastAction {
  label: string
  onClick: () => void
}

interface ToastContextValue {
  showToast: (message: string, kind?: ToastKind, action?: ToastAction) => void
}

const ToastContext = createContext<ToastContextValue>({ showToast: () => {} })

function readDocTheme(): 'dark' | 'light' {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
}

/** 基于 Sonner 的全站现代堆叠通知系统（兼容旧 showToast API，新增可撤销 Action 支持）。 */
export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [theme, setTheme] = useState<'dark' | 'light'>(readDocTheme)

  useEffect(() => {
    const observer = new MutationObserver(() => setTheme(readDocTheme()))
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    return () => observer.disconnect()
  }, [])

  const showToast = useCallback((message: string, kind: ToastKind = 'info', action?: ToastAction) => {
    const opts = action
      ? { action: { label: action.label, onClick: action.onClick } }
      : undefined
    if (kind === 'success') toast.success(message, opts)
    else if (kind === 'error') toast.error(message, opts)
    else toast(message, opts)
  }, [])

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <Toaster
        theme={theme}
        position="bottom-right"
        richColors
        closeButton
        toastOptions={{
          duration: 3000,
          className: 'ms-sonner-toast',
        }}
      />
    </ToastContext.Provider>
  )
}

export function useToast() {
  return useContext(ToastContext)
}
