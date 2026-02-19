import { useState } from 'react'
import { apiClient } from '../api/client'

interface LoginScreenProps {
  onLogin: (username: string) => void
}

export function LoginScreen({ onLogin }: LoginScreenProps) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setLoading(true)

    try {
      const result = await apiClient.login(username, password)
      if (result.authenticated && result.username) {
        onLogin(result.username)
      } else {
        setError(result.error || 'Invalid credentials')
      }
    } catch {
      setError('Failed to connect to server')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex items-center justify-center min-h-screen bg-wm-bg noise-overlay">
      <div className="w-full max-w-sm">
        <div className="bg-wm-surface border border-wm-border rounded-xl p-8 shadow-2xl">
          {/* Logo */}
          <div className="flex flex-col items-center mb-8">
            <img src="/logo.png" alt="Worldmind" className="h-16 w-auto mb-4" />
            <h1 className="text-xl font-semibold text-wm_text-primary tracking-wide">WORLDMIND</h1>
            <p className="text-xs text-wm_text-muted mt-1">Mission Control</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-[10px] font-mono uppercase tracking-wider text-wm_text-muted mb-1.5">
                Username
              </label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="w-full bg-wm-bg border border-wm-border rounded-lg px-4 py-2.5 text-sm text-wm_text-primary placeholder:text-wm_text-dim focus:outline-none focus:border-centurion-vigil/50 focus:ring-1 focus:ring-centurion-vigil/20 transition-all"
                placeholder="Enter username"
                disabled={loading}
                autoFocus
              />
            </div>

            <div>
              <label className="block text-[10px] font-mono uppercase tracking-wider text-wm_text-muted mb-1.5">
                Password
              </label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full bg-wm-bg border border-wm-border rounded-lg px-4 py-2.5 text-sm text-wm_text-primary placeholder:text-wm_text-dim focus:outline-none focus:border-centurion-vigil/50 focus:ring-1 focus:ring-centurion-vigil/20 transition-all"
                placeholder="Enter password"
                disabled={loading}
              />
            </div>

            {error && (
              <div className="text-xs text-red-400 bg-red-500/10 border border-red-500/20 px-3 py-2 rounded animate-fade-in">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading || !username || !password}
              className="w-full px-4 py-2.5 bg-centurion-vigil text-white rounded-lg text-sm font-medium hover:bg-centurion-vigil/90 disabled:opacity-30 disabled:cursor-not-allowed transition-all"
            >
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <span className="w-3 h-3 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                  Authenticating...
                </span>
              ) : (
                'Sign In'
              )}
            </button>
          </form>
        </div>

        <p className="text-center text-[10px] text-wm_text-dim mt-6">
          Agentic Code Assistant
        </p>
      </div>
    </div>
  )
}
