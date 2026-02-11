export interface MissionResponse {
  mission_id: string
  status: string
  request: string
  interaction_mode: string
  execution_strategy: string
  classification: Classification | null
  directives: DirectiveResponse[]
  seal_granted: boolean
  metrics: MissionMetrics | null
  errors: string[]
  wave_count: number
}

export interface DirectiveResponse {
  id: string
  centurion: string
  description: string
  status: string
  iteration: number
  max_iterations: number
  elapsed_ms: number | null
  files_affected: FileRecord[]
  on_failure: string | null
  review_score: number | null
  review_summary: string | null
}

export interface FileRecord {
  action: string
  path: string
  linesChanged: number
}

export interface Classification {
  category: string
  complexity: number
  affectedComponents: string[]
  planningStrategy: string
}

export interface MissionMetrics {
  totalDurationMs: number
  directivesCompleted: number
  directivesFailed: number
  totalIterations: number
  filesCreated: number
  filesModified: number
  testsRun: number
  testsPassed: number
  wavesExecuted: number
  aggregateDurationMs: number
}

export interface WorldmindEvent {
  eventType: string
  missionId: string
  directiveId?: string
  payload: Record<string, any>
  timestamp: string
}

export interface TimelineEntry {
  checkpoint: string
  threadId: string
  timestamp: string
  state: Record<string, any>
}

export type MissionStatus =
  | 'CLASSIFYING'
  | 'UPLOADING'
  | 'SPECIFYING'
  | 'PLANNING'
  | 'AWAITING_APPROVAL'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'FAILED'

export type DirectiveStatus =
  | 'PENDING'
  | 'EXECUTING'
  | 'FULFILLED'
  | 'FAILED'
  | 'SKIPPED'

export type InteractionMode =
  | 'FULL_AUTO'
  | 'APPROVE_PLAN'
  | 'STEP_BY_STEP'

export interface McpSettingsResponse {
  enabled: boolean
  servers: McpServerInfo[]
}

export interface McpServerInfo {
  name: string
  url: string
  status: 'UP' | 'DOWN'
  consumers: McpConsumerInfo[]
}

export interface McpConsumerInfo {
  name: string
  hasToken: boolean
  tools: McpToolInfo[]
}

export interface McpToolInfo {
  name: string
  description: string
}
