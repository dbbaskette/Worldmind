// Status colors — dark theme
export const STATUS_COLORS = {
  PENDING: 'bg-wm-elevated text-wm_text-muted border border-wm-border',
  EXECUTING: 'bg-status-info/20 text-blue-400 border border-blue-500/30',
  FULFILLED: 'bg-status-success/20 text-emerald-400 border border-emerald-500/30',
  FAILED: 'bg-status-error/20 text-red-400 border border-red-500/30',
  SKIPPED: 'bg-status-warning/20 text-amber-400 border border-amber-500/30',
  CLASSIFYING: 'bg-purple-500/20 text-purple-400 border border-purple-500/30',
  UPLOADING: 'bg-indigo-500/20 text-indigo-400 border border-indigo-500/30',
  SPECIFYING: 'bg-cyan-500/20 text-cyan-400 border border-cyan-500/30',
  PLANNING: 'bg-blue-500/20 text-blue-400 border border-blue-500/30',
  AWAITING_APPROVAL: 'bg-amber-500/20 text-amber-400 border border-amber-500/30',
  COMPLETED: 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30',
  CANCELLED: 'bg-wm-elevated text-wm_text-muted border border-wm-border',
} as const

// Centurion badge colors
export const CENTURION_COLORS = {
  FORGE: 'bg-centurion-forge/20 text-centurion-forge border border-centurion-forge/30',
  GAUNTLET: 'bg-centurion-gauntlet/20 text-centurion-gauntlet border border-centurion-gauntlet/30',
  VIGIL: 'bg-centurion-vigil/20 text-centurion-vigil border border-centurion-vigil/30',
  PULSE: 'bg-centurion-pulse/20 text-centurion-pulse border border-centurion-pulse/30',
  PRISM: 'bg-centurion-prism/20 text-centurion-prism border border-centurion-prism/30',
} as const

// Centurion solid accent colors for left-border tints
export const CENTURION_ACCENT: Record<string, string> = {
  FORGE: '#FF6B35',
  GAUNTLET: '#C084FC',
  VIGIL: '#818CF8',
  PULSE: '#22D3EE',
  PRISM: '#F472B6',
}

// Event type colors for log entries
export const EVENT_COLORS = {
  'mission.created': 'text-purple-400',
  'directive.started': 'text-blue-400',
  'directive.fulfilled': 'text-emerald-400',
  'directive.failed': 'text-red-400',
  'directive.progress': 'text-wm_text-secondary',
  'directive.phase': 'text-indigo-400',
  'starblaster.opened': 'text-cyan-400',
  'seal.denied': 'text-amber-400',
  'seal.granted': 'text-emerald-400',
  'wave.scheduled': 'text-cyan-400',
  'wave.completed': 'text-teal-400',
} as const

// Interaction modes
export const INTERACTION_MODES = [
  { value: 'FULL_AUTO', label: 'Full Auto' },
  { value: 'APPROVE_PLAN', label: 'Approve Plan' },
  { value: 'STEP_BY_STEP', label: 'Step by Step' },
] as const

// Auto-refresh interval (ms)
export const MISSION_REFRESH_INTERVAL = 5000
