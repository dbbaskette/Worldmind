import { useEffect, useRef } from 'react'
import { WorldmindEvent } from '../api/types'
import { EVENT_COLORS } from '../utils/constants'

interface EventLogProps {
  events: WorldmindEvent[]
  connectionStatus: 'connected' | 'disconnected' | 'reconnecting'
}

export function EventLog({ events, connectionStatus }: EventLogProps) {
  const logEndRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [events])

  const statusIndicator = {
    connected: 'bg-emerald-500',
    disconnected: 'bg-red-500',
    reconnecting: 'bg-amber-500 animate-researcher',
  }[connectionStatus]

  return (
    <div className="bg-wm-bg rounded-lg border border-wm-border overflow-hidden">
      <div className="flex items-center justify-between px-3 py-2 border-b border-wm-border bg-wm-card">
        <span className="text-[10px] font-mono uppercase tracking-wider text-wm_text-muted">Event Stream</span>
        <div className="flex items-center gap-1.5">
          <span className={`w-1.5 h-1.5 rounded-full ${statusIndicator}`} />
          <span className="text-[10px] font-mono text-wm_text-muted">{connectionStatus}</span>
        </div>
      </div>

      <div className="max-h-48 overflow-y-auto p-2 scan-lines font-mono text-[11px] space-y-0.5">
        {events.length === 0 && (
          <div className="text-wm_text-muted text-center py-6">
            <span className="cursor-blink">_</span> awaiting events...
          </div>
        )}

        {events.map((event, idx) => {
          const color = EVENT_COLORS[event.eventType as keyof typeof EVENT_COLORS] || 'text-wm_text-muted'
          const time = new Date(event.timestamp).toLocaleTimeString('en-US', {
            hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'
          })

          return (
            <div key={idx} className="flex gap-2 py-0.5 hover:bg-wm-surface/50 px-1 rounded">
              <span className="text-wm_text-muted shrink-0">{time}</span>
              <span className={`shrink-0 ${color}`}>{event.eventType}</span>
              {event.taskId && (
                <span className="text-wm_text-muted">{event.taskId}</span>
              )}
              {event.payload && Object.keys(event.payload).length > 0 && (
                <span className="text-wm_text-muted truncate">
                  {Object.entries(event.payload).map(([k, v]) => `${k}=${v}`).join(' ')}
                </span>
              )}
            </div>
          )
        })}

        <div ref={logEndRef} />
      </div>
    </div>
  )
}
