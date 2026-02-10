import { useState, useCallback } from 'react'
import { apiClient } from '../api/client'
import { MissionResponse } from '../api/types'

export function useMissionList() {
  const [missions, setMissions] = useState<Map<string, MissionResponse>>(new Map())
  const [selectedMissionId, setSelectedMissionId] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const submitMission = useCallback(async (request: string, mode: string, projectPath?: string): Promise<string> => {
    setSubmitting(true)
    setSubmitError(null)

    try {
      const result = await apiClient.submitMission(request, mode, projectPath)
      const missionId = result.mission_id

      // Add placeholder mission to list
      const placeholderMission: MissionResponse = {
        mission_id: missionId,
        status: result.status,
        request,
        interaction_mode: mode,
        execution_strategy: 'SEQUENTIAL',
        classification: null,
        directives: [],
        seal_granted: false,
        metrics: null,
        errors: []
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
