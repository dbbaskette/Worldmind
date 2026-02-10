import { useMissionList } from './hooks/useMissionList'
import { MissionForm } from './components/MissionForm'
import { MissionList } from './components/MissionList'
import { MissionDetail } from './components/MissionDetail'

function App() {
  const {
    missions,
    selectedMissionId,
    submitting,
    submitError,
    submitMission,
    selectMission,
  } = useMissionList()

  const handleSubmit = async (request: string, mode: string, projectPath?: string) => {
    await submitMission(request, mode, projectPath)
  }

  return (
    <div className="flex flex-col h-screen">
      {/* Header with Mission Form */}
      <MissionForm
        onSubmit={handleSubmit}
        submitting={submitting}
        error={submitError}
      />

      {/* Main Content: Sidebar + Detail Panel */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar: Mission List */}
        <div className="w-96 border-r bg-white overflow-y-auto">
          {/* Missions Header */}
          <div className="px-4 py-3 border-b bg-gray-50">
            <h2 className="text-sm font-semibold text-gray-700">
              Missions ({missions.size})
            </h2>
          </div>

          <MissionList
            missions={missions}
            selectedMissionId={selectedMissionId}
            onSelect={selectMission}
          />
        </div>

        {/* Detail Panel */}
        <div className="flex-1 bg-gray-50">
          {selectedMissionId ? (
            <MissionDetail missionId={selectedMissionId} />
          ) : (
            <div className="flex items-center justify-center h-full">
              <div className="text-center">
                <svg
                  className="w-24 h-24 mx-auto mb-4 text-gray-300"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                  />
                </svg>
                <p className="text-gray-500 text-lg">
                  {missions.size === 0
                    ? 'Submit your first mission to get started'
                    : 'Select a mission to view details'}
                </p>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

export default App
