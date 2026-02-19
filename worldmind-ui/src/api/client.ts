import { MissionResponse, DirectiveResponse, TimelineEntry, McpSettingsResponse, AuthStatusResponse, LlmProvidersResponse, LlmCurrentResponse } from './types'

const API_BASE = '/api/v1'

class ApiClient {
  async submitMission(
    request: string,
    mode: string,
    projectPath?: string,
    gitRemoteUrl?: string,
    reasoningLevel?: string,
    executionStrategy?: string,
    createCfDeployment?: boolean
  ): Promise<{ mission_id: string; status: string }> {
    const response = await fetch(`${API_BASE}/missions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        request,
        mode,
        project_path: projectPath || '',
        git_remote_url: gitRemoteUrl || '',
        reasoning_level: reasoningLevel || 'medium',
        execution_strategy: executionStrategy || 'SEQUENTIAL',
        create_cf_deployment: createCfDeployment || false
      })
    })

    if (!response.ok) {
      const error = await response.json()
      throw new Error(error.error || 'Failed to submit mission')
    }

    return response.json()
  }

  async listMissions(): Promise<MissionResponse[]> {
    const response = await fetch(`${API_BASE}/missions`)

    if (!response.ok) {
      throw new Error('Failed to fetch missions')
    }

    return response.json()
  }

  async getMission(id: string): Promise<MissionResponse> {
    const response = await fetch(`${API_BASE}/missions/${id}`)

    if (!response.ok) {
      if (response.status === 404) {
        throw new Error('Mission not found')
      }
      throw new Error('Failed to fetch mission')
    }

    return response.json()
  }

  async approveMission(id: string): Promise<void> {
    const response = await fetch(`${API_BASE}/missions/${id}/approve`, {
      method: 'POST'
    })

    if (!response.ok) {
      throw new Error('Failed to approve mission')
    }
  }

  async editMission(id: string, modifications: string): Promise<void> {
    const response = await fetch(`${API_BASE}/missions/${id}/edit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ modifications })
    })

    if (!response.ok) {
      throw new Error('Failed to edit mission')
    }
  }

  async submitClarifyingAnswers(
    id: string, 
    answers: { question_id: string; question: string; answer: string }[]
  ): Promise<void> {
    const response = await fetch(`${API_BASE}/missions/${id}/clarify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ answers })
    })

    if (!response.ok) {
      const error = await response.json()
      throw new Error(error.error || 'Failed to submit answers')
    }
  }

  async cancelMission(id: string): Promise<void> {
    const response = await fetch(`${API_BASE}/missions/${id}/cancel`, {
      method: 'POST'
    })

    if (!response.ok) {
      throw new Error('Failed to cancel mission')
    }
  }

  async getTimeline(id: string): Promise<TimelineEntry[]> {
    const response = await fetch(`${API_BASE}/missions/${id}/timeline`)

    if (!response.ok) {
      throw new Error('Failed to fetch timeline')
    }

    return response.json()
  }

  async getDirective(missionId: string, directiveId: string): Promise<DirectiveResponse> {
    const response = await fetch(`${API_BASE}/missions/${missionId}/directives/${directiveId}`)

    if (!response.ok) {
      throw new Error('Failed to fetch directive')
    }

    return response.json()
  }

  async getMcpSettings(): Promise<McpSettingsResponse> {
    const response = await fetch(`${API_BASE}/settings/mcp`)

    if (!response.ok) {
      throw new Error('Failed to fetch MCP settings')
    }

    return response.json()
  }

  async retryMission(id: string, directiveIds?: string[]): Promise<{ mission_id: string; status: string }> {
    const response = await fetch(`${API_BASE}/missions/${id}/retry`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(
        directiveIds ? { directive_ids: directiveIds } : {}
      )
    })

    if (!response.ok) {
      const error = await response.json()
      throw new Error(error.error || 'Failed to retry mission')
    }

    return response.json()
  }

  async getAuthStatus(): Promise<AuthStatusResponse> {
    const response = await fetch(`${API_BASE}/auth/status`)
    if (!response.ok) {
      throw new Error('Failed to check auth status')
    }
    return response.json()
  }

  async login(username: string, password: string): Promise<{ authenticated: boolean; username?: string; error?: string }> {
    const response = await fetch(`${API_BASE}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    })

    return response.json()
  }

  async logout(): Promise<void> {
    await fetch(`${API_BASE}/auth/logout`, { method: 'POST' })
  }

  async getLlmProviders(): Promise<LlmProvidersResponse> {
    const response = await fetch(`${API_BASE}/llm/providers`)
    if (!response.ok) {
      throw new Error('Failed to fetch LLM providers')
    }
    return response.json()
  }

  async selectLlmModel(provider: string, model: string): Promise<LlmCurrentResponse> {
    const response = await fetch(`${API_BASE}/llm/select`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider, model })
    })
    if (!response.ok) {
      const error = await response.json()
      throw new Error(error.error || 'Failed to select model')
    }
    return response.json()
  }

  async getCurrentLlm(): Promise<LlmCurrentResponse> {
    const response = await fetch(`${API_BASE}/llm/current`)
    if (!response.ok) {
      throw new Error('Failed to fetch current LLM')
    }
    return response.json()
  }
}

export const apiClient = new ApiClient()
