import { useState, useCallback } from 'react'
import { apiClient } from '../api/client'

interface Question {
  id: string
  question: string
  category: string
  why_asking?: string
  suggested_options?: string[]
  required: boolean
  default_answer?: string
}

interface ClarifyingQuestions {
  questions: Question[]
  summary: string
}

interface ClarifyingQuestionsPanelProps {
  missionId: string
  questions: ClarifyingQuestions
  onRefresh: () => void
}

interface ServiceBinding {
  id: string
  type: string
  instanceName: string
  enabled: boolean
  detected: boolean
}

const SERVICE_DISPLAY_NAMES: Record<string, string> = {
  postgresql: 'PostgreSQL Database',
  mysql: 'MySQL Database',
  mongodb: 'MongoDB Database',
  redis: 'Redis Cache',
  rabbitmq: 'RabbitMQ Message Queue',
  s3: 'S3/Blob Storage',
}

const AVAILABLE_SERVICE_TYPES = Object.keys(SERVICE_DISPLAY_NAMES)

const CF_SERVICE_BINDINGS_ID = 'cf_service_bindings'

/** Parse detected services from the question's suggested options */
function parseDetectedServices(suggestedOptions?: string[]): ServiceBinding[] {
  if (!suggestedOptions) return []
  const bindings: ServiceBinding[] = []
  for (const opt of suggestedOptions) {
    const match = opt.match(/^(\w+): <instance-name>$/)
    if (match) {
      bindings.push({
        id: crypto.randomUUID(),
        type: match[1],
        instanceName: '',
        enabled: true,
        detected: true,
      })
    }
  }
  return bindings
}

/** Format service bindings as JSON array string for the answer payload */
function formatServiceBindingsAnswer(bindings: ServiceBinding[]): string {
  const enabled = bindings.filter(b => b.enabled)
  if (enabled.length === 0) return 'No services needed'
  return JSON.stringify(
    enabled.map(b => ({ type: b.type, instanceName: b.instanceName }))
  )
}

let nextId = 0
function generateId(): string {
  return `svc-${Date.now()}-${nextId++}`
}

const categoryColors: Record<string, string> = {
  scope: 'bg-blue-500/10 text-blue-400 border-blue-500/30',
  technical: 'bg-purple-500/10 text-purple-400 border-purple-500/30',
  ux: 'bg-pink-500/10 text-pink-400 border-pink-500/30',
  integration: 'bg-cyan-500/10 text-cyan-400 border-cyan-500/30',
  constraints: 'bg-amber-500/10 text-amber-400 border-amber-500/30',
}

