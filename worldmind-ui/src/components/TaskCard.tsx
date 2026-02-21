import { useState, useMemo } from 'react'
import { TaskResponse, WorldmindEvent } from '../api/types'
import { StatusBadge } from './StatusBadge'
import { AGENT_ACCENT } from '../utils/constants'
import { formatDuration } from '../utils/formatting'

/* ── Phase definitions ─────────────────────────────────────────────── */

const PHASES = ['CODER', 'TESTER', 'REVIEWER', 'QUALITY_GATE'] as const
type Phase = typeof PHASES[number]

const PHASE_ACTIVITY: Record<Phase, string> = {
  CODER: 'Generating code',
  TESTER: 'Running tests',
  REVIEWER: 'Reviewing code',
  QUALITY_GATE: 'Evaluating quality_gate',
}

const DEPLOYER_PHASES = ['BUILD', 'PUSH', 'VERIFY'] as const
type DeployerPhase = typeof DEPLOYER_PHASES[number]

const DEPLOYER_PHASE_ACTIVITY: Record<DeployerPhase, string> = {
  BUILD: 'Deploying to CF\u2026',
  PUSH: 'Pushing to CF',
  VERIFY: 'Verifying health',
}

const DEPLOYER_PHASE_LABELS: Record<DeployerPhase, string> = {
  BUILD: 'Build',
  PUSH: 'Push',
  VERIFY: 'Verify',
}

/* ── Generic phase-state derivation ────────────────────────────────── */

type PhaseState<T extends string> = {
  active: T | null
  completed: Set<T>
  failed: T | null
}

/**
 * Shared phase-derivation logic for CODER and DEPLOYER pipelines.
 * Status-based early returns (FULFILLED, PENDING, etc.) are handled here;
 * event-specific logic is delegated to the processEvent callback.
 */
function derivePhaseState<T extends string>(
  phases: readonly T[],
  events: WorldmindEvent[],
  status: string,
  processEvent: (event: WorldmindEvent, state: PhaseState<T>, phases: readonly T[]) => void,
  extraStatusCheck?: (status: string) => PhaseState<T> | null,
): PhaseState<T> {
  const defaultPhase = phases[0]

  if (status === 'FULFILLED') {
    return { active: null, completed: new Set(phases), failed: null }
  }
  if (extraStatusCheck) {
    const override = extraStatusCheck(status)
    if (override) return override
  }
  if (status === 'FAILED' && events.length === 0) {
    return { active: null, completed: new Set(), failed: defaultPhase }
  }
  if (status === 'EXECUTING' && events.length === 0) {
    return { active: defaultPhase, completed: new Set(), failed: null }
  }
  if (status === 'PENDING') {
    return { active: null, completed: new Set(), failed: null }
  }

  const state: PhaseState<T> = { active: null, completed: new Set(), failed: null }
  for (const event of events) {
    processEvent(event, state, phases)
  }

  if (status === 'FAILED' && !state.failed && !state.active) {
    state.failed = defaultPhase
  }
  return state
}

function deriveCoderPhase(events: WorldmindEvent[], status: string) {
  return derivePhaseState<Phase>(
    PHASES,
    events,
    status,
    (event, state, phases) => {
      if (event.eventType === 'task.started') {
        state.active = 'CODER'
      }
      if (event.eventType === 'task.phase') {
        const phase = event.payload?.phase as Phase
        if (phase) {
          const idx = phases.indexOf(phase)
          for (let i = 0; i < idx; i++) state.completed.add(phases[i])
          state.active = phase
        }
      }
      if (event.eventType === 'quality_gate.granted') {
        phases.forEach(p => state.completed.add(p))
        state.active = null
      }
      if (event.eventType === 'quality_gate.denied') {
        state.completed.add('CODER')
        state.completed.add('TESTER')
        state.completed.add('REVIEWER')
        state.failed = 'QUALITY_GATE'
        state.active = null
      }
      if (event.eventType === 'task.failed') {
        state.failed = state.active || 'CODER'
        state.active = null
      }
    },
    (status) => {
      if (status === 'VERIFYING') {
        const completed = new Set<Phase>()
        completed.add('CODER')
        return { active: 'TESTER' as Phase, completed, failed: null }
      }
      return null
    },
  )
}

