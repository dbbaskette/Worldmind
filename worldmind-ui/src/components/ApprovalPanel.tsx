import { useState } from 'react'
import { MissionResponse } from '../api/types'
import { apiClient } from '../api/client'
import { CENTURION_ACCENT } from '../utils/constants'

interface ApprovalPanelProps {
  mission: MissionResponse
  onRefresh: () => void
}

export function ApprovalPanel({ mission, onRefresh }: ApprovalPanelProps) {
  const [approving, setApproving] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleApprove = async () => {
    setApproving(true)
    setError(null)
    try {
      await apiClient.approveMission(mission.mission_id)
      onRefresh()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to approve mission')
    } finally {
      setApproving(false)
    }
  }

  const handleCancel = async () => {
    if (!confirm('Are you sure you want to cancel this mission?')) return
    setCancelling(true)
    setError(null)
    try {
      await apiClient.cancelMission(mission.mission_id)
      onRefresh()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to cancel mission')
    } finally {
      setCancelling(false)
    }
  }

  return (
    <div className="bg-amber-500/5 border border-amber-500/20 rounded-lg p-4 mb-5 animate-fade-in">
      <div className="flex items-center gap-2 mb-3">
        <div className="w-2 h-2 rounded-full bg-amber-500 animate-pulse" />
        <h3 className="text-sm font-semibold text-amber-400">Awaiting Approval</h3>
      </div>

      {mission.classification && (
        <div className="bg-wm-surface rounded-lg p-3 mb-3 border border-wm-border">
          <div className="grid grid-cols-2 gap-2 text-xs">
            <div>
              <span className="text-wm_text-muted">Category:</span>{' '}
              <span className="text-wm_text-secondary">{mission.classification.category}</span>
            </div>
            <div>
              <span className="text-wm_text-muted">Complexity:</span>{' '}
              <span className="text-wm_text-secondary font-mono">{mission.classification.complexity}/10</span>
            </div>
            <div>
              <span className="text-wm_text-muted">Strategy:</span>{' '}
              <span className="text-wm_text-secondary">{mission.classification.planningStrategy}</span>
            </div>
            {mission.classification.affectedComponents.length > 0 && (
              <div>
                <span className="text-wm_text-muted">Affects:</span>{' '}
                <span className="text-wm_text-secondary">{mission.classification.affectedComponents.join(', ')}</span>
              </div>
            )}
          </div>
        </div>
      )}

      <div className="bg-wm-surface rounded-lg p-3 mb-4 border border-wm-border">
        <div className="text-[10px] font-mono uppercase tracking-wider text-wm_text-muted mb-2">
          Execution Plan ({mission.directives.length} directives)
        </div>
        <div className="space-y-1.5">
          {mission.directives.map((d, idx) => {
            const accent = CENTURION_ACCENT[d.centurion] || '#6B7280'
            return (
              <div key={d.id} className="flex items-start gap-2 text-xs">
                <span className="font-mono text-wm_text-muted w-4 shrink-0 text-right">{idx + 1}.</span>
                <span
                  className="font-mono text-[10px] px-1 py-0.5 rounded shrink-0"
                  style={{ backgroundColor: `${accent}20`, color: accent }}
                >
                  {d.centurion}
                </span>
                <span className="text-wm_text-secondary">{d.description}</span>
              </div>
            )
          })}
        </div>
      </div>

      {error && (
        <div className="text-xs text-red-400 bg-red-500/10 border border-red-500/20 px-3 py-1.5 rounded mb-3">
          {error}
        </div>
      )}

      <div className="flex gap-2">
        <button
          onClick={handleApprove}
          disabled={approving || cancelling}
          className="px-4 py-2 bg-emerald-600 text-white rounded-lg text-xs font-medium hover:bg-emerald-500 disabled:opacity-30 disabled:cursor-not-allowed transition-all"
        >
          {approving ? 'Approving...' : 'Approve & Execute'}
        </button>
        <button
          onClick={handleCancel}
          disabled={approving || cancelling}
          className="px-4 py-2 bg-wm-elevated text-wm_text-muted rounded-lg text-xs font-medium hover:text-red-400 hover:bg-red-500/10 disabled:opacity-30 disabled:cursor-not-allowed transition-all border border-wm-border"
        >
          {cancelling ? 'Cancelling...' : 'Cancel'}
        </button>
      </div>
    </div>
  )
}
