import { useState } from 'react'
import { INTERACTION_MODES } from '../utils/constants'

interface MissionFormProps {
  onSubmit: (request: string, mode: string, projectPath?: string, gitRemoteUrl?: string, reasoningLevel?: string) => Promise<void>
  submitting: boolean
  error: string | null
  showSettings?: boolean
  onToggleSettings?: () => void
}

const REASONING_LEVELS = [
  { value: 'low', label: 'Quick', description: 'Fast, minimal reasoning' },
  { value: 'medium', label: 'Balanced', description: 'Default reasoning depth' },
  { value: 'high', label: 'Thorough', description: 'Step-by-step analysis' },
  { value: 'max', label: 'Deep', description: 'Maximum reasoning, quality focus' },
]

export function MissionForm({ onSubmit, submitting, error, showSettings, onToggleSettings }: MissionFormProps) {
  const [request, setRequest] = useState('')
  const [mode, setMode] = useState('APPROVE_PLAN')
  const [projectPath, setProjectPath] = useState('')
  const [gitRemoteUrl, setGitRemoteUrl] = useState('')
  const [reasoningLevel, setReasoningLevel] = useState('medium')
  const [showOptions, setShowOptions] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (request.trim()) {
      await onSubmit(request, mode, projectPath || undefined, gitRemoteUrl || undefined, reasoningLevel)
      setRequest('')
    }
  }

  return (
    <div className="bg-wm-surface border-b border-wm-border px-4 py-3">
      <form onSubmit={handleSubmit}>
        <div className="flex items-center gap-3">
          {/* Logo + Brand */}
          <div className="flex items-center gap-2.5 shrink-0">
            <img src="/logo.png" alt="Worldmind" className="h-9 w-auto object-contain" />
            <div className="hidden sm:block">
              <h1 className="text-sm font-semibold text-wm_text-primary tracking-wide">WORLDMIND</h1>
            </div>
          </div>

          <div className="w-px h-8 bg-wm-border shrink-0" />

          {/* Input */}
          <div className="flex-1">
            <input
              type="text"
              value={request}
              onChange={(e) => setRequest(e.target.value)}
              placeholder="Describe your mission..."
              className="w-full bg-wm-bg border border-wm-border rounded-lg px-4 py-2 text-sm text-wm_text-primary placeholder:text-wm_text-dim focus:outline-none focus:border-centurion-vigil/50 focus:ring-1 focus:ring-centurion-vigil/20 transition-all"
              disabled={submitting}
            />
          </div>

          {/* Mode selector */}
          <select
            value={mode}
            onChange={(e) => setMode(e.target.value)}
            className="bg-wm-bg border border-wm-border rounded-lg px-3 py-2 text-xs font-mono text-wm_text-secondary focus:outline-none focus:border-centurion-vigil/50 shrink-0"
            disabled={submitting}
          >
            {INTERACTION_MODES.map(m => (
              <option key={m.value} value={m.value}>{m.label}</option>
            ))}
          </select>

          {/* Options toggle */}
          <button
            type="button"
            onClick={() => setShowOptions(!showOptions)}
            className={`p-2 rounded-lg border transition-colors shrink-0 ${
              showOptions
                ? 'bg-centurion-vigil/10 border-centurion-vigil/30 text-centurion-vigil'
                : 'bg-wm-bg border-wm-border text-wm_text-muted hover:text-wm_text-secondary'
            }`}
            title="Mission options"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M6.75 12a.75.75 0 11-1.5 0 .75.75 0 011.5 0zM12.75 12a.75.75 0 11-1.5 0 .75.75 0 011.5 0zM18.75 12a.75.75 0 11-1.5 0 .75.75 0 011.5 0z" />
            </svg>
          </button>

          {/* Settings toggle */}
          {onToggleSettings && (
            <button
              type="button"
              onClick={onToggleSettings}
              className={`p-2 rounded-lg border transition-colors shrink-0 ${
                showSettings
                  ? 'bg-centurion-vigil/10 border-centurion-vigil/30 text-centurion-vigil'
                  : 'bg-wm-bg border-wm-border text-wm_text-muted hover:text-wm_text-secondary'
              }`}
              title="Settings"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
            </button>
          )}

          {/* Submit */}
          <button
            type="submit"
            disabled={submitting || !request.trim()}
            className="px-4 py-2 bg-centurion-vigil text-white rounded-lg text-sm font-medium hover:bg-centurion-vigil/90 disabled:opacity-30 disabled:cursor-not-allowed transition-all shrink-0"
          >
            {submitting ? (
              <span className="flex items-center gap-2">
                <span className="w-3 h-3 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                Launching...
              </span>
            ) : (
              'Launch'
            )}
          </button>
        </div>

        {/* Expandable options */}
        {showOptions && (
          <div className="mt-3 animate-fade-in space-y-3">
            {/* Reasoning Level */}
            <div>
              <label className="block text-[10px] font-mono uppercase tracking-wider text-wm_text-muted mb-1.5">
                Reasoning Depth
              </label>
              <div className="flex gap-2">
                {REASONING_LEVELS.map(level => (
                  <button
                    key={level.value}
                    type="button"
                    onClick={() => setReasoningLevel(level.value)}
                    disabled={submitting}
                    className={`flex-1 px-3 py-1.5 rounded border text-xs transition-all ${
                      reasoningLevel === level.value
                        ? 'bg-centurion-vigil/20 border-centurion-vigil/50 text-centurion-vigil'
                        : 'bg-wm-bg border-wm-border text-wm_text-muted hover:border-wm_text-muted/50'
                    }`}
                    title={level.description}
                  >
                    {level.label}
                  </button>
                ))}
              </div>
              <p className="text-[10px] text-wm_text-muted mt-1">
                {REASONING_LEVELS.find(l => l.value === reasoningLevel)?.description}
              </p>
            </div>

            {/* Project Path & Git URL */}
            <div className="flex gap-3">
              <div className="flex-1">
                <label className="block text-[10px] font-mono uppercase tracking-wider text-wm_text-muted mb-1">
                  Project Path
                </label>
                <input
                  type="text"
                  value={projectPath}
                  onChange={(e) => setProjectPath(e.target.value)}
                  placeholder="/path/to/project"
                  className="w-full bg-wm-bg border border-wm-border rounded px-3 py-1.5 text-xs font-mono text-wm_text-secondary placeholder:text-wm_text-dim focus:outline-none focus:border-centurion-vigil/50"
                  disabled={submitting}
                />
              </div>
              <div className="flex-1">
                <label className="block text-[10px] font-mono uppercase tracking-wider text-wm_text-muted mb-1">
                  Git Remote URL
                </label>
                <input
                  type="text"
                  value={gitRemoteUrl}
                  onChange={(e) => setGitRemoteUrl(e.target.value)}
                  placeholder="https://github.com/..."
                  className="w-full bg-wm-bg border border-wm-border rounded px-3 py-1.5 text-xs font-mono text-wm_text-secondary placeholder:text-wm_text-dim focus:outline-none focus:border-centurion-vigil/50"
                  disabled={submitting}
                />
              </div>
            </div>
          </div>
        )}

        {error && (
          <div className="mt-2 text-xs text-red-400 bg-red-500/10 border border-red-500/20 px-3 py-1.5 rounded animate-fade-in">
            {error}
          </div>
        )}
      </form>
    </div>
  )
}
