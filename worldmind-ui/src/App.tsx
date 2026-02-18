import { useState } from 'react'
import { useMissionList } from './hooks/useMissionList'
import { MissionForm } from './components/MissionForm'
import { MissionList } from './components/MissionList'
import { MissionDetail } from './components/MissionDetail'
import { SettingsPanel } from './components/SettingsPanel'

function App() {
  const {
    missions,
    selectedMissionId,
    submitting,
    submitError,
    submitMission,
    selectMission,
  } = useMissionList()

  const [showSettings, setShowSettings] = useState(false)

  const handleSubmit = async (request: string, mode: string, projectPath?: string, gitRemoteUrl?: string, reasoningLevel?: string, executionStrategy?: string, createCfDeployment?: boolean) => {
    await submitMission(request, mode, projectPath, gitRemoteUrl, reasoningLevel, executionStrategy, createCfDeployment)
  }

  return (
    <div className="flex flex-col h-screen bg-wm-bg noise-overlay">
      {/* Header */}
      <MissionForm
        onSubmit={handleSubmit}
        submitting={submitting}
        error={submitError}
        showSettings={showSettings}
        onToggleSettings={() => setShowSettings(s => !s)}
      />

      {/* Main */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar - fixed width, never shrinks */}
        <aside className="w-72 min-w-[288px] max-w-[288px] border-r border-wm-border bg-wm-surface overflow-y-auto flex-shrink-0">
          <div className="px-3 py-2.5 border-b border-wm-border">
            <span className="text-[10px] font-mono uppercase tracking-wider text-wm_text-muted">
              Missions ({missions.size})
            </span>
          </div>
          <MissionList
            missions={missions}
            selectedMissionId={selectedMissionId}
            onSelect={selectMission}
          />
        </aside>

        {/* Detail - takes remaining space, content can't push sidebar */}
        <main className="flex-1 min-w-0 bg-wm-bg overflow-hidden">
          {showSettings ? (
            <SettingsPanel />
          ) : selectedMissionId ? (
            <MissionDetail missionId={selectedMissionId} />
          ) : (
            <div className="flex items-center justify-center h-full">
              <div className="text-center">
                <div className="w-16 h-16 rounded-2xl bg-wm-surface border border-wm-border flex items-center justify-center mx-auto mb-4">
                  <svg className="w-7 h-7 text-wm_text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75z" />
                  </svg>
                </div>
                <p className="text-xs text-wm_text-muted font-mono">
                  {missions.size === 0
                    ? 'launch your first mission'
                    : 'select a mission to view'}
                </p>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  )
}

export default App
