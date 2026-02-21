// Status colors — dark theme
export const STATUS_COLORS = {
  PENDING: 'bg-wm-elevated text-wm_text-muted border border-wm-border',
  EXECUTING: 'bg-status-info/20 text-blue-400 border border-blue-500/30',
  VERIFYING: 'bg-purple-500/20 text-purple-400 border border-purple-500/30',  // Quality gates in progress
  FULFILLED: 'bg-status-success/20 text-emerald-400 border border-emerald-500/30',
  FAILED: 'bg-status-error/20 text-red-400 border border-red-500/30',
  SKIPPED: 'bg-status-warning/20 text-amber-400 border border-amber-500/30',
  CLASSIFYING: 'bg-purple-500/20 text-purple-400 border border-purple-500/30',
  UPLOADING: 'bg-indigo-500/20 text-indigo-400 border border-indigo-500/30',
  CLARIFYING: 'bg-agent-researcher/20 text-agent-researcher border border-agent-researcher/30',
  SPECIFYING: 'bg-cyan-500/20 text-cyan-400 border border-cyan-500/30',
  PLANNING: 'bg-blue-500/20 text-blue-400 border border-blue-500/30',
  AWAITING_APPROVAL: 'bg-amber-500/20 text-amber-400 border border-amber-500/30',
  COMPLETED: 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30',
  CANCELLED: 'bg-wm-elevated text-wm_text-muted border border-wm-border',
} as const

// Agent badge colors
export const AGENT_COLORS = {
  CODER: 'bg-agent-coder/20 text-agent-coder border border-agent-coder/30',
  TESTER: 'bg-agent-tester/20 text-agent-tester border border-agent-tester/30',
  REVIEWER: 'bg-agent-reviewer/20 text-agent-reviewer border border-agent-reviewer/30',
  RESEARCHER: 'bg-agent-researcher/20 text-agent-researcher border border-agent-researcher/30',
  REFACTORER: 'bg-agent-refactorer/20 text-agent-refactorer border border-agent-refactorer/30',
  DEPLOYER: 'bg-agent-deployer/20 text-agent-deployer border border-agent-deployer/30',
} as const

// Agent solid accent colors for left-border tints
export const AGENT_ACCENT: Record<string, string> = {
  CODER: '#FF6B35',
  TESTER: '#C084FC',
  REVIEWER: '#818CF8',
  RESEARCHER: '#22D3EE',
  REFACTORER: '#F472B6',
  DEPLOYER: '#2DD4BF',
}

// Event type colors for log entries
export const EVENT_COLORS = {
  'mission.created': 'text-purple-400',
  'task.started': 'text-blue-400',
  'task.fulfilled': 'text-emerald-400',
  'task.failed': 'text-red-400',
  'task.progress': 'text-wm_text-secondary',
  'task.phase': 'text-indigo-400',
  'sandbox.opened': 'text-cyan-400',
  'quality_gate.denied': 'text-amber-400',
  'quality_gate.granted': 'text-emerald-400',
  'wave.scheduled': 'text-cyan-400',
  'wave.completed': 'text-teal-400',
  'deployer.phase': 'text-teal-400',
  'deployer.deployed': 'text-emerald-400',
} as const

// Interaction modes
export const INTERACTION_MODES = [
  { value: 'FULL_AUTO', label: 'Full Auto' },
  { value: 'APPROVE_PLAN', label: 'Approve Plan' },
  { value: 'STEP_BY_STEP', label: 'Step by Step' },
] as const

// Auto-refresh interval (ms)
export const MISSION_REFRESH_INTERVAL = 5000
