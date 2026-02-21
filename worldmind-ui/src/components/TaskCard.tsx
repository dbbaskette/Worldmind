import { useState, useMemo } from 'react'
import { TaskResponse, WorldmindEvent } from '../api/types'
import { StatusBadge } from './StatusBadge'
import { AGENT_ACCENT } from '../utils/constants'
import { formatDuration } from '../utils/formatting'

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
  BUILD: 'Building application',
  PUSH: 'Pushing to CF',
  VERIFY: 'Verifying health',
}

const DEPLOYER_PHASE_LABELS: Record<DeployerPhase, string> = {
  BUILD: 'Build',
  PUSH: 'Push',
  VERIFY: 'Verify',
}

interface TaskCardProps {
  task: TaskResponse
  events: WorldmindEvent[]
  onRetry?: (id: string) => void
  index?: number
  total?: number
}

function derivePhase(events: WorldmindEvent[], status: string): { active: Phase | null; completed: Set<Phase>; failed: Phase | null } {
  const completed = new Set<Phase>()
  let active: Phase | null = null
  let failed: Phase | null = null

  // First check task status to handle page refresh (no events available yet)
  // This ensures status lights show correctly even before SSE events arrive
  if (status === 'FULFILLED') {
    PHASES.forEach(p => completed.add(p))
    return { active: null, completed, failed: null }
  }
  if (status === 'VERIFYING') {
    // CODER complete, running quality gates (TESTER/REVIEWER)
    completed.add('CODER')
    return { active: 'TESTER', completed, failed: null }
  }
  if (status === 'FAILED' && events.length === 0) {
    // Without events we don't know which phase failed, show CODER as failed
    return { active: null, completed, failed: 'CODER' }
  }
  if (status === 'EXECUTING' && events.length === 0) {
    // Executing but no events yet - show CODER as active
    return { active: 'CODER', completed, failed: null }
  }
  if (status === 'PENDING') {
    // Not started yet
    return { active: null, completed, failed: null }
  }

  // Process events to get more precise phase info
  for (const event of events) {
    if (event.eventType === 'task.started') {
      active = 'CODER'
    }
    if (event.eventType === 'task.phase') {
      const phase = event.payload?.phase as Phase
      if (phase) {
        const idx = PHASES.indexOf(phase)
        for (let i = 0; i < idx; i++) completed.add(PHASES[i])
        active = phase
      }
    }
    if (event.eventType === 'quality_gate.granted') {
      PHASES.forEach(p => completed.add(p))
      active = null
    }
    if (event.eventType === 'quality_gate.denied') {
      completed.add('CODER')
      completed.add('TESTER')
      completed.add('REVIEWER')
      failed = 'QUALITY_GATE'
      active = null
    }
    if (event.eventType === 'task.failed') {
      failed = active || 'CODER'
      active = null
    }
  }

  // Final status-based overrides
  if (status === 'FULFILLED') {
    PHASES.forEach(p => completed.add(p))
    active = null
    failed = null
  }

  if (status === 'FAILED' && !failed && !active) {
    failed = 'CODER'
  }

  return { active, completed, failed }
}

