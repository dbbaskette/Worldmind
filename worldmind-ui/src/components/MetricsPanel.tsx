import { MissionMetrics } from '../api/types'
import { formatDuration, formatNumber } from '../utils/formatting'

interface MetricsPanelProps {
  metrics: MissionMetrics | null
}

export function MetricsPanel({ metrics }: MetricsPanelProps) {
  if (!metrics) {
    return null
  }

  const metricsData = [
    { label: 'Total Duration', value: formatDuration(metrics.totalDurationMs) },
    { label: 'Directives Completed', value: formatNumber(metrics.directivesCompleted) },
    { label: 'Directives Failed', value: formatNumber(metrics.directivesFailed) },
    { label: 'Total Iterations', value: formatNumber(metrics.totalIterations) },
    { label: 'Files Created', value: formatNumber(metrics.filesCreated) },
    { label: 'Files Modified', value: formatNumber(metrics.filesModified) },
    { label: 'Tests Run', value: formatNumber(metrics.testsRun) },
    { label: 'Tests Passed', value: formatNumber(metrics.testsPassed) },
    { label: 'Waves Executed', value: formatNumber(metrics.wavesExecuted) },
  ]

  return (
    <div className="bg-white border rounded-lg p-4">
      <h3 className="text-sm font-semibold text-gray-700 mb-3">Mission Metrics</h3>

      <div className="grid grid-cols-3 gap-4">
        {metricsData.map(({ label, value }) => (
          <div key={label}>
            <div className="text-2xl font-bold text-gray-900">{value}</div>
            <div className="text-xs text-gray-500">{label}</div>
          </div>
        ))}
      </div>
    </div>
  )
}
