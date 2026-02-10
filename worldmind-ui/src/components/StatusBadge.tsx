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

  return (
    <span className={`px-2 py-1 rounded text-xs font-semibold ${colors || 'bg-gray-300 text-gray-800'}`}>
      {displayText}
    </span>
  )
}
