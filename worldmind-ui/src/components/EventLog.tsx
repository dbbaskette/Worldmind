import { useEffect, useRef } from 'react'
import { WorldmindEvent } from '../api/types'
import { EVENT_COLORS } from '../utils/constants'
import { formatTimestamp } from '../utils/formatting'

interface EventLogProps {
  events: WorldmindEvent[]
  connectionStatus: 'connected' | 'disconnected' | 'reconnecting'
}

export function EventLog({ events, connectionStatus }: EventLogProps) {
  const logEndRef = useRef<HTMLDivElement>(null)

  // Auto-scroll to latest event
  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [events])

  const statusColor = {
    connected: 'bg-green-500',
    disconnected: 'bg-red-500',
    reconnecting: 'bg-yellow-500'
  }[connectionStatus]

  return (
    <div className="bg-gray-50 rounded-lg p-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-gray-700">Event Stream</h3>
        <div className="flex items-center gap-2">
          <span className={`w-2 h-2 rounded-full ${statusColor}`} />
          <span className="text-xs text-gray-500 capitalize">{connectionStatus}</span>
        </div>
      </div>

      <div className="space-y-2 max-h-64 overflow-y-auto">
        {events.length === 0 && (
          <p className="text-xs text-gray-400 text-center py-4">
            Waiting for events...
          </p>
        )}

        {events.map((event, idx) => {
          const eventColor = EVENT_COLORS[event.eventType as keyof typeof EVENT_COLORS] || 'text-gray-600'

          return (
            <div key={idx} className="text-xs border-l-2 border-gray-300 pl-2 py-1">
              <div className="flex items-center justify-between">
                <span className={`font-semibold ${eventColor}`}>
                  {event.eventType}
                </span>
                <span className="text-gray-400">
                  {formatTimestamp(event.timestamp)}
                </span>
              </div>

              {event.directiveId && (
                <div className="text-gray-500 mt-1">
                  Directive: {event.directiveId}
                </div>
              )}

              {event.payload && Object.keys(event.payload).length > 0 && (
                <div className="text-gray-600 mt-1 font-mono text-xs bg-gray-100 px-2 py-1 rounded">
                  {JSON.stringify(event.payload, null, 2).slice(0, 200)}
                  {JSON.stringify(event.payload).length > 200 && '...'}
                </div>
              )}
            </div>
          )
        })}

        <div ref={logEndRef} />
      </div>
    </div>
  )
}
