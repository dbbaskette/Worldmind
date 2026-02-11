import { useState, useMemo } from 'react'
import { DirectiveResponse, WorldmindEvent } from '../api/types'
import { StatusBadge } from './StatusBadge'
import { CENTURION_ACCENT } from '../utils/constants'
import { formatDuration } from '../utils/formatting'

const PHASES = ['FORGE', 'GAUNTLET', 'VIGIL', 'SEAL'] as const
type Phase = typeof PHASES[number]

const PHASE_ACTIVITY: Record<Phase, string> = {
  FORGE: 'Generating code',
  GAUNTLET: 'Running tests',
  VIGIL: 'Reviewing code',
  SEAL: 'Evaluating seal',
}

interface DirectiveCardProps {
  directive: DirectiveResponse
  events: WorldmindEvent[]
  onRetry?: (id: string) => void
}

function derivePhase(events: WorldmindEvent[], status: string): { active: Phase | null; completed: Set<Phase>; failed: Phase | null } {
  const completed = new Set<Phase>()
  let active: Phase | null = null
  let failed: Phase | null = null

  for (const event of events) {
    if (event.eventType === 'directive.started') {
      active = 'FORGE'
    }
    if (event.eventType === 'directive.phase') {
      const phase = event.payload?.phase as Phase
      if (phase) {
        const idx = PHASES.indexOf(phase)
        for (let i = 0; i < idx; i++) completed.add(PHASES[i])
        active = phase
      }
    }
    if (event.eventType === 'seal.granted') {
      PHASES.forEach(p => completed.add(p))
      active = null
    }
    if (event.eventType === 'seal.denied') {
      completed.add('FORGE')
      completed.add('GAUNTLET')
      completed.add('VIGIL')
      failed = 'SEAL'
      active = null
    }
    if (event.eventType === 'directive.failed') {
      failed = active || 'FORGE'
      active = null
    }
  }

  if (status === 'FULFILLED') {
    PHASES.forEach(p => completed.add(p))
    active = null
    failed = null
  }

  if (status === 'FAILED' && !failed && !active) {
    failed = 'FORGE'
  }

  return { active, completed, failed }
}

function PhasePipeline({ directive, events }: { directive: DirectiveResponse; events: WorldmindEvent[] }) {
  if (directive.centurion !== 'FORGE') return null

  const { active, completed, failed } = derivePhase(events, directive.status)

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
                    ? 'bg-blue-500/20 text-blue-400 border border-blue-500/40 animate-pulse'
                    : isFailed
                    ? 'bg-red-500/20 text-red-400 border border-red-500/40'
                    : 'bg-wm-elevated text-wm_text-dim border border-wm-border'
                }`}
              >
                {isCompleted ? '\u2713' : isFailed ? '\u2717' : (idx + 1)}
              </div>
              <span className={`text-[8px] font-mono uppercase tracking-wider ${
                isActive ? 'text-blue-400' : isFailed ? 'text-red-400' : isCompleted ? 'text-emerald-400/60' : 'text-wm_text-dim'
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

      {active && directive.status === 'EXECUTING' && (
        <div className="ml-3 flex items-center gap-1.5 text-[10px] text-blue-400">
          <span className="w-1 h-1 rounded-full bg-blue-400 animate-pulse" />
          {PHASE_ACTIVITY[active]}
        </div>
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

export function DirectiveCard({ directive, events, onRetry }: DirectiveCardProps) {
  const [expanded, setExpanded] = useState(false)
  const accent = CENTURION_ACCENT[directive.centurion] || '#6B7280'

  const liveScore = useMemo(() => {
    if (directive.review_score != null) return { score: directive.review_score, summary: directive.review_summary }
    for (let i = events.length - 1; i >= 0; i--) {
      const e = events[i]
      if (e.eventType === 'seal.granted' || e.eventType === 'seal.denied') {
        if (e.payload?.score != null) {
          return { score: e.payload.score as number, summary: (e.payload.summary as string) || null }
        }
      }
    }
    return { score: null, summary: null }
  }, [directive.review_score, directive.review_summary, events])

  return (
    <div
      className="bg-wm-card rounded-lg border border-wm-border hover:border-wm-border-light transition-all animate-fade-in"
      style={{ borderLeftColor: accent, borderLeftWidth: '2px' }}
    >
      <div className="p-3">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            <span className="text-[11px] font-mono text-wm_text-muted">{directive.id}</span>
            <StatusBadge status={directive.centurion} type="centurion" />
          </div>
          <div className="flex items-center gap-2">
            {directive.status === 'FAILED' && onRetry && (
              <button
                onClick={() => onRetry(directive.id)}
                className="px-2 py-0.5 bg-amber-500/10 text-amber-400 border border-amber-500/30 rounded text-[10px] font-mono hover:bg-amber-500/20 transition-colors"
              >
                retry
              </button>
            )}
            <StatusBadge status={directive.status} type="directive" />
          </div>
        </div>

        <p className="text-xs text-wm_text-secondary leading-relaxed mb-2">{directive.description}</p>

        <PhasePipeline directive={directive} events={events} />

        <div className="flex items-center gap-3 text-[10px] text-wm_text-muted flex-wrap">
          <span className="font-mono">iter {directive.iteration}/{directive.max_iterations}</span>
          {directive.elapsed_ms && (
            <span className="font-mono">{formatDuration(directive.elapsed_ms)}</span>
          )}
          {directive.on_failure && (
            <span className="font-mono text-amber-400/70">on_fail:{directive.on_failure.toLowerCase()}</span>
          )}
          <QualityScore score={liveScore.score} summary={liveScore.summary} />
        </div>

        {directive.files_affected && directive.files_affected.length > 0 && (
          <div className="mt-2">
            <button
              onClick={() => setExpanded(!expanded)}
              className="text-[10px] font-mono text-centurion-vigil/70 hover:text-centurion-vigil transition-colors"
            >
              {expanded ? '\u25BE' : '\u25B8'} {directive.files_affected.length} files
            </button>

            {expanded && (
              <div className="mt-1.5 space-y-0.5 animate-fade-in">
                {directive.files_affected.map((file, idx) => (
                  <div key={idx} className="text-[10px] font-mono text-wm_text-muted bg-wm-bg rounded px-2 py-0.5 flex items-center gap-2">
                    <span className="text-centurion-gauntlet">{file.action}</span>
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
      </div>
    </div>
  )
}
