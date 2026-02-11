import { useState, useEffect, useCallback } from 'react'
import { SseConnection } from '../api/sse'
import { WorldmindEvent } from '../api/types'

export function useSse(missionId: string | null, onRefresh?: () => void) {
  const [events, setEvents] = useState<WorldmindEvent[]>([])
  const [connectionStatus, setConnectionStatus] = useState<'connected' | 'disconnected' | 'reconnecting'>('disconnected')
  const [sseConnection] = useState(() => new SseConnection())

  const addEvent = useCallback((event: WorldmindEvent) => {
    setEvents(prev => [...prev, event])
    // Trigger mission refresh when key events occur
    if (onRefresh) {
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
      'directive.started': addEvent,
      'directive.fulfilled': addEvent,
      'directive.failed': addEvent,
      'directive.progress': addEvent,
      'directive.phase': addEvent,
      'starblaster.opened': addEvent,
      'seal.denied': addEvent,
      'seal.granted': addEvent,
      'wave.scheduled': addEvent,
      'wave.completed': addEvent,
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
