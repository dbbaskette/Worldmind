import { DirectiveResponse } from '../api/types'

interface DirectiveTimelineProps {
  directives: DirectiveResponse[]
}

export function DirectiveTimeline({ directives }: DirectiveTimelineProps) {
  if (directives.length === 0) {
    return null
  }

  const completed = directives.filter(d => d.status === 'FULFILLED').length
  const failed = directives.filter(d => d.status === 'FAILED').length
  const executing = directives.filter(d => d.status === 'EXECUTING').length
  const pending = directives.filter(d => d.status === 'PENDING').length

  const progress = (completed / directives.length) * 100

  return (
    <div className="mb-6">
      <div className="flex justify-between text-sm text-gray-600 mb-2">
        <span>Progress: {completed}/{directives.length} completed</span>
        <span>{Math.round(progress)}%</span>
      </div>

      <div className="w-full bg-gray-200 rounded-full h-3 mb-3">
        <div
          className="bg-green-500 h-3 rounded-full transition-all duration-300"
          style={{ width: `${progress}%` }}
        />
      </div>

      <div className="flex gap-4 text-xs">
        {completed > 0 && (
          <div className="flex items-center gap-1">
            <span className="w-3 h-3 bg-green-500 rounded-full" />
            <span>{completed} Fulfilled</span>
          </div>
        )}
        {executing > 0 && (
          <div className="flex items-center gap-1">
            <span className="w-3 h-3 bg-blue-500 rounded-full animate-pulse" />
            <span>{executing} Executing</span>
          </div>
        )}
        {pending > 0 && (
          <div className="flex items-center gap-1">
            <span className="w-3 h-3 bg-gray-300 rounded-full" />
            <span>{pending} Pending</span>
          </div>
        )}
        {failed > 0 && (
          <div className="flex items-center gap-1">
            <span className="w-3 h-3 bg-red-500 rounded-full" />
            <span>{failed} Failed</span>
          </div>
        )}
      </div>
    </div>
  )
}