function PhasePipeline({ task, events }: { task: TaskResponse; events: WorldmindEvent[] }) {
  if (task.agent !== 'CODER') return null

  const { active, completed, failed } = derivePhase(events, task.status)

  return (
    <div className="flex items-center gap-0.5 mb-3">
      {PHASES.map((phase, idx) => {
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
                    ? 'bg-blue-500/20 text-blue-400 border border-blue-500/40 animate-researcher'
                    : isFailed
                    ? 'bg-red-500/20 text-red-400 border border-red-500/40'
                    : 'bg-wm-elevated text-wm_text-muted border border-wm-border'
                }`}
              >
                {isCompleted ? '\u2713' : isFailed ? '\u2717' : (idx + 1)}
              </div>
              <span className={`text-[8px] font-mono uppercase tracking-wider ${
                isActive ? 'text-blue-400' : isFailed ? 'text-red-400' : isCompleted ? 'text-emerald-400/60' : 'text-wm_text-muted'
              }`}>
                {phase}
              </span>
            </div>
            {idx < PHASES.length - 1 && (
              <div className={`w-4 h-px mx-0.5 transition-all ${
                completed.has(PHASES[idx + 1]) || active === PHASES[idx + 1]
                  ? 'bg-emerald-500/50'
                  : 'bg-wm-border'
              }`} />
            )}
          </div>
        )
      })}

      {active && (task.status === 'EXECUTING' || task.status === 'VERIFYING') && (
        <div className="ml-3 flex items-center gap-1.5 text-[10px] text-blue-400">
          <span className="w-1 h-1 rounded-full bg-blue-400 animate-researcher" />
          {PHASE_ACTIVITY[active]}
        </div>
      )}
    </div>
  )
}

function deriveDeployerPhase(events: WorldmindEvent[], status: string): { active: DeployerPhase | null; completed: Set<DeployerPhase>; failed: DeployerPhase | null } {
  const completed = new Set<DeployerPhase>()
  let active: DeployerPhase | null = null
  let failed: DeployerPhase | null = null

  if (status === 'FULFILLED') {
    DEPLOYER_PHASES.forEach(p => completed.add(p))
    return { active: null, completed, failed: null }
  }
  if (status === 'FAILED' && events.length === 0) {
    return { active: null, completed, failed: 'BUILD' }
  }
  if (status === 'EXECUTING' && events.length === 0) {
    return { active: 'BUILD', completed, failed: null }
  }
  if (status === 'PENDING') {
    return { active: null, completed, failed: null }
  }

  for (const event of events) {
    if (event.eventType === 'task.started') {
      active = 'BUILD'
    }
    if (event.eventType === 'task.phase' || event.eventType === 'deployer.phase') {
      const phase = (event.payload?.phase as string)?.toUpperCase() as DeployerPhase
      if (phase && DEPLOYER_PHASES.includes(phase as DeployerPhase)) {
        const idx = DEPLOYER_PHASES.indexOf(phase)
        for (let i = 0; i < idx; i++) completed.add(DEPLOYER_PHASES[i])
        active = phase
      }
    }
    if (event.eventType === 'deployer.deployed' || event.eventType === 'task.fulfilled') {
      DEPLOYER_PHASES.forEach(p => completed.add(p))
      active = null
    }
    if (event.eventType === 'task.failed') {
      failed = active || 'BUILD'
      active = null
    }
  }

  if (status === 'FULFILLED') {
    DEPLOYER_PHASES.forEach(p => completed.add(p))
    active = null
    failed = null
  }

  if (status === 'FAILED' && !failed && !active) {
    failed = 'BUILD'
  }

  return { active, completed, failed }
}

function DeployerPipeline({ task, events }: { task: TaskResponse; events: WorldmindEvent[] }) {
  if (task.agent !== 'DEPLOYER') return null

  const { active, completed, failed } = deriveDeployerPhase(events, task.status)

  return (
    <div className="flex items-center gap-0.5 mb-3">
      {DEPLOYER_PHASES.map((phase, idx) => {
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
                    ? 'bg-teal-500/20 text-teal-400 border border-teal-500/40 animate-researcher'
                    : isFailed
                    ? 'bg-red-500/20 text-red-400 border border-red-500/40'
                    : 'bg-wm-elevated text-wm_text-muted border border-wm-border'
                }`}
              >
                {isCompleted ? '\u2713' : isFailed ? '\u2717' : (idx + 1)}
              </div>
              <span className={`text-[8px] font-mono uppercase tracking-wider ${
                isActive ? 'text-teal-400' : isFailed ? 'text-red-400' : isCompleted ? 'text-emerald-400/60' : 'text-wm_text-muted'
              }`}>
                {DEPLOYER_PHASE_LABELS[phase]}
              </span>
            </div>
            {idx < DEPLOYER_PHASES.length - 1 && (
              <div className={`w-4 h-px mx-0.5 transition-all ${
                completed.has(DEPLOYER_PHASES[idx + 1]) || active === DEPLOYER_PHASES[idx + 1]
                  ? 'bg-emerald-500/50'
                  : 'bg-wm-border'
              }`} />
            )}
          </div>
        )
      })}

      {active && (task.status === 'EXECUTING' || task.status === 'RUNNING') && (
        <div className="ml-3 flex items-center gap-1.5 text-[10px] text-teal-400">
          <span className="w-1 h-1 rounded-full bg-teal-400 animate-researcher" />
          {DEPLOYER_PHASE_ACTIVITY[active]}
        </div>
      )}
    </div>
  )
}

function DeploymentUrl({ events, task }: { events: WorldmindEvent[]; task: TaskResponse }) {
  const url = useMemo(() => {
    // Check for explicit deployment URL in events
    for (let i = events.length - 1; i >= 0; i--) {
      const e = events[i]
      if (e.eventType === 'deployer.deployed' && e.payload?.url) {
        return e.payload.url as string
      }
      if (e.eventType === 'task.fulfilled' && e.payload?.deploymentUrl) {
        return e.payload.deploymentUrl as string
      }
    }
    // Try to extract URL from task description (route convention: {mission-id}.apps.{domain})
    const urlPattern = /[\w-]+\.apps\.[\w.-]+/
    for (let i = events.length - 1; i >= 0; i--) {
      const e = events[i]
      if (e.payload?.output) {
        const match = (e.payload.output as string).match(urlPattern)
        if (match) return match[0]
      }
    }
    // Check task description for route
    const descMatch = task.description?.match(urlPattern)
    if (descMatch) return descMatch[0]
    return null
  }, [events, task.description])

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

function DeployerRetryInfo({ task }: { task: TaskResponse }) {
  if (task.agent !== 'DEPLOYER') return null
  if (task.max_iterations <= 1) return null

  const attempt = task.iteration + 1
  const isFinal = task.status === 'FAILED' && attempt >= task.max_iterations

  return (
    <span className={`font-mono ${isFinal ? 'text-red-400' : 'text-amber-400/70'}`}>
      deploy attempt {attempt}/{task.max_iterations}
    </span>
  )
}

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