function deriveDeployerPhase(events: WorldmindEvent[], status: string) {
  return derivePhaseState<DeployerPhase>(
    DEPLOYER_PHASES,
    events,
    status,
    (event, state, phases) => {
      if (event.eventType === 'task.started') {
        state.active = 'BUILD'
      }
      if (event.eventType === 'task.phase' || event.eventType === 'deployer.phase') {
        const phase = (event.payload?.phase as string)?.toUpperCase() as DeployerPhase
        if (phase && (phases as readonly string[]).includes(phase)) {
          const idx = phases.indexOf(phase)
          for (let i = 0; i < idx; i++) state.completed.add(phases[i])
          state.active = phase
        }
      }
      if (event.eventType === 'deployer.deployed' || event.eventType === 'task.fulfilled') {
        phases.forEach(p => state.completed.add(p))
        state.active = null
      }
      if (event.eventType === 'task.failed') {
        state.failed = state.active || 'BUILD'
        state.active = null
      }
    },
  )
}

/* ── Generic pipeline component ────────────────────────────────────── */

interface PipelineColorConfig {
  bg: string
  text: string
  border: string
  dot: string
}

interface PipelineConfig<T extends string> {
  phases: readonly T[]
  labels: Record<T, string>
  activityText: Record<T, string>
  activeColor: PipelineColorConfig
}

const CODER_PIPELINE_CONFIG: PipelineConfig<Phase> = {
  phases: PHASES,
  labels: { CODER: 'CODER', TESTER: 'TESTER', REVIEWER: 'REVIEWER', QUALITY_GATE: 'Q_GATE' },
  activityText: PHASE_ACTIVITY,
  activeColor: {
    bg: 'bg-blue-500/20', text: 'text-blue-400', border: 'border-blue-500/40', dot: 'bg-blue-400',
  },
}

const DEPLOYER_PIPELINE_CONFIG: PipelineConfig<DeployerPhase> = {
  phases: DEPLOYER_PHASES,
  labels: DEPLOYER_PHASE_LABELS,
  activityText: DEPLOYER_PHASE_ACTIVITY,
  activeColor: {
    bg: 'bg-teal-500/20', text: 'text-teal-400', border: 'border-teal-500/40', dot: 'bg-teal-400',
  },
}

function PhaseStepsPipeline<T extends string>({
  config,
  state,
  status,
}: {
  config: PipelineConfig<T>
  state: PhaseState<T>
  status: string
}) {
  const { phases, labels, activityText, activeColor } = config
  const { active, completed, failed } = state

  return (
    <div className="flex items-center gap-0.5 mb-3">
      {phases.map((phase, idx) => {
        const isCompleted = completed.has(phase)
        const isActive = active === phase
        const isFailed = failed === phase

        return (
          <div key={phase} className="flex items-center">
            <div className="flex flex-col items-center gap-0.5">
              <div
                className={`w-5 h-5 rounded-md flex items-center justify-center text-[9px] font-bold transition-all ${
                  isCompleted
                    ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/40'
                    : isActive
                    ? `${activeColor.bg} ${activeColor.text} border ${activeColor.border} animate-glow-pulse`
                    : isFailed
                    ? 'bg-red-500/20 text-red-400 border border-red-500/40'
                    : 'bg-wm-elevated text-wm_text-muted border border-wm-border'
                }`}
              >
                {isCompleted ? '\u2713' : isFailed ? '\u2717' : (idx + 1)}
              </div>
              <span className={`text-[8px] font-mono uppercase tracking-wider ${
                isActive ? activeColor.text : isFailed ? 'text-red-400' : isCompleted ? 'text-emerald-400/60' : 'text-wm_text-muted'
              }`}>
                {labels[phase]}
              </span>
            </div>
            {idx < phases.length - 1 && (
              <div className={`w-4 h-px mx-0.5 transition-all ${
                completed.has(phases[idx + 1]) || active === phases[idx + 1]
                  ? 'bg-emerald-500/50'
                  : 'bg-wm-border'
              }`} />
            )}
          </div>
        )
      })}

      {active && (status === 'EXECUTING' || status === 'VERIFYING' || status === 'RUNNING') && (
        <div className={`ml-3 flex items-center gap-1.5 text-[10px] ${activeColor.text}`}>
          <span className={`w-1 h-1 rounded-full ${activeColor.dot} animate-glow-pulse`} />
          {activityText[active]}
        </div>
      )}
    </div>
  )
}

