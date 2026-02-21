import { useState } from 'react'
import { useMission } from '../hooks/useMission'
import { useSse } from '../hooks/useSse'
import { apiClient } from '../api/client'
import { StatusBadge } from './StatusBadge'
import { TaskTimeline } from './TaskTimeline'
import { TaskCard } from './TaskCard'
import { MetricsPanel } from './MetricsPanel'
import { EventLog } from './EventLog'
import { ApprovalPanel } from './ApprovalPanel'
import { ClarifyingQuestionsPanel } from './ClarifyingQuestionsPanel'

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

  const handleRetryTask = async (taskId: string) => {
    if (!mission) return
    setRetrying(true)
    try {
      await apiClient.retryMission(mission.mission_id, [taskId])
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
          <div className="w-5 h-5 border-2 border-wm-border border-t-agent-reviewer rounded-full animate-spin" />
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

  const hasFailedTasks = mission.tasks.some(d => d.status === 'FAILED')
  const isTerminal = mission.status === 'COMPLETED' || mission.status === 'FAILED'
  const showRetryAll = isTerminal && hasFailedTasks

  return (
    <div className="p-5 overflow-y-auto h-full overflow-x-hidden">
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
          {mission.quality_gate_granted && (
            <span className="text-emerald-400 flex items-center gap-1">
              <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
              </svg>
              quality_gate granted
            </span>
          )}
        </div>
      </div>

      {mission.status === 'CLARIFYING' && mission.clarifying_questions && (
        <ClarifyingQuestionsPanel 
          missionId={mission.mission_id}
          questions={mission.clarifying_questions}
          onRefresh={refresh}
        />
      )}

      {(mission.status === 'SPECIFYING' || mission.status === 'PLANNING') && (
        <div className="bg-wm_bg-surface border border-wm_border-subtle rounded-md p-6 mb-5">
          <div className="flex items-center gap-3">
            <div className="w-5 h-5 border-2 border-cyan-400 border-t-transparent rounded-full animate-spin" />
            <div>
              <div className="text-wm_text-secondary font-medium">
                {mission.status === 'SPECIFYING' ? 'Generating Product Requirements...' : 'Planning Mission...'}
              </div>
              <div className="text-wm_text-muted text-xs mt-1">
                {mission.status === 'SPECIFYING' 
                  ? 'Creating detailed specifications based on your requirements'
                  : 'Breaking down the specifications into tasks'}
              </div>
            </div>
          </div>
        </div>
      )}

      {mission.status === 'AWAITING_APPROVAL' && (
        <ApprovalPanel mission={mission} onRefresh={refresh} />
      )}

      {mission.metrics && <MetricsPanel metrics={mission.metrics} />}

      {mission.tasks.length > 0 && (
        <TaskTimeline tasks={mission.tasks} waveCount={mission.wave_count} />
      )}

      {mission.tasks.length > 0 && (() => {
        const codeTasks = mission.tasks.filter(t => t.agent !== 'DEPLOYER')
        const deployerTasks = mission.tasks.filter(t => t.agent === 'DEPLOYER')

        return (
          <>
            {codeTasks.length > 0 && (
              <div className="mb-5">
                <div className="text-[10px] font-mono uppercase tracking-wider text-wm_text-muted mb-3">
                  Tasks ({codeTasks.length})
                </div>
                <div className="space-y-2">
                  {codeTasks.map((task, idx) => (
                    <TaskCard
                      key={task.id}
                      task={task}
                      events={events.filter(e => e.taskId === task.id)}
                      onRetry={isTerminal ? handleRetryTask : undefined}
                      index={idx}
                      total={codeTasks.length}
                    />
                  ))}
                </div>
              </div>
            )}

            {deployerTasks.length > 0 && (
              <div className="mb-5">
                <div className="flex items-center gap-2 mb-3">
                  {/* Rocket icon */}
                  <svg className="w-3.5 h-3.5 text-agent-deployer" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M15.59 14.37a6 6 0 01-5.84 7.38v-4.8m5.84-2.58a14.98 14.98 0 006.16-12.12A14.98 14.98 0 009.631 8.41m5.96 5.96a14.926 14.926 0 01-5.841 2.58m-.119-8.54a6 6 0 00-7.381 5.84h4.8m2.58-5.84a14.927 14.927 0 00-2.58 5.84m2.699 2.7c-.103.021-.207.041-.311.06a15.09 15.09 0 01-2.448-2.448 14.9 14.9 0 01.06-.312m-2.24 2.39a4.493 4.493 0 00-6.233 0c-1.045 1.045-1.573 3.141-1.573 5.655 0 .464.015.903.044 1.317.023.33.298.588.629.599.31.01.646.015.997.015 2.514 0 4.61-.528 5.655-1.573a4.493 4.493 0 000-6.233" />
                  </svg>
                  <div className="text-[10px] font-mono uppercase tracking-wider text-agent-deployer">
                    Deployment
                  </div>
                </div>
                <div className="space-y-2">
                  {deployerTasks.map((task) => (
                    <TaskCard
                      key={task.id}
                      task={task}
                      events={events.filter(e => e.taskId === task.id)}
                      onRetry={isTerminal ? handleRetryTask : undefined}
                    />
                  ))}
                </div>
              </div>
            )}
          </>
        )
      })()}

      <EventLog events={events} connectionStatus={connectionStatus} />

      {mission.errors.length > 0 && (
        <ErrorPanel errors={mission.errors} status={mission.status} />
      )}
    </div>
  )
}
