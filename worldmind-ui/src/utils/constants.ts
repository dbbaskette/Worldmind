// Status colors for Tailwind CSS classes
export const STATUS_COLORS = {
  PENDING: 'bg-gray-200 text-gray-700',
  EXECUTING: 'bg-blue-500 text-white animate-pulse',
  FULFILLED: 'bg-green-500 text-white',
  FAILED: 'bg-red-500 text-white',
  SKIPPED: 'bg-yellow-500 text-black',
  CLASSIFYING: 'bg-purple-500 text-white',
  UPLOADING: 'bg-indigo-500 text-white',
  PLANNING: 'bg-blue-600 text-white',
  AWAITING_APPROVAL: 'bg-orange-500 text-white',
  COMPLETED: 'bg-green-600 text-white',
} as const

// Centurion colors
export const CENTURION_COLORS = {
  FORGE: 'bg-centurion-forge text-white',
  GAUNTLET: 'bg-centurion-gauntlet text-white',
  VIGIL: 'bg-centurion-vigil text-white',
  PULSE: 'bg-centurion-pulse text-white',
  PRISM: 'bg-centurion-prism text-white',
} as const

// Event type colors for log entries
export const EVENT_COLORS = {
  'mission.created': 'text-purple-600',
  'directive.started': 'text-blue-600',
  'directive.fulfilled': 'text-green-600',
  'directive.failed': 'text-red-600',
  'directive.progress': 'text-gray-600',
  'stargate.opened': 'text-indigo-600',
  'seal.denied': 'text-orange-600',
  'seal.granted': 'text-green-600',
  'wave.scheduled': 'text-cyan-600',
  'wave.completed': 'text-teal-600',
} as const

// Interaction modes
export const INTERACTION_MODES = [
  { value: 'FULL_AUTO', label: 'Full Auto' },
  { value: 'APPROVE_PLAN', label: 'Approve Plan' },
  { value: 'STEP_BY_STEP', label: 'Step by Step' },
] as const

// Auto-refresh interval (ms)
export const MISSION_REFRESH_INTERVAL = 5000
