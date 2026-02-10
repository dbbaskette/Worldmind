import { MissionResponse } from '../api/types'
import { StatusBadge } from './StatusBadge'
import { truncate } from '../utils/formatting'

interface MissionListProps {
  missions: Map<string, MissionResponse>
  selectedMissionId: string | null
  onSelect: (missionId: string) => void
}

export function MissionList({ missions, selectedMissionId, onSelect }: MissionListProps) {
  const missionArray = Array.from(missions.values()).sort((a, b) => {
    // Sort by most recent first (assuming mission_id includes timestamp or sequential ordering)
    return b.mission_id.localeCompare(a.mission_id)
  })

  if (missionArray.length === 0) {
    return (
      <div className="p-4 text-center text-gray-500">
        <p className="mb-2">No missions yet</p>
        <p className="text-sm">Submit a mission to get started</p>
      </div>
    )
  }

  return (
    <div className="divide-y divide-gray-200">
      {missionArray.map(mission => (
        <button
          key={mission.mission_id}
          onClick={() => onSelect(mission.mission_id)}
          className={`w-full text-left p-4 hover:bg-gray-50 transition-colors ${
            selectedMissionId === mission.mission_id ? 'bg-blue-50 border-l-4 border-blue-600' : ''
          }`}
        >
          <div className="flex items-start justify-between mb-2">
            <span className="text-xs font-mono text-gray-500">{mission.mission_id}</span>
            <StatusBadge status={mission.status} type="mission" />
          </div>

          <p className="text-sm text-gray-900 mb-2">
            {truncate(mission.request, 80)}
          </p>

          <div className="flex items-center justify-between text-xs text-gray-500">
            <span>{mission.interaction_mode.replace(/_/g, ' ')}</span>
            {mission.metrics && (
              <span>{mission.directives.length} directives</span>
            )}
          </div>
        </button>
      ))}
    </div>
  )
}
