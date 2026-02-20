import { TaskResponse } from '../api/types'

interface TaskTimelineProps {
  tasks: TaskResponse[]
  waveCount?: number
}

export function TaskTimeline({ tasks, waveCount }: TaskTimelineProps) {
  if (tasks.length === 0) return null

  const total = tasks.length
  const completed = tasks.filter(d => d.status === 'FULFILLED').length
  const failed = tasks.filter(d => d.status === 'FAILED').length
  const executing = tasks.filter(d => d.status === 'EXECUTING').length
  const verifying = tasks.filter(d => d.status === 'VERIFYING').length
  const pending = total - completed - failed - executing - verifying

  const completedPct = (completed / total) * 100
  const executingPct = (executing / total) * 100
  const verifyingPct = (verifying / total) * 100
  const failedPct = (failed / total) * 100

  return (
    <div className="mb-5">
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-3">
          <span className="text-xs text-wm_text-secondary">
            <span className="font-mono text-wm_text-primary">{completed}</span>/{total} completed
          </span>
          {waveCount != null && waveCount > 0 && (
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-agent-reviewer/10 text-agent-reviewer border border-agent-reviewer/20">
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
        {verifyingPct > 0 && (
          <div className="bg-purple-500 h-full animate-researcher transition-all duration-500" style={{ width: `${verifyingPct}%` }} />
        )}
        {executingPct > 0 && (
          <div className="bg-blue-500 h-full animate-researcher transition-all duration-500" style={{ width: `${executingPct}%` }} />
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
        {verifying > 0 && (
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-purple-500 animate-researcher" /> {verifying} verifying
          </span>
        )}
        {executing > 0 && (
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-blue-500 animate-researcher" /> {executing} running
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
