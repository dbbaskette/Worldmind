import { useState } from 'react'
import { INTERACTION_MODES } from '../utils/constants'

interface MissionFormProps {
  onSubmit: (request: string, mode: string, projectPath?: string) => Promise<void>
  submitting: boolean
  error: string | null
}

export function MissionForm({ onSubmit, submitting, error }: MissionFormProps) {
  const [request, setRequest] = useState('')
  const [mode, setMode] = useState('APPROVE_PLAN')
  const [projectPath, setProjectPath] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (request.trim()) {
      await onSubmit(request, mode, projectPath || undefined)
      setRequest('')
    }
  }

  return (
    <div className="bg-white shadow-sm border-b p-4">
      <div className="max-w-7xl mx-auto">
        <div className="flex items-center gap-3 mb-4">
          <img src="/logo.jpg" alt="Worldmind" className="h-12 w-auto object-contain" />
          <h1 className="text-2xl font-bold text-gray-900">Worldmind</h1>
          <span className="text-sm text-gray-500">Agentic Code Assistant</span>
        </div>

        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <textarea
              value={request}
              onChange={(e) => setRequest(e.target.value)}
              placeholder="Describe your development task in natural language..."
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
              rows={3}
              disabled={submitting}
            />
          </div>

          <div className="flex gap-3 items-end">
            <div className="flex-1">
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Interaction Mode
              </label>
              <select
                value={mode}
                onChange={(e) => setMode(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                disabled={submitting}
              >
                {INTERACTION_MODES.map(m => (
                  <option key={m.value} value={m.value}>{m.label}</option>
                ))}
              </select>
            </div>

            <div className="flex-1">
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Project Path (optional)
              </label>
              <input
                type="text"
                value={projectPath}
                onChange={(e) => setProjectPath(e.target.value)}
                placeholder="Leave empty for current project"
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                disabled={submitting}
              />
            </div>

            <button
              type="submit"
              disabled={submitting || !request.trim()}
              className="px-6 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 disabled:bg-gray-300 disabled:cursor-not-allowed transition-colors"
            >
              {submitting ? 'Submitting...' : 'Submit Mission'}
            </button>
          </div>

          {error && (
            <div className="text-red-600 text-sm bg-red-50 px-3 py-2 rounded">
              {error}
            </div>
          )}
        </form>
      </div>
    </div>
  )
}
