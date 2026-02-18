import { useState, useCallback, useEffect } from 'react'
import { apiClient } from '../api/client'
import { MissionResponse } from '../api/types'

export function useMissionList() {
  const [missions, setMissions] = useState<Map<string, MissionResponse>>(new Map())
  const [selectedMissionId, setSelectedMissionId] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  // Load existing missions from the backend on mount
  useEffect(() => {
    apiClient.listMissions().then(list => {
      if (list.length > 0) {
        const map = new Map<string, MissionResponse>()
        for (const m of list) {
          map.set(m.mission_id, m)
        }
        setMissions(map)
      }
    }).catch(() => { /* ignore — backend may be unreachable on first load */ })
  }, [])

  const submitMission = useCallback(async (request: string, mode: string, projectPath?: string, gitRemoteUrl?: string, reasoningLevel?: string, executionStrategy?: string, createCfDeployment?: boolean): Promise<string> => {
    setSubmitting(true)
    setSubmitError(null)

    try {
      const result = await apiClient.submitMission(request, mode, projectPath, gitRemoteUrl, reasoningLevel, executionStrategy, createCfDeployment)
      const missionId = result.mission_id

      // Add placeholder mission to list
      const placeholderMission: MissionResponse = {
        mission_id: missionId,
        status: result.status,
        request,
        interaction_mode: mode,
        execution_strategy: executionStrategy || 'PARALLEL',
        classification: null,
        product_spec: null,
        directives: [],
        seal_granted: false,
        metrics: null,
        errors: [],
        wave_count: 0
      }

      setMissions(prev => new Map(prev).set(missionId, placeholderMission))
      setSelectedMissionId(missionId)

      return missionId
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to submit mission'
      setSubmitError(errorMessage)
      throw err
    } finally {
      setSubmitting(false)
    }
  }, [])

  const selectMission = useCallback((missionId: string) => {
    setSelectedMissionId(missionId)
  }, [])

  const updateMission = useCallback((mission: MissionResponse) => {
    setMissions(prev => new Map(prev).set(mission.mission_id, mission))
  }, [])

  const removeMission = useCallback((missionId: string) => {
    setMissions(prev => {
      const newMap = new Map(prev)
      newMap.delete(missionId)
      return newMap
    })
    if (selectedMissionId === missionId) {
      setSelectedMissionId(null)
    }
  }, [selectedMissionId])

  return {
    missions,
    selectedMissionId,
    submitting,
    submitError,
    submitMission,
    selectMission,
    updateMission,
    removeMission
  }
}
