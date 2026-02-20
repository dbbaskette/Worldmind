import { MissionMetrics } from '../api/types'
import { formatDuration, formatNumber } from '../utils/formatting'

interface MetricsPanelProps {
  metrics: MissionMetrics | null
}

export function MetricsPanel({ metrics }: MetricsPanelProps) {
  if (!metrics) return null

  const items = [
    { label: 'Duration', value: formatDuration(metrics.totalDurationMs) },
    { label: 'Completed', value: formatNumber(metrics.tasksCompleted) },
    { label: 'Failed', value: formatNumber(metrics.tasksFailed), warn: metrics.tasksFailed > 0 },
    { label: 'Iterations', value: formatNumber(metrics.totalIterations) },
    { label: 'Files', value: formatNumber(metrics.filesCreated + metrics.filesModified) },
    { label: 'Tests', value: `${formatNumber(metrics.testsPassed)}/${formatNumber(metrics.testsRun)}` },
    { label: 'Waves', value: formatNumber(metrics.wavesExecuted) },
  ]

  return (
    <div className="flex items-center gap-5 py-3 px-4 bg-wm-card rounded-lg border border-wm-border mb-5 overflow-x-auto">
      {items.map(({ label, value, warn }, idx) => (
        <div key={label} className="flex items-center gap-5">
          <div className="shrink-0">
            <div className={`text-sm font-mono font-semibold ${warn ? 'text-red-400' : 'text-wm_text-primary'}`}>
              {value}
            </div>
            <div className="text-[10px] text-wm_text-muted uppercase tracking-wider">{label}</div>
          </div>
          {idx < items.length - 1 && <div className="w-px h-6 bg-wm-border shrink-0" />}
        </div>
      ))}
    </div>
  )
}
