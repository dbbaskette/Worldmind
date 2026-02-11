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
    return b.mission_id.localeCompare(a.mission_id)
  })

  if (missionArray.length === 0) {
    return (
      <div className="p-6 text-center">
        <div className="w-10 h-10 rounded-full bg-wm-elevated border border-wm-border flex items-center justify-center mx-auto mb-3">
          <svg className="w-5 h-5 text-wm_text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 4v16m8-8H4" />
          </svg>
        </div>
        <p className="text-xs text-wm_text-muted">No missions yet</p>
      </div>
    )
  }

  return (
    <div className="space-y-px">
      {missionArray.map(mission => {
        const isSelected = selectedMissionId === mission.mission_id
        return (
          <button
            key={mission.mission_id}
            onClick={() => onSelect(mission.mission_id)}
            className={`w-full text-left px-3 py-3 transition-all ${
              isSelected
                ? 'bg-centurion-vigil/10 border-l-2 border-centurion-vigil'
                : 'hover:bg-wm-elevated border-l-2 border-transparent'
            }`}
          >
            <div className="flex items-center justify-between mb-1.5">
              <span className="text-[10px] font-mono text-wm_text-muted">{mission.mission_id}</span>
              <StatusBadge status={mission.status} type="mission" />
            </div>
            <p className="text-xs text-wm_text-primary leading-relaxed mb-1">
              {truncate(mission.request, 60)}
            </p>
            <div className="flex items-center justify-between text-[10px] text-wm_text-muted">
              <span className="font-mono">{mission.interaction_mode.replace(/_/g, ' ').toLowerCase()}</span>
              {mission.directives.length > 0 && (
                <span>{mission.directives.length} dir</span>
              )}
            </div>
          </button>
        )
      })}
    </div>
  )
}
