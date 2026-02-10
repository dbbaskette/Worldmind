import { useState, useEffect, useCallback, useRef } from 'react'
import { apiClient } from '../api/client'
import { MissionResponse } from '../api/types'

export function useMission(missionId: string | null) {
  const [mission, setMission] = useState<MissionResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const missionRef = useRef<MissionResponse | null>(null)

  // Keep ref in sync so the interval callback sees current status
  missionRef.current = mission

  const fetchMission = useCallback(async () => {
    if (!missionId) {
      setMission(null)
      return
    }

    setLoading(true)
    setError(null)

    try {
      const data = await apiClient.getMission(missionId)
      setMission(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch mission')
    } finally {
      setLoading(false)
    }
  }, [missionId])

  // Initial fetch and periodic refresh
  useEffect(() => {
    fetchMission()

    const interval = setInterval(() => {
      const current = missionRef.current
      if (current && ['CLASSIFYING', 'UPLOADING', 'PLANNING', 'EXECUTING'].includes(current.status)) {
        fetchMission()
      }
    }, 5000)

    return () => clearInterval(interval)
  }, [missionId, fetchMission])

  const refresh = useCallback(() => {
    fetchMission()
  }, [fetchMission])

  return { mission, loading, error, refresh }
}
