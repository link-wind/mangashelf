import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { AuthProvider } from './auth'
import './styles/tokens.css'
import './styles/layout.css'
import './styles/components.css'
import './styles/pages.css'

createRoot(document.getElementById('root')!, {
  onUncaughtError: (error, errorInfo) => {
    const stack = errorInfo && typeof errorInfo.componentStack === 'string'
      ? errorInfo.componentStack.slice(0, 400) : JSON.stringify(errorInfo)
    console.error('Uncaught render error:', error, stack)
    document.title = 'ERR: ' + String(error).slice(0, 120) + ' @ ' + stack.slice(0, 120)
  },
  onCaughtError: (error) => {
    console.error('Caught render error:', error)
  },
}).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
