import { useState, useEffect, useCallback } from 'react'
import { SseConnection } from '../api/sse'
import { WorldmindEvent } from '../api/types'

// Only refresh mission state on events that represent a real state change.
// Progress and phase events are high-frequency and don't change mission structure,
// so refreshing on every one causes rapid re-renders that visually scramble the sidebar.
const REFRESH_TRIGGERING_EVENTS = new Set([
  'mission.created',
  'mission.completed',
  'task.started',
  'task.fulfilled',
  'task.failed',
  'quality_gate.denied',
  'quality_gate.granted',
  'wave.completed',
  'wave.merged',
  'deployer.success',
  'deployer.failed',
])

export function useSse(missionId: string | null, onRefresh?: () => void) {
  const [events, setEvents] = useState<WorldmindEvent[]>([])
  const [connectionStatus, setConnectionStatus] = useState<'connected' | 'disconnected' | 'reconnecting'>('disconnected')
  const [sseConnection] = useState(() => new SseConnection())

  const addEvent = useCallback((event: WorldmindEvent) => {
    setEvents(prev => [...prev, event])
    // Only trigger a mission refresh for state-changing events, not high-frequency
    // progress/phase events which would cause layout thrashing on the sidebar.
    if (onRefresh && REFRESH_TRIGGERING_EVENTS.has(event.eventType)) {
      onRefresh()
    }
  }, [onRefresh])

  useEffect(() => {
    if (!missionId) {
      setEvents([])
      setConnectionStatus('disconnected')
      return
    }

    // Event handlers for different event types
    const handlers = {
      'mission.created': addEvent,
      'mission.completed': addEvent,
      'task.started': addEvent,
      'task.fulfilled': addEvent,
      'task.failed': addEvent,
      'task.progress': addEvent,
      'task.phase': addEvent,
      'sandbox.opened': addEvent,
      'quality_gate.denied': addEvent,
      'quality_gate.granted': addEvent,
      'wave.scheduled': addEvent,
      'wave.completed': addEvent,
      'wave.merged': addEvent,
      'deployer.success': addEvent,
      'deployer.failed': addEvent,
    }

    sseConnection.connect(missionId, handlers)

    // Update connection status
    const statusInterval = setInterval(() => {
      setConnectionStatus(sseConnection.getStatus())
    }, 1000)

    return () => {
      clearInterval(statusInterval)
      sseConnection.disconnect()
    }
  }, [missionId, sseConnection, addEvent])

  return { events, connectionStatus }
}
