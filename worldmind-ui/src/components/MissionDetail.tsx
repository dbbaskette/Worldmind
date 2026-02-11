import { useState } from 'react'
import { useMission } from '../hooks/useMission'
import { useSse } from '../hooks/useSse'
import { apiClient } from '../api/client'
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
    <div className={`mt-4 rounded-lg border p-3 ${
      isOldCompleted ? 'bg-wm-card border-wm-border' : 'bg-red-500/5 border-red-500/20'
    }`}>
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center justify-between w-full text-left"
      >
        <span className={`text-xs font-mono ${isOldCompleted ? 'text-wm_text-muted' : 'text-red-400'}`}>
          {errors.length} error{errors.length !== 1 ? 's' : ''}
        </span>
        <svg
          className={`w-3 h-3 transition-transform ${expanded ? 'rotate-180' : ''} ${
            isOldCompleted ? 'text-wm_text-dim' : 'text-red-400/60'
          }`}
          fill="none" stroke="currentColor" viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>
      {expanded && (
        <ul className={`mt-2 space-y-1 text-xs font-mono ${isOldCompleted ? 'text-wm_text-muted' : 'text-red-400/80'}`}>
          {errors.map((error, idx) => (
            <li key={idx} className="leading-relaxed">{error}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

export function MissionDetail({ missionId }: MissionDetailProps) {
  const { mission, loading, error, refresh } = useMission(missionId)
  const { events, connectionStatus } = useSse(missionId, refresh)
  const [retrying, setRetrying] = useState(false)

  const handleRetryDirective = async (directiveId: string) => {
    if (!mission) return
    setRetrying(true)
    try {
      await apiClient.retryMission(mission.mission_id, [directiveId])
      refresh()
    } catch (err) {
      console.error('Retry failed:', err)
    } finally {
      setRetrying(false)
    }
  }

  const handleRetryAllFailed = async () => {
    if (!mission) return
    setRetrying(true)
    try {
      await apiClient.retryMission(mission.mission_id)
      refresh()
    } catch (err) {
      console.error('Retry all failed:', err)
    } finally {
      setRetrying(false)
    }
  }

  if (loading && !mission) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="flex items-center gap-3 text-wm_text-muted">
          <div className="w-5 h-5 border-2 border-wm-border border-t-centurion-vigil rounded-full animate-spin" />
          <span className="text-xs font-mono">Loading mission...</span>
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <div className="text-red-400 text-xs font-mono mb-1">ERROR</div>
          <p className="text-sm text-wm_text-secondary">{error}</p>
        </div>
      </div>
    )
  }

  if (!mission) {
    return (
      <div className="flex items-center justify-center h-full">
        <p className="text-xs text-wm_text-muted font-mono">Select a mission</p>
      </div>
    )
  }

  const hasFailedDirectives = mission.directives.some(d => d.status === 'FAILED')
  const isTerminal = mission.status === 'COMPLETED' || mission.status === 'FAILED'
  const showRetryAll = isTerminal && hasFailedDirectives

  return (
    <div className="p-5 overflow-y-auto h-full">
      {/* Header */}
      <div className="mb-5">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-3">
            <h2 className="text-sm font-mono font-semibold text-wm_text-primary">
              {mission.mission_id}
            </h2>
            <StatusBadge status={mission.status} type="mission" />
          </div>
          {showRetryAll && (
            <button
              onClick={handleRetryAllFailed}
              disabled={retrying}
              className="px-3 py-1.5 bg-amber-500/10 text-amber-400 border border-amber-500/30 rounded-lg text-[10px] font-mono hover:bg-amber-500/20 disabled:opacity-30 transition-colors"
            >
              {retrying ? 'retrying...' : 'retry failed'}
            </button>
          )}
        </div>

        <p className="text-sm text-wm_text-secondary leading-relaxed mb-3">{mission.request}</p>

        <div className="flex items-center gap-4 text-[10px] font-mono text-wm_text-muted">
          <span>mode: {mission.interaction_mode.replace(/_/g, ' ').toLowerCase()}</span>
          <span>strategy: {mission.execution_strategy.toLowerCase()}</span>
          {mission.seal_granted && (
            <span className="text-emerald-400 flex items-center gap-1">
              <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
              </svg>
              seal granted
            </span>
          )}
        </div>
      </div>

      {mission.status === 'AWAITING_APPROVAL' && (
        <ApprovalPanel mission={mission} onRefresh={refresh} />
      )}

      {mission.metrics && <MetricsPanel metrics={mission.metrics} />}

      {mission.directives.length > 0 && (
        <DirectiveTimeline directives={mission.directives} waveCount={mission.wave_count} />
      )}

      {mission.directives.length > 0 && (
        <div className="mb-5">
          <div className="text-[10px] font-mono uppercase tracking-wider text-wm_text-muted mb-3">
            Directives ({mission.directives.length})
          </div>
          <div className="space-y-2">
            {mission.directives.map(directive => (
              <DirectiveCard
                key={directive.id}
                directive={directive}
                events={events.filter(e => e.directiveId === directive.id)}
                onRetry={isTerminal ? handleRetryDirective : undefined}
              />
            ))}
          </div>
        </div>
      )}

      <EventLog events={events} connectionStatus={connectionStatus} />

      {mission.errors.length > 0 && (
        <ErrorPanel errors={mission.errors} status={mission.status} />
      )}
    </div>
  )
}