function PhasePipeline({ task, events }: { task: TaskResponse; events: WorldmindEvent[] }) {
  if (task.agent !== 'CODER') return null
  const state = deriveCoderPhase(events, task.status)
  return <PhaseStepsPipeline config={CODER_PIPELINE_CONFIG} state={state} status={task.status} />
}

function DeployerPipeline({ task, events }: { task: TaskResponse; events: WorldmindEvent[] }) {
  if (task.agent !== 'DEPLOYER') return null
  const state = deriveDeployerPhase(events, task.status)
  return <PhaseStepsPipeline config={DEPLOYER_PIPELINE_CONFIG} state={state} status={task.status} />
}

/* ── Deployment URL display ────────────────────────────────────────── */

function DeploymentUrl({ events, task }: { events: WorldmindEvent[]; task: TaskResponse }) {
  const url = useMemo(() => {
    // Prefer explicit deployment URL from structured events
    for (let i = events.length - 1; i >= 0; i--) {
      const e = events[i]
      if (e.eventType === 'deployer.deployed' && e.payload?.url) {
        return e.payload.url as string
      }
      if (e.eventType === 'task.fulfilled' && e.payload?.deploymentUrl) {
        return e.payload.deploymentUrl as string
      }
    }
    // Fallback: scan event output for CF CLI route markers (e.g. "routes: app.apps.domain")
    const cfRoutePattern = /(?:routes?:|urls?:)\s*([\w-]+\.apps\.[\w.-]+)/i
    for (let i = events.length - 1; i >= 0; i--) {
      const e = events[i]
      if (e.payload?.output) {
        const match = (e.payload.output as string).match(cfRoutePattern)
        if (match) return match[1]
      }
    }
    return null
  }, [events])

  if (!url || task.status !== 'FULFILLED') return null

  const displayUrl = url.replace(/^https?:\/\//, '')
  const href = url.startsWith('http') ? url : `https://${url}`

  return (
    <div className="mt-2 bg-emerald-500/10 border border-emerald-500/20 rounded px-2.5 py-2 flex items-center gap-2">
      {/* Link icon */}
      <svg className="w-3.5 h-3.5 text-emerald-400 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M13.19 8.688a4.5 4.5 0 011.242 7.244l-4.5 4.5a4.5 4.5 0 01-6.364-6.364l1.757-1.757m9.07-9.07l-1.757 1.757a4.5 4.5 0 010 6.364l4.5-4.5a4.5 4.5 0 00-6.364-6.364" />
      </svg>
      <span className="text-[11px] text-emerald-400 font-medium">Deployed:</span>
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        className="text-[11px] font-mono text-emerald-400 hover:text-emerald-300 underline underline-offset-2 truncate transition-colors"
      >
        {displayUrl}
      </a>
    </div>
  )
}

/* ── Deployer retry info ───────────────────────────────────────────── */

function DeployerRetryInfo({ task }: { task: TaskResponse }) {
  if (task.agent !== 'DEPLOYER') return null

  const attempt = task.iteration + 1
  const maxIter = task.max_iterations

  // Only suppress for data anomalies (0 or negative)
  if (maxIter <= 0) return null

  const isFinal = task.status === 'FAILED' && attempt >= maxIter

  return (
    <span className={`font-mono ${isFinal ? 'text-red-400' : 'text-amber-400/70'}`}>
      deploy attempt {attempt}/{maxIter}
    </span>
  )
}

/* ── Failure reason display ────────────────────────────────────────── */

function FailureReason({ events }: { events: WorldmindEvent[] }) {
  const failedEvent = events.find(e => e.eventType === 'task.failed' && e.payload?.reason)
  if (!failedEvent) return null

  const reason = failedEvent.payload?.reason as string
  const agentOutput = failedEvent.payload?.agentOutput as string | undefined
  const [showOutput, setShowOutput] = useState(false)

  return (
    <div className="mt-2 bg-red-500/5 border border-red-500/20 rounded px-2.5 py-2">
      <p className="text-[11px] text-red-400 font-medium">{reason}</p>
      {agentOutput && (
        <>
          <button
            onClick={() => setShowOutput(!showOutput)}
            className="mt-1 text-[10px] font-mono text-red-400/70 hover:text-red-400 transition-colors"
          >
            {showOutput ? '\u25BE' : '\u25B8'} agent output
          </button>
          {showOutput && (
            <pre className="mt-1.5 text-[10px] font-mono text-wm_text-secondary bg-wm-bg rounded p-2 max-h-60 overflow-auto whitespace-pre-wrap break-words">
              {agentOutput}
            </pre>
          )}
        </>
      )}
    </div>
  )
}

/* ── Quality score badge ───────────────────────────────────────────── */

function QualityScore({ score, summary }: { score: number | null; summary: string | null }) {
  if (score == null) return null

  const color = score >= 7 ? 'text-emerald-400' : score >= 4 ? 'text-amber-400' : 'text-red-400'
  const bg = score >= 7 ? 'bg-emerald-500/10 border-emerald-500/20' : score >= 4 ? 'bg-amber-500/10 border-amber-500/20' : 'bg-red-500/10 border-red-500/20'

  return (
    <div className={`inline-flex items-center gap-1.5 ${bg} border rounded px-1.5 py-0.5`} title={summary || undefined}>
      <span className={`text-[10px] font-mono font-semibold ${color}`}>{score}/10</span>
      {summary && <span className="text-[10px] text-wm_text-muted truncate max-w-[180px]">{summary}</span>}
    </div>
  )
}

/* ── Main TaskCard ─────────────────────────────────────────────────── */

interface TaskCardProps {
  task: TaskResponse
  events: WorldmindEvent[]
  onRetry?: (id: string) => void
  index?: number
  total?: number
}

export function TaskCard({ task, events, onRetry, index, total }: TaskCardProps) {
  const [expanded, setExpanded] = useState(false)
  const accent = AGENT_ACCENT[task.agent] || '#6B7280'

  const liveScore = useMemo(() => {
    if (task.review_score != null) return { score: task.review_score, summary: task.review_summary }
    for (let i = events.length - 1; i >= 0; i--) {
      const e = events[i]
      if (e.eventType === 'quality_gate.granted' || e.eventType === 'quality_gate.denied') {
        if (e.payload?.score != null) {
          return { score: e.payload.score as number, summary: (e.payload.summary as string) || null }
        }
      }
    }
    return { score: null, summary: null }
  }, [task.review_score, task.review_summary, events])

  return (
    <div
      className="bg-wm-card rounded-lg border border-wm-border hover:border-wm-border-light transition-all animate-fade-in"
      style={{ borderLeftColor: accent, borderLeftWidth: '2px' }}
    >
      <div className="p-3">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            {index != null && total != null && (
              <span className="text-[10px] font-mono bg-wm-elevated text-wm_text-secondary px-1.5 py-0.5 rounded">
                {index + 1}/{total}
              </span>
            )}
            <span className="text-[11px] font-mono text-wm_text-muted">{task.id}</span>
            <StatusBadge status={task.agent} type="agent" />
          </div>
          <div className="flex items-center gap-2">
            {task.status === 'FAILED' && onRetry && (
              <button
                onClick={() => onRetry(task.id)}
                className="px-2 py-0.5 bg-amber-500/10 text-amber-400 border border-amber-500/30 rounded text-[10px] font-mono hover:bg-amber-500/20 transition-colors"
              >
                retry
              </button>
            )}
            <StatusBadge status={task.status} type="task" />
          </div>
        </div>

        <p className="text-xs text-wm_text-secondary leading-relaxed mb-2">{task.description}</p>

        <PhasePipeline task={task} events={events} />
        <DeployerPipeline task={task} events={events} />
        <FailureReason events={events} />
        {task.agent === 'DEPLOYER' && <DeploymentUrl events={events} task={task} />}

        <div className="flex items-center gap-3 text-[10px] text-wm_text-muted flex-wrap">
          {task.agent === 'DEPLOYER' ? (
            <DeployerRetryInfo task={task} />
          ) : (
            <span className="font-mono">iter {task.iteration + 1}/{task.max_iterations}</span>
          )}
          {task.elapsed_ms && (
            <span className="font-mono">{formatDuration(task.elapsed_ms)}</span>
          )}
          {task.on_failure && (
            <span className="font-mono text-amber-400/70">on_fail:{task.on_failure.toLowerCase()}</span>
          )}
          <QualityScore score={liveScore.score} summary={liveScore.summary} />
        </div>

        {task.files_affected && task.files_affected.length > 0 && (
          <div className="mt-2">
            <button
              onClick={() => setExpanded(!expanded)}
              className="text-[10px] font-mono text-agent-reviewer/70 hover:text-agent-reviewer transition-colors"
            >
              {expanded ? '\u25BE' : '\u25B8'} {task.files_affected.length} files
            </button>

            {expanded && (
              <div className="mt-1.5 space-y-0.5 animate-fade-in">
                {task.files_affected.map((file, idx) => (
                  <div key={idx} className="text-[10px] font-mono text-wm_text-muted bg-wm-bg rounded px-2 py-0.5 flex items-center gap-2">
                    <span className="text-agent-tester">{file.action}</span>
                    <span className="text-wm_text-muted truncate">{file.path}</span>
                    {file.linesChanged > 0 && (
                      <span className="text-wm_text-muted shrink-0">+{file.linesChanged}</span>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Review feedback details - shown when score is low or expanded */}
        {(task.review_issues?.length || task.review_suggestions?.length) && (
          <ReviewFeedbackPanel
            issues={task.review_issues}
            suggestions={task.review_suggestions}
            score={task.review_score}
          />
        )}
      </div>
    </div>
  )
}

function ReviewFeedbackPanel({
  issues,
  suggestions,
  score
}: {
  issues: string[] | null
  suggestions: string[] | null
  score: number | null
}) {
  const [expanded, setExpanded] = useState(score != null && score < 7)

  if (!issues?.length && !suggestions?.length) return null

  return (
    <div className="mt-2 border-t border-wm-border pt-2">
      <button
        onClick={() => setExpanded(!expanded)}
        className="text-[10px] font-mono text-amber-400/70 hover:text-amber-400 transition-colors flex items-center gap-1"
      >
        {expanded ? '\u25BE' : '\u25B8'} Review Details
        {issues?.length ? <span className="text-red-400/70">({issues.length} issues)</span> : null}
      </button>

      {expanded && (
        <div className="mt-2 space-y-2 animate-fade-in">
          {issues && issues.length > 0 && (
            <div>
              <div className="text-[10px] font-semibold text-red-400 mb-1">Issues:</div>
              <ul className="space-y-0.5">
                {issues.map((issue, idx) => (
                  <li key={idx} className="text-[10px] text-wm_text-secondary pl-2 border-l-2 border-red-500/30">
                    {issue}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {suggestions && suggestions.length > 0 && (
            <div>
              <div className="text-[10px] font-semibold text-blue-400 mb-1">Suggestions:</div>
              <ul className="space-y-0.5">
                {suggestions.map((suggestion, idx) => (
                  <li key={idx} className="text-[10px] text-wm_text-secondary pl-2 border-l-2 border-blue-500/30">
                    {suggestion}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
