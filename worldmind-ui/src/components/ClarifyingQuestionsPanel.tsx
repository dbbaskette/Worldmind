import { useState } from 'react'
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

const categoryColors: Record<string, string> = {
  scope: 'bg-blue-500/10 text-blue-400 border-blue-500/30',
  technical: 'bg-purple-500/10 text-purple-400 border-purple-500/30',
  ux: 'bg-pink-500/10 text-pink-400 border-pink-500/30',
  integration: 'bg-cyan-500/10 text-cyan-400 border-cyan-500/30',
  constraints: 'bg-amber-500/10 text-amber-400 border-amber-500/30',
}

export function ClarifyingQuestionsPanel({ missionId, questions, onRefresh }: ClarifyingQuestionsPanelProps) {
  const [answers, setAnswers] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {}
    questions.questions.forEach(q => {
      initial[q.id] = q.default_answer || ''
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
