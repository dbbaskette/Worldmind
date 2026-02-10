import { useState } from 'react'
import { DirectiveResponse } from '../api/types'
import { StatusBadge } from './StatusBadge'
import { formatDuration } from '../utils/formatting'

interface DirectiveCardProps {
  directive: DirectiveResponse
}

export function DirectiveCard({ directive }: DirectiveCardProps) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="bg-white border rounded-lg p-4 hover:shadow-md transition-shadow">
      <div className="flex items-start justify-between mb-3">
        <div className="flex items-center gap-2">
          <span className="text-sm font-mono text-gray-600">{directive.id}</span>
          <StatusBadge status={directive.centurion} type="centurion" />
        </div>
        <StatusBadge status={directive.status} type="directive" />
      </div>

      <p className="text-sm text-gray-900 mb-3">{directive.description}</p>

      <div className="flex items-center gap-4 text-xs text-gray-500 mb-3">
        <span>Iteration: {directive.iteration}/{directive.max_iterations}</span>
        {directive.elapsed_ms && (
          <span>Duration: {formatDuration(directive.elapsed_ms)}</span>
        )}
      </div>

      {directive.files_affected && directive.files_affected.length > 0 && (
        <div>
          <button
            onClick={() => setExpanded(!expanded)}
            className="text-xs text-blue-600 hover:text-blue-800 font-medium"
          >
            {expanded ? '▼' : '▶'} Files Affected ({directive.files_affected.length})
          </button>

          {expanded && (
            <div className="mt-2 space-y-1">
              {directive.files_affected.map((file, idx) => (
                <div key={idx} className="text-xs text-gray-700 bg-gray-50 px-2 py-1 rounded">
                  <span className="font-mono text-purple-600">{file.action}</span>
                  {' '}
                  <span className="font-mono">{file.path}</span>
                  {file.linesChanged > 0 && (
                    <span className="text-gray-500"> ({file.linesChanged} lines)</span>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
