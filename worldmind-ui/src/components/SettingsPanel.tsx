import { useState, useEffect } from 'react'
import { apiClient } from '../api/client'
import { McpSettingsResponse, McpServerInfo, McpConsumerInfo, LlmProvidersResponse, LlmModel } from '../api/types'

const MCP_CACHE_KEY = 'worldmind:mcp-settings'
const LLM_CACHE_KEY = 'worldmind:llm-settings'

function loadCached(): McpSettingsResponse | null {
  try {
    const raw = sessionStorage.getItem(MCP_CACHE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch { return null }
}

function saveCache(data: McpSettingsResponse) {
  try { sessionStorage.setItem(MCP_CACHE_KEY, JSON.stringify(data)) } catch {}
}

function loadLlmCached(): LlmProvidersResponse | null {
  try {
    const raw = sessionStorage.getItem(LLM_CACHE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch { return null }
}

function saveLlmCache(data: LlmProvidersResponse) {
  try { sessionStorage.setItem(LLM_CACHE_KEY, JSON.stringify(data)) } catch {}
}

export function SettingsPanel() {
  const cached = loadCached()
  const llmCached = loadLlmCached()
  const [settings, setSettings] = useState<McpSettingsResponse | null>(cached)
  const [llmSettings, setLlmSettings] = useState<LlmProvidersResponse | null>(llmCached)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(!cached)
  const [llmLoading, setLlmLoading] = useState(!llmCached)
  const [selectedProvider, setSelectedProvider] = useState<string>(llmCached?.currentProvider || '')
  const [selectedModel, setSelectedModel] = useState<string>(llmCached?.currentModel || '')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    apiClient.getMcpSettings()
      .then(data => { setSettings(data); saveCache(data) })
      .catch(e => { if (!settings) setError(e.message) })
      .finally(() => setLoading(false))

    apiClient.getLlmProviders()
      .then(data => {
        setLlmSettings(data)
        saveLlmCache(data)
        setSelectedProvider(data.currentProvider || '')
        setSelectedModel(data.currentModel || '')
      })
      .catch(() => {})
      .finally(() => setLlmLoading(false))
  }, [])

  const handleProviderChange = (providerId: string) => {
    setSelectedProvider(providerId)
    const provider = llmSettings?.providers.find(p => p.id === providerId)
    if (provider && provider.models.length > 0) {
      setSelectedModel(provider.models[0].id)
    }
  }

  const handleSaveModel = async () => {
    if (!selectedProvider || !selectedModel) return
    setSaving(true)
    try {
      await apiClient.selectLlmModel(selectedProvider, selectedModel)
      const updated = await apiClient.getLlmProviders()
      setLlmSettings(updated)
      saveLlmCache(updated)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  const currentProvider = llmSettings?.providers.find(p => p.id === selectedProvider)
  const currentModel = currentProvider?.models.find(m => m.id === selectedModel)
  const hasChanges = selectedProvider !== llmSettings?.currentProvider || selectedModel !== llmSettings?.currentModel

  return (
    <div className="h-full overflow-y-auto p-6">
      <h2 className="text-lg font-semibold text-wm_text-primary mb-6">Settings</h2>

      {/* LLM Model Selection Section */}
      <div className="mb-8">
        <h3 className="text-sm font-semibold text-wm_text-primary uppercase tracking-wider mb-3">LLM Model</h3>

        {llmLoading ? (
          <div className="flex items-center gap-2 text-sm text-wm_text-muted">
            <span className="w-3 h-3 border-2 border-wm_text-muted/30 border-t-wm_text-muted rounded-full animate-spin" />
            Loading providers...
          </div>
        ) : llmSettings && llmSettings.providers.length > 0 ? (
          <div className="space-y-4">
            {/* Provider Selection */}
            <div>
              <label className="block text-[10px] font-mono uppercase tracking-wider text-wm_text-muted mb-2">
                Provider
              </label>
              <div className="flex flex-wrap gap-2">
                {llmSettings.providers.map(provider => (
                  <button
                    key={provider.id}
                    onClick={() => handleProviderChange(provider.id)}
                    className={`px-3 py-1.5 rounded-lg border text-xs font-medium transition-all ${
                      selectedProvider === provider.id
                        ? 'bg-centurion-vigil/20 border-centurion-vigil/50 text-centurion-vigil'
                        : 'bg-wm-bg border-wm-border text-wm_text-muted hover:border-wm_text-muted/50'
                    }`}
                  >
                    {provider.name}
                  </button>
                ))}
              </div>
            </div>

            {/* Model Selection */}
            {currentProvider && (
              <div>
                <label className="block text-[10px] font-mono uppercase tracking-wider text-wm_text-muted mb-2">
                  Model
                </label>
                <div className="space-y-2">
                  {currentProvider.models.map(model => (
                    <ModelCard
                      key={model.id}
                      model={model}
                      selected={selectedModel === model.id}
                      onSelect={() => setSelectedModel(model.id)}
                    />
                  ))}
                </div>
              </div>
            )}

            {/* Save Button */}
            {hasChanges && (
              <div className="flex items-center gap-3 pt-2">
                <button
                  onClick={handleSaveModel}
                  disabled={saving}
                  className="px-4 py-2 bg-centurion-vigil text-white rounded-lg text-xs font-medium hover:bg-centurion-vigil/90 disabled:opacity-50 transition-all"
                >
                  {saving ? 'Saving...' : 'Save Selection'}
                </button>
                <span className="text-[10px] text-wm_text-muted">
                  Changes will apply to new missions
                </span>
              </div>
            )}

            {/* Current Selection Display */}
            {!hasChanges && currentModel && (
              <div className="bg-wm-card rounded-lg border border-wm-border p-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-centurion-forge/10 text-centurion-forge border border-centurion-forge/30">
                      {currentProvider?.name}
                    </span>
                    <span className="text-sm font-mono text-wm_text-secondary">{currentModel.name}</span>
                  </div>
                  <span className="text-[10px] text-wm_text-muted">{currentModel.priceDisplay}</span>
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className="bg-wm-card rounded-lg border border-wm-border p-4">
            <p className="text-xs text-wm_text-muted">
              No LLM providers configured. Add API keys to .env file:
            </p>
            <ul className="mt-2 text-xs text-wm_text-secondary font-mono space-y-1">
              <li>ANTHROPIC_API_KEY=sk-ant-...</li>
              <li>OPENAI_API_KEY=sk-...</li>
              <li>GOOGLE_API_KEY=AIza...</li>
            </ul>
          </div>
        )}
      </div>

      {/* MCP Servers Section */}
      <div className="mb-8">
        <div className="flex items-center gap-3 mb-4">
          <h3 className="text-sm font-semibold text-wm_text-primary uppercase tracking-wider">MCP Servers</h3>
          {settings && (
            <span className={`px-2 py-0.5 rounded text-[10px] font-mono ${
              settings.enabled
                ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/30'
                : 'bg-red-500/10 text-red-400 border border-red-500/30'
            }`}>
              {settings.enabled ? 'Enabled' : 'Disabled'}
            </span>
          )}
        </div>

        {loading && (
          <div className="flex items-center gap-2 text-sm text-wm_text-muted">
            <span className="w-3 h-3 border-2 border-wm_text-muted/30 border-t-wm_text-muted rounded-full animate-spin" />
            Loading MCP configuration...
          </div>
        )}

        {error && (
          <div className="text-xs text-red-400 bg-red-500/10 border border-red-500/20 px-3 py-2 rounded">
            {error}
          </div>
        )}

        {settings && settings.servers.length === 0 && !loading && (
          <p className="text-xs text-wm_text-muted font-mono">No MCP servers configured</p>
        )}

        {settings?.servers.map(server => (
          <ServerCard key={server.name} server={server} />
        ))}
      </div>
    </div>
  )
}

function ModelCard({ model, selected, onSelect }: { model: LlmModel; selected: boolean; onSelect: () => void }) {
  const tierColors: Record<string, string> = {
    flagship: 'bg-centurion-vigil/10 text-centurion-vigil border-centurion-vigil/30',
    premium: 'bg-amber-500/10 text-amber-400 border-amber-500/30',
    fast: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30',
    reasoning: 'bg-purple-500/10 text-purple-400 border-purple-500/30',
    bound: 'bg-blue-500/10 text-blue-400 border-blue-500/30',
  }

  return (
    <div
      onClick={onSelect}
      className={`p-3 rounded-lg border cursor-pointer transition-all ${
        selected
          ? 'bg-centurion-vigil/10 border-centurion-vigil/40'
          : 'bg-wm-card border-wm-border hover:border-wm_text-muted/30'
      }`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <span className={`text-sm font-medium ${selected ? 'text-wm_text-primary' : 'text-wm_text-secondary'}`}>
              {model.name}
            </span>
            <span className={`px-1.5 py-0.5 rounded text-[9px] font-mono font-bold border ${tierColors[model.tier] || tierColors.flagship}`}>
              {model.tier}
            </span>
          </div>
          <p className="text-[11px] text-wm_text-muted">{model.description}</p>
        </div>
        <div className="text-right shrink-0">
          <div className="text-[10px] text-wm_text-muted font-mono">{model.priceDisplay}</div>
          {model.contextWindow > 0 && (
            <div className="text-[9px] text-wm_text-dim">{(model.contextWindow / 1000).toFixed(0)}K context</div>
          )}
        </div>
      </div>
    </div>
  )
}

function ServerCard({ server }: { server: McpServerInfo }) {
  return (
    <div className="bg-wm-card rounded-lg border border-wm-border p-4 mb-3">
      {/* Header */}
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-wm_text-primary">{server.name}</span>
          <span className={`px-1.5 py-0.5 rounded text-[10px] font-mono font-bold ${
            server.status === 'UP'
              ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/30'
              : 'bg-red-500/10 text-red-400 border border-red-500/30'
          }`}>
            {server.status}
          </span>
        </div>
      </div>

      <div className="text-xs font-mono text-wm_text-muted mb-3 break-all">{server.url}</div>

      {/* Consumers with per-consumer tools */}
      {server.consumers.length > 0 && (
        <div>
          <span className="text-[10px] font-mono uppercase tracking-wider text-wm_text-muted block mb-2">
            Consumers
          </span>
          <div className="space-y-2">
            {server.consumers.map(consumer => (
              <ConsumerRow key={consumer.name} consumer={consumer} />
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function ConsumerRow({ consumer }: { consumer: McpConsumerInfo }) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="bg-wm-bg rounded border border-wm-border">
      <div
        className="flex items-center justify-between px-2.5 py-1.5 cursor-pointer hover:bg-wm-elevated/50 transition-colors"
        onClick={() => consumer.tools.length > 0 && setExpanded(!expanded)}
      >
        <div className="flex items-center gap-2">
          <span className="text-xs font-mono text-wm_text-secondary">{consumer.name}</span>
          {consumer.hasToken ? (
            <svg className="w-3 h-3 text-emerald-400" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l6 6 9-13.5" />
            </svg>
          ) : (
            <svg className="w-3 h-3 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          )}
        </div>
        {consumer.tools.length > 0 && (
          <span className="text-[10px] font-mono text-centurion-vigil/70">
            {expanded ? '\u25BE' : '\u25B8'} {consumer.tools.length} tool{consumer.tools.length !== 1 ? 's' : ''}
          </span>
        )}
      </div>

      {expanded && consumer.tools.length > 0 && (
        <div className="border-t border-wm-border px-2.5 py-1.5 space-y-1 animate-fade-in">
          {consumer.tools.map(tool => (
            <div key={tool.name} className="flex items-baseline gap-2">
              <span className="text-[10px] font-mono text-wm_text-primary shrink-0">{tool.name}</span>
              {tool.description && (
                <span className="text-[10px] text-wm_text-secondary truncate">{tool.description}</span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
