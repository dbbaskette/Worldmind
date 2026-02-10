import { useState } from 'react'
import { MissionResponse } from '../api/types'
import { apiClient } from '../api/client'

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
    if (!confirm('Are you sure you want to cancel this mission?')) {
      return
    }

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
    <div className="bg-yellow-50 border border-yellow-300 rounded-lg p-4 mb-6">
      <div className="flex items-start justify-between mb-4">
        <div>
          <h3 className="text-lg font-semibold text-yellow-900 mb-1">
            Plan Approval Required
          </h3>
          <p className="text-sm text-yellow-700">
            Review the mission plan below and approve to begin execution.
          </p>
        </div>
      </div>

      {mission.classification && (
        <div className="bg-white rounded p-3 mb-4">
          <h4 className="text-sm font-semibold text-gray-700 mb-2">Classification</h4>
          <div className="text-sm space-y-1">
            <div><span className="font-medium">Category:</span> {mission.classification.category}</div>
            <div><span className="font-medium">Complexity:</span> {mission.classification.complexity}/10</div>
            <div><span className="font-medium">Strategy:</span> {mission.classification.planningStrategy}</div>
            {mission.classification.affectedComponents.length > 0 && (
              <div>
                <span className="font-medium">Affected:</span>{' '}
                {mission.classification.affectedComponents.join(', ')}
              </div>
            )}
          </div>
        </div>
      )}

      <div className="bg-white rounded p-3 mb-4">
        <h4 className="text-sm font-semibold text-gray-700 mb-2">
          Plan ({mission.directives.length} directives)
        </h4>
        <ul className="space-y-2">
          {mission.directives.map(directive => (
            <li key={directive.id} className="text-sm">
              <span className="font-mono text-gray-600">{directive.id}</span>
              {' '}
              <span className="font-semibold text-blue-600">[{directive.centurion}]</span>
              {' '}
              {directive.description}
            </li>
          ))}
        </ul>
      </div>

      {error && (
        <div className="text-red-600 text-sm bg-red-50 px-3 py-2 rounded mb-4">
          {error}
        </div>
      )}

      <div className="flex gap-3">
        <button
          onClick={handleApprove}
          disabled={approving || cancelling}
          className="px-4 py-2 bg-green-600 text-white rounded-md hover:bg-green-700 disabled:bg-gray-300 disabled:cursor-not-allowed transition-colors"
        >
          {approving ? 'Approving...' : 'Approve & Execute'}
        </button>

        <button
          onClick={handleCancel}
          disabled={approving || cancelling}
          className="px-4 py-2 bg-red-600 text-white rounded-md hover:bg-red-700 disabled:bg-gray-300 disabled:cursor-not-allowed transition-colors"
        >
          {cancelling ? 'Cancelling...' : 'Cancel Mission'}
        </button>
      </div>
    </div>
  )
}
