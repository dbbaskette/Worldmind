import { STATUS_COLORS, AGENT_COLORS } from '../utils/constants'

interface StatusBadgeProps {
  status: string
  type?: 'mission' | 'task' | 'agent'
}

export function StatusBadge({ status, type = 'task' }: StatusBadgeProps) {
  const colors = type === 'agent'
    ? AGENT_COLORS[status as keyof typeof AGENT_COLORS]
    : STATUS_COLORS[status as keyof typeof STATUS_COLORS]

  const displayText = status.replace(/_/g, ' ')
  const isExecuting = status === 'EXECUTING'

  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[10px] font-mono font-medium uppercase tracking-wider ${colors || 'bg-wm-elevated text-wm_text-muted border border-wm-border'}`}>
      {isExecuting && (
        <span className="w-1.5 h-1.5 rounded-full bg-current animate-researcher" />
      )}
      {displayText}
    </span>
  )
}
