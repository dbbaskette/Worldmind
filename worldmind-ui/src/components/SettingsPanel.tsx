import { useState, useEffect } from 'react'
import { apiClient } from '../api/client'
import { McpSettingsResponse, McpServerInfo, McpConsumerInfo } from '../api/types'

const MCP_CACHE_KEY = 'worldmind:mcp-settings'

function loadCached(): McpSettingsResponse | null {
  try {
    const raw = sessionStorage.getItem(MCP_CACHE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch { return null }
}

function saveCache(data: McpSettingsResponse) {
  try { sessionStorage.setItem(MCP_CACHE_KEY, JSON.stringify(data)) } catch {}
}

export function SettingsPanel() {
  const cached = loadCached()
  const [settings, setSettings] = useState<McpSettingsResponse | null>(cached)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(!cached)

  useEffect(() => {
    apiClient.getMcpSettings()
      .then(data => { setSettings(data); saveCache(data) })
      .catch(e => { if (!settings) setError(e.message) })
      .finally(() => setLoading(false))
  }, [])

  return (
    <div className="h-full overflow-y-auto p-6">
      <h2 className="text-lg font-semibold text-wm_text-primary mb-6">Settings</h2>

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
