import { useState } from 'react'
import { useMission } from '../hooks/useMission'
import { useSse } from '../hooks/useSse'
import { StatusBadge } from './StatusBadge'
import { DirectiveTimeline } from './DirectiveTimeline'
import { DirectiveCard } from './DirectiveCard'
import { MetricsPanel } from './MetricsPanel'
import { EventLog } from './EventLog'
import { ApprovalPanel } from './ApprovalPanel'

interface MissionDetailProps {
  missionId: string
}

function ErrorPanel({ errors, status }: { errors: string[]; status: string }) {
  const isOldCompleted = status === 'COMPLETED'
  const [expanded, setExpanded] = useState(!isOldCompleted)

  return (
    <div className={`mt-6 rounded-lg border p-4 ${
      isOldCompleted ? 'bg-gray-50 border-gray-200' : 'bg-red-50 border-red-300'
    }`}>
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center justify-between w-full text-left"
      >
        <h3 className={`text-sm font-semibold ${isOldCompleted ? 'text-gray-600' : 'text-red-900'}`}>
          Errors ({errors.length})
        </h3>
        <svg
          className={`w-4 h-4 transition-transform ${expanded ? 'rotate-180' : ''} ${
            isOldCompleted ? 'text-gray-400' : 'text-red-400'
          }`}
          fill="none" stroke="currentColor" viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>
      {expanded && (
        <ul className={`mt-2 space-y-1 text-sm ${isOldCompleted ? 'text-gray-600' : 'text-red-700'}`}>
          {errors.map((error, idx) => (
            <li key={idx}>{error}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

export function MissionDetail({ missionId }: MissionDetailProps) {
  const { mission, loading, error, refresh } = useMission(missionId)
  const { events, connectionStatus } = useSse(missionId, refresh)

  if (loading && !mission) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600 mx-auto mb-4" />
          <p className="text-gray-600">Loading mission...</p>
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center text-red-600">
          <p className="text-lg font-semibold mb-2">Error</p>
          <p>{error}</p>
        </div>
      </div>
    )
  }

  if (!mission) {
    return (
      <div className="flex items-center justify-center h-full">
        <p className="text-gray-500">Select a mission to view details</p>
      </div>
    )
  }

  return (
    <div className="p-6 overflow-y-auto h-full">
      {/* Header */}
      <div className="mb-6">
        <div className="flex items-center justify-between mb-2">
          <h2 className="text-xl font-bold text-gray-900">
            Mission {mission.mission_id}
          </h2>
          <StatusBadge status={mission.status} type="mission" />
        </div>

        <p className="text-gray-700 mb-4">{mission.request}</p>

        <div className="flex gap-4 text-sm text-gray-600">
          <div>
            <span className="font-medium">Mode:</span>{' '}
            {mission.interaction_mode.replace(/_/g, ' ')}
          </div>
          <div>
            <span className="font-medium">Strategy:</span>{' '}
            {mission.execution_strategy}
          </div>
          {mission.seal_granted && (
            <div className="text-green-600 font-medium">
              ✓ Seal of Approval Granted
            </div>
          )}
        </div>
      </div>

      {/* Approval Panel (conditional) */}
      {mission.status === 'AWAITING_APPROVAL' && (
        <ApprovalPanel mission={mission} onRefresh={refresh} />
      )}

      {/* Metrics Panel */}
      {mission.metrics && (
        <div className="mb-6">
          <MetricsPanel metrics={mission.metrics} />
        </div>
      )}

      {/* Directive Timeline */}
      {mission.directives.length > 0 && (
        <DirectiveTimeline directives={mission.directives} />
      )}

      {/* Directives */}
      {mission.directives.length > 0 && (
        <div className="mb-6">
          <h3 className="text-sm font-semibold text-gray-700 mb-3">
            Directives ({mission.directives.length})
          </h3>
          <div className="space-y-3">
            {mission.directives.map(directive => (
              <DirectiveCard key={directive.id} directive={directive} />
            ))}
          </div>
        </div>
      )}

      {/* Event Log */}
      <EventLog events={events} connectionStatus={connectionStatus} />

      {/* Errors — expanded for active missions, collapsed for terminal ones */}
      {mission.errors.length > 0 && (
        <ErrorPanel errors={mission.errors} status={mission.status} />
      )}
    </div>
  )
}
