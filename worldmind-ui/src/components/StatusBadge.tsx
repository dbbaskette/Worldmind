import { STATUS_COLORS, CENTURION_COLORS } from '../utils/constants'

interface StatusBadgeProps {
  status: string
  type?: 'mission' | 'directive' | 'centurion'
}

export function StatusBadge({ status, type = 'directive' }: StatusBadgeProps) {
  const colors = type === 'centurion'
    ? CENTURION_COLORS[status as keyof typeof CENTURION_COLORS]
    : STATUS_COLORS[status as keyof typeof STATUS_COLORS]

  const displayText = status.replace(/_/g, ' ')
  const isExecuting = status === 'EXECUTING'

  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[10px] font-mono font-medium uppercase tracking-wider ${colors || 'bg-wm-elevated text-wm_text-muted border border-wm-border'}`}>
      {isExecuting && (
        <span className="w-1.5 h-1.5 rounded-full bg-current animate-pulse" />
      )}
      {displayText}
    </span>
  )
}
