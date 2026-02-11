import { WorldmindEvent } from './types'

type EventHandler = (event: WorldmindEvent) => void

export class SseConnection {
  private eventSource: EventSource | null = null
  private missionId: string | null = null
  private handlers: Record<string, EventHandler> = {}
  private status: 'connected' | 'disconnected' | 'reconnecting' = 'disconnected'
  private reconnectAttempts = 0
  private maxReconnectAttempts = 5

  connect(missionId: string, handlers: Record<string, EventHandler>): void {
    this.missionId = missionId
    this.handlers = handlers
    this.createEventSource()
  }

  private createEventSource(): void {
    if (!this.missionId) return

    this.eventSource = new EventSource(`/api/v1/missions/${this.missionId}/events`)

    this.eventSource.onopen = () => {
      this.status = 'connected'
      this.reconnectAttempts = 0
      console.log(`SSE connected to mission ${this.missionId}`)
    }

    // Register all event type listeners
    const eventTypes = [
      'mission.created',
      'directive.started',
      'directive.fulfilled',
      'directive.progress',
      'directive.failed',
      'starblaster.opened',
      'seal.denied',
      'seal.granted',
      'wave.scheduled',
      'wave.completed',
      'directive.phase'
    ]

    eventTypes.forEach(eventType => {
      this.eventSource?.addEventListener(eventType, (e: MessageEvent) => {
        try {
          const data: WorldmindEvent = JSON.parse(e.data)
          const handler = this.handlers[eventType]
          if (handler) {
            handler(data)
          }
        } catch (error) {
          console.error(`Failed to parse SSE event ${eventType}:`, error)
        }
      })
    })

    this.eventSource.onerror = (error) => {
      console.error('SSE connection error:', error)
      this.status = 'reconnecting'

      // Auto-reconnect with exponential backoff
      if (this.reconnectAttempts < this.maxReconnectAttempts) {
        this.reconnectAttempts++
        const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000)

        console.log(`Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`)

        setTimeout(() => {
          this.disconnect()
          this.createEventSource()
        }, delay)
      } else {
        console.error('Max reconnection attempts reached')
        this.status = 'disconnected'
      }
    }
  }

  disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close()
      this.eventSource = null
    }
    this.status = 'disconnected'
  }

  getStatus(): 'connected' | 'disconnected' | 'reconnecting' {
    return this.status
  }
}