function ServiceBindingEditor({
  question,
  onChange,
}: {
  question: Question
  onChange: (value: string) => void
}) {
  const [bindings, setBindings] = useState<ServiceBinding[]>(() => {
    const detected = parseDetectedServices(question.suggested_options)
    if (detected.length === 0) {
      // No auto-detected services — start with empty list
      return []
    }
    return detected
  })

  const updateBindings = useCallback(
    (updater: (prev: ServiceBinding[]) => ServiceBinding[]) => {
      setBindings(prev => {
        const next = updater(prev)
        onChange(formatServiceBindingsAnswer(next))
        return next
      })
    },
    [onChange]
  )

  const toggleBinding = useCallback(
    (id: string) => {
      updateBindings(prev =>
        prev.map(b => (b.id === id ? { ...b, enabled: !b.enabled } : b))
      )
    },
    [updateBindings]
  )

  const updateInstanceName = useCallback(
    (id: string, instanceName: string) => {
      updateBindings(prev =>
        prev.map(b => (b.id === id ? { ...b, instanceName } : b))
      )
    },
    [updateBindings]
  )

  const removeBinding = useCallback(
    (id: string) => {
      updateBindings(prev => prev.filter(b => b.id !== id))
    },
    [updateBindings]
  )

  const addBinding = useCallback(() => {
    // Find first service type not already in the list
    const usedTypes = new Set(bindings.map(b => b.type))
    const availableType = AVAILABLE_SERVICE_TYPES.find(t => !usedTypes.has(t)) || AVAILABLE_SERVICE_TYPES[0]
    updateBindings(prev => [
      ...prev,
      {
        id: generateId(),
        type: availableType,
        instanceName: '',
        enabled: true,
        detected: false,
      },
    ])
  }, [bindings, updateBindings])

  const updateType = useCallback(
    (id: string, type: string) => {
      updateBindings(prev =>
        prev.map(b => (b.id === id ? { ...b, type } : b))
      )
    },
    [updateBindings]
  )

  const hasDetected = bindings.some(b => b.detected)

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 mb-1">
        <svg className="w-4 h-4 text-cyan-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2" />
        </svg>
        <h4 className="text-xs font-mono font-semibold text-wm_text-primary">
          Service Bindings {hasDetected ? 'Detected' : ''}
        </h4>
      </div>

      {hasDetected && (
        <p className="text-[11px] text-wm_text-secondary leading-relaxed">
          Based on your requirements, this application appears to need:
        </p>
      )}

      {bindings.length === 0 && (
        <p className="text-[11px] text-wm_text-muted">
          No services detected. Add services your application needs.
        </p>
      )}

      <div className="space-y-2">
        {bindings.map(binding => (
          <div
            key={binding.id}
            className={`rounded-lg border p-3 transition-colors ${
              binding.enabled
                ? 'border-wm-border-light bg-wm-elevated'
                : 'border-wm-border bg-wm-surface opacity-60'
            }`}
          >
            <div className="flex items-center gap-3">
              {/* Checkbox */}
              <button
                type="button"
                onClick={() => toggleBinding(binding.id)}
                className={`w-4 h-4 rounded border flex items-center justify-center flex-shrink-0 transition-colors ${
                  binding.enabled
                    ? 'bg-cyan-500 border-cyan-500'
                    : 'border-wm_text-muted bg-transparent'
                }`}
                aria-label={`Toggle ${SERVICE_DISPLAY_NAMES[binding.type] || binding.type}`}
              >
                {binding.enabled && (
                  <svg className="w-3 h-3 text-wm-bg" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                  </svg>
                )}
              </button>

              {/* Service type */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1.5">
                  {binding.detected ? (
                    <span className="text-xs font-medium text-wm_text-primary">
                      {SERVICE_DISPLAY_NAMES[binding.type] || binding.type}
                    </span>
                  ) : (
                    <select
                      value={binding.type}
                      onChange={e => updateType(binding.id, e.target.value)}
                      className="bg-wm-surface border border-wm-border rounded px-2 py-1 text-xs text-wm_text-primary focus:outline-none focus:border-cyan-500"
                    >
                      {AVAILABLE_SERVICE_TYPES.map(t => (
                        <option key={t} value={t}>
                          {SERVICE_DISPLAY_NAMES[t]}
                        </option>
                      ))}
                    </select>
                  )}
                  {binding.detected && (
                    <span className="text-[9px] px-1.5 py-0.5 rounded bg-cyan-500/10 text-cyan-400 border border-cyan-500/30">
                      detected
                    </span>
                  )}
                </div>

                {/* Instance name input */}
                {binding.enabled && (
                  <div>
                    <label className="text-[10px] text-wm_text-muted block mb-1">
                      Service instance name:
                    </label>
                    <input
                      type="text"
                      value={binding.instanceName}
                      onChange={e => updateInstanceName(binding.id, e.target.value)}
                      placeholder={`my-${binding.type}-instance`}
                      className="w-full bg-wm-surface border border-wm-border rounded px-3 py-1.5 text-xs text-wm_text-primary placeholder:text-wm_text-dim focus:outline-none focus:border-cyan-500"
                    />
                    <p className="text-[9px] text-wm_text-muted mt-1">
                      Must be pre-created in target CF space
                    </p>
                  </div>
                )}
              </div>

              {/* Remove button */}
              <button
                type="button"
                onClick={() => removeBinding(binding.id)}
                className="text-wm_text-muted hover:text-red-400 transition-colors flex-shrink-0 p-1"
                aria-label="Remove service"
              >
                <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* Add Service button */}
      <button
        type="button"
        onClick={addBinding}
        className="flex items-center gap-1.5 px-3 py-1.5 text-[11px] text-cyan-400 border border-dashed border-cyan-500/30 rounded-lg hover:bg-cyan-500/10 transition-colors"
      >
        <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
        </svg>
        Add Service
      </button>

      {question.why_asking && (
        <p className="text-[10px] text-wm_text-muted italic mt-2">
          Why: {question.why_asking}
        </p>
      )}
    </div>
  )
}

export function ClarifyingQuestionsPanel({ missionId, questions, onRefresh }: ClarifyingQuestionsPanelProps) {
  const [answers, setAnswers] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {}
    questions.questions.forEach(q => {
      if (q.id === CF_SERVICE_BINDINGS_ID) {
        // Service binding editor manages its own state; initialize with empty
        const detected = parseDetectedServices(q.suggested_options)
        initial[q.id] = formatServiceBindingsAnswer(detected)
      } else {
        initial[q.id] = q.default_answer || ''
      }
    })
    return initial
  })
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleAnswerChange = (questionId: string, value: string) => {
    setAnswers(prev => ({ ...prev, [questionId]: value }))
  }

  const handleOptionSelect = (questionId: string, option: string) => {
    setAnswers(prev => {
      const current = prev[questionId] || ''
      if (current.includes(option)) {
        return { ...prev, [questionId]: current.replace(option, '').trim() }
      }
      return { ...prev, [questionId]: current ? `${current}, ${option}` : option }
    })
  }

  const handleSubmit = async () => {
    setSubmitting(true)
    setError(null)

    try {
      const formattedAnswers = questions.questions.map(q => ({
        question_id: q.id,
        question: q.question,
        answer: answers[q.id] || q.default_answer || '(no answer provided)'
      }))

      await apiClient.submitClarifyingAnswers(missionId, formattedAnswers)
      onRefresh()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to submit answers')
    } finally {
      setSubmitting(false)
    }
  }

  const allRequiredAnswered = questions.questions
    .filter(q => q.required)
    .every(q => (answers[q.id] || '').trim() !== '')

  return (
    <div className="mb-5 rounded-lg border border-wm-border bg-wm-card p-4">
      <div className="flex items-center gap-2 mb-3">
        <div className="w-5 h-5 rounded bg-agent-researcher/20 flex items-center justify-center">
          <svg className="w-3 h-3 text-agent-researcher" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8.228 9c.549-1.165 2.03-2 3.772-2 2.21 0 4 1.343 4 3 0 1.4-1.278 2.575-3.006 2.907-.542.104-.994.54-.994 1.093m0 3h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        </div>
        <h3 className="text-xs font-mono font-semibold text-wm_text-primary">
          Clarifying Questions
        </h3>
      </div>

      {questions.summary && (
        <p className="text-xs text-wm_text-secondary mb-4 leading-relaxed">
          <span className="text-wm_text-muted">Understanding: </span>
          {questions.summary}
        </p>
      )}

      <div className="space-y-4">
        {questions.questions.map((q, idx) => (
          <div key={q.id} className="border border-wm-border rounded-lg p-3 bg-wm-surface">
            <div className="flex items-start gap-2 mb-2">
              <span className="text-[10px] font-mono text-wm_text-dim">Q{idx + 1}</span>
              <span className={`text-[9px] px-1.5 py-0.5 rounded border ${categoryColors[q.category] || 'bg-gray-500/10 text-gray-400 border-gray-500/30'}`}>
                {q.category}
              </span>
              {q.required && (
                <span className="text-[9px] px-1.5 py-0.5 rounded bg-red-500/10 text-red-400 border border-red-500/30">
                  required
                </span>
              )}
            </div>

            {q.id === CF_SERVICE_BINDINGS_ID ? (
              /* Service binding editor */
              <ServiceBindingEditor
                question={q}
                onChange={(value) => handleAnswerChange(q.id, value)}
              />
            ) : (
              /* Standard question rendering */
              <>
                <p className="text-sm text-wm_text-primary mb-2">{q.question}</p>

                {q.why_asking && (
                  <p className="text-[10px] text-wm_text-muted mb-2 italic">
                    Why: {q.why_asking}
                  </p>
                )}

                {q.suggested_options && q.suggested_options.length > 0 && (
                  <div className="flex flex-wrap gap-1.5 mb-2">
                    {q.suggested_options.map(opt => (
                      <button
                        key={opt}
                        onClick={() => handleOptionSelect(q.id, opt)}
                        className={`px-2 py-1 text-[10px] rounded border transition-colors ${
                          answers[q.id]?.includes(opt)
                            ? 'bg-agent-researcher/20 border-agent-researcher text-agent-researcher'
                            : 'bg-wm-card border-wm-border text-wm_text-muted hover:border-wm_text-muted'
                        }`}
                      >
                        {opt}
                      </button>
                    ))}
                  </div>
                )}

                <textarea
                  value={answers[q.id] || ''}
                  onChange={(e) => handleAnswerChange(q.id, e.target.value)}
                  placeholder={q.default_answer || 'Type your answer...'}
                  className="w-full bg-wm-surface border border-wm-border rounded px-3 py-2 text-xs text-wm_text-primary placeholder:text-wm_text-dim focus:outline-none focus:border-agent-researcher resize-none"
                  rows={2}
                />
              </>
            )}
          </div>
        ))}
      </div>

      {error && (
        <div className="mt-3 p-2 rounded bg-red-500/10 border border-red-500/30">
          <p className="text-xs text-red-400">{error}</p>
        </div>
      )}

      <div className="mt-4 flex items-center justify-between">
        <p className="text-[10px] text-wm_text-dim">
          {questions.questions.filter(q => q.required).length > 0
            ? `* ${questions.questions.filter(q => q.required).length} required question(s)`
            : 'All questions are optional'}
        </p>
        <button
          onClick={handleSubmit}
          disabled={submitting || !allRequiredAnswered}
          className="px-4 py-2 bg-agent-researcher text-wm-background font-mono text-xs rounded-lg hover:bg-agent-researcher/90 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {submitting ? 'Submitting...' : 'Continue to Spec Generation'}
        </button>
      </div>
    </div>
  )
}
