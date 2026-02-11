import { DirectiveResponse } from '../api/types'

interface DirectiveTimelineProps {
  directives: DirectiveResponse[]
  waveCount?: number
}

export function DirectiveTimeline({ directives, waveCount }: DirectiveTimelineProps) {
  if (directives.length === 0) return null

  const total = directives.length
  const completed = directives.filter(d => d.status === 'FULFILLED').length
  const failed = directives.filter(d => d.status === 'FAILED').length
  const executing = directives.filter(d => d.status === 'EXECUTING').length
  const pending = total - completed - failed - executing

  const completedPct = (completed / total) * 100
  const executingPct = (executing / total) * 100
  const failedPct = (failed / total) * 100

  return (
    <div className="mb-5">
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-3">
          <span className="text-xs text-wm_text-secondary">
            <span className="font-mono text-wm_text-primary">{completed}</span>/{total} completed
          </span>
          {waveCount != null && waveCount > 0 && (
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-centurion-vigil/10 text-centurion-vigil border border-centurion-vigil/20">
              WAVE {waveCount}
            </span>
          )}
        </div>
        <span className="text-xs font-mono text-wm_text-muted">{Math.round(completedPct)}%</span>
      </div>

      <div className="w-full bg-wm-elevated rounded-full h-1.5 flex overflow-hidden">
        {completedPct > 0 && (
          <div className="bg-emerald-500 h-full transition-all duration-500" style={{ width: `${completedPct}%` }} />
        )}
        {executingPct > 0 && (
          <div className="bg-blue-500 h-full animate-pulse transition-all duration-500" style={{ width: `${executingPct}%` }} />
        )}
        {failedPct > 0 && (
          <div className="bg-red-500 h-full transition-all duration-500" style={{ width: `${failedPct}%` }} />
        )}
      </div>

      <div className="flex gap-4 mt-2 text-[10px] text-wm_text-muted">
        {completed > 0 && (
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-emerald-500" /> {completed} done
          </span>
        )}
        {executing > 0 && (
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-blue-500 animate-pulse" /> {executing} running
          </span>
        )}
        {pending > 0 && (
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-wm-border-light" /> {pending} pending
          </span>
        )}
        {failed > 0 && (
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-red-500" /> {failed} failed
          </span>
        )}
      </div>
    </div>
  )
}
