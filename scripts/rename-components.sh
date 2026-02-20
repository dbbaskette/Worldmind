#!/usr/bin/env bash
#
# rename-components.sh — Rename all fantasy-named components to descriptive names
#
# Covers GitHub issues #17–#27:
#   Agent → Agent, Sandbox → Sandbox, Task → Task,
#   QualityGate → QualityGate, Coder → Coder, Tester → Tester,
#   Reviewer → Reviewer, Researcher → Researcher, Refactorer → Refactorer
#
# Usage:
#   ./scripts/rename-components.sh          # execute all renames
#   DRY_RUN=1 ./scripts/rename-components.sh  # preview only, no changes
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

DRY_RUN="${DRY_RUN:-0}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
info()  { printf '\033[1;34m[INFO]\033[0m  %s\n' "$*"; }
warn()  { printf '\033[1;33m[WARN]\033[0m  %s\n' "$*"; }
error() { printf '\033[1;31m[ERROR]\033[0m %s\n' "$*"; }

do_git_mv() {
  local src="$1" dst="$2"
  if [[ ! -e "$src" ]]; then
    warn "Skipping git mv (source missing): $src"
    return 0
  fi
  if [[ -e "$dst" ]]; then
    warn "Skipping git mv (destination exists): $dst"
    return 0
  fi
  # Ensure parent directory exists
  mkdir -p "$(dirname "$dst")"
  if [[ "$DRY_RUN" == "1" ]]; then
    info "[DRY RUN] git mv $src -> $dst"
  else
    info "git mv $src -> $dst"
    git mv "$src" "$dst"
  fi
}

do_sed() {
  local find="$1" replace="$2" file="$3"
  if [[ "$DRY_RUN" == "1" ]]; then
    if grep -q "$find" "$file" 2>/dev/null; then
      info "[DRY RUN] sed: '$find' -> '$replace' in $file"
    fi
  else
    sed -i '' "s|${find}|${replace}|g" "$file"
  fi
}

# ---------------------------------------------------------------------------
# Phase 1: File & Directory Renames (git mv)
# ---------------------------------------------------------------------------
info "=== Phase 1: File & Directory Renames ==="

# --- 1a. Package directories ---
info "--- Package directories ---"

# sandbox → sandbox (main + test)
do_git_mv "src/main/java/com/worldmind/sandbox" "src/main/java/com/worldmind/sandbox"
do_git_mv "src/test/java/com/worldmind/sandbox" "src/test/java/com/worldmind/sandbox"

# core/quality_gate → core/qualitygate (main + test)
do_git_mv "src/main/java/com/worldmind/core/quality_gate" "src/main/java/com/worldmind/core/qualitygate"
do_git_mv "src/test/java/com/worldmind/core/quality_gate" "src/test/java/com/worldmind/core/qualitygate"

# --- 1b. Docker directories ---
info "--- Docker directories ---"

do_git_mv "docker/agent-base"     "docker/agent-base"
do_git_mv "docker/agent-coder"    "docker/agent-coder"
do_git_mv "docker/agent-tester" "docker/agent-tester"
do_git_mv "docker/agent-reviewer"    "docker/agent-reviewer"
do_git_mv "docker/agent-researcher"    "docker/agent-researcher"
do_git_mv "docker/agent-refactorer"    "docker/agent-refactorer"
do_git_mv "docker/sandboxes"       "docker/sandboxes"

# --- 1c. Java file renames (inside already-moved dirs) ---
info "--- Java file renames ---"

# Main source — sandbox package
do_git_mv "src/main/java/com/worldmind/sandbox/AgentDispatcher.java"    "src/main/java/com/worldmind/sandbox/AgentDispatcher.java"
do_git_mv "src/main/java/com/worldmind/sandbox/SandboxManager.java"   "src/main/java/com/worldmind/sandbox/SandboxManager.java"
do_git_mv "src/main/java/com/worldmind/sandbox/SandboxConfig.java"    "src/main/java/com/worldmind/sandbox/SandboxConfig.java"
do_git_mv "src/main/java/com/worldmind/sandbox/SandboxProperties.java" "src/main/java/com/worldmind/sandbox/SandboxProperties.java"
do_git_mv "src/main/java/com/worldmind/sandbox/SandboxProvider.java"  "src/main/java/com/worldmind/sandbox/SandboxProvider.java"
do_git_mv "src/main/java/com/worldmind/sandbox/AgentRequest.java"   "src/main/java/com/worldmind/sandbox/AgentRequest.java"
do_git_mv "src/main/java/com/worldmind/sandbox/DockerSandboxProvider.java" "src/main/java/com/worldmind/sandbox/DockerSandboxProvider.java"

# cf subpackage
do_git_mv "src/main/java/com/worldmind/sandbox/cf/CloudFoundrySandboxProvider.java" "src/main/java/com/worldmind/sandbox/cf/CloudFoundrySandboxProvider.java"
do_git_mv "src/main/java/com/worldmind/sandbox/cf/CfSandboxConfig.java" "src/main/java/com/worldmind/sandbox/cf/CfSandboxConfig.java"

# model package
do_git_mv "src/main/java/com/worldmind/core/model/SandboxInfo.java"   "src/main/java/com/worldmind/core/model/SandboxInfo.java"
do_git_mv "src/main/java/com/worldmind/core/model/Task.java"         "src/main/java/com/worldmind/core/model/Task.java"
do_git_mv "src/main/java/com/worldmind/core/model/TaskStatus.java"   "src/main/java/com/worldmind/core/model/TaskStatus.java"
do_git_mv "src/main/java/com/worldmind/core/model/QualityGateDecision.java"      "src/main/java/com/worldmind/core/model/QualityGateDecision.java"

# dispatch/api
do_git_mv "src/main/java/com/worldmind/dispatch/api/SandboxController.java" "src/main/java/com/worldmind/dispatch/api/SandboxController.java"

# core packages
do_git_mv "src/main/java/com/worldmind/core/qualitygate/QualityGateEvaluationService.java" "src/main/java/com/worldmind/core/qualitygate/QualityGateEvaluationService.java"
do_git_mv "src/main/java/com/worldmind/core/scheduler/TaskScheduler.java" "src/main/java/com/worldmind/core/scheduler/TaskScheduler.java"
do_git_mv "src/main/java/com/worldmind/core/nodes/DispatchAgentNode.java" "src/main/java/com/worldmind/core/nodes/DispatchAgentNode.java"
do_git_mv "src/main/java/com/worldmind/core/nodes/EvaluateQualityGateNode.java"  "src/main/java/com/worldmind/core/nodes/EvaluateQualityGateNode.java"

# Test source — sandbox package
do_git_mv "src/test/java/com/worldmind/sandbox/AgentDispatcherTest.java"    "src/test/java/com/worldmind/sandbox/AgentDispatcherTest.java"
do_git_mv "src/test/java/com/worldmind/sandbox/SandboxManagerTest.java"   "src/test/java/com/worldmind/sandbox/SandboxManagerTest.java"
do_git_mv "src/test/java/com/worldmind/sandbox/SandboxPropertiesTest.java" "src/test/java/com/worldmind/sandbox/SandboxPropertiesTest.java"
do_git_mv "src/test/java/com/worldmind/sandbox/SandboxProviderTest.java"  "src/test/java/com/worldmind/sandbox/SandboxProviderTest.java"
do_git_mv "src/test/java/com/worldmind/sandbox/DockerSandboxProviderTest.java" "src/test/java/com/worldmind/sandbox/DockerSandboxProviderTest.java"
do_git_mv "src/test/java/com/worldmind/sandbox/cf/CloudFoundrySandboxProviderTest.java" "src/test/java/com/worldmind/sandbox/cf/CloudFoundrySandboxProviderTest.java"

# Test source — core packages
do_git_mv "src/test/java/com/worldmind/core/qualitygate/QualityGateEvaluationServiceTest.java" "src/test/java/com/worldmind/core/qualitygate/QualityGateEvaluationServiceTest.java"
do_git_mv "src/test/java/com/worldmind/core/scheduler/TaskSchedulerTest.java" "src/test/java/com/worldmind/core/scheduler/TaskSchedulerTest.java"
do_git_mv "src/test/java/com/worldmind/core/nodes/DispatchAgentNodeTest.java" "src/test/java/com/worldmind/core/nodes/DispatchAgentNodeTest.java"
do_git_mv "src/test/java/com/worldmind/core/nodes/EvaluateQualityGateNodeTest.java" "src/test/java/com/worldmind/core/nodes/EvaluateQualityGateNodeTest.java"

# --- 1d. UI file renames ---
info "--- UI file renames ---"

do_git_mv "worldmind-ui/src/components/TaskCard.tsx"     "worldmind-ui/src/components/TaskCard.tsx"
do_git_mv "worldmind-ui/src/components/TaskTimeline.tsx"  "worldmind-ui/src/components/TaskTimeline.tsx"

# --- 1e. Workflow renames ---
info "--- Workflow renames ---"

do_git_mv ".github/workflows/agent-images.yml" ".github/workflows/agent-images.yml"

# ---------------------------------------------------------------------------
# Phase 2: Content Replacements (sed)
# ---------------------------------------------------------------------------
info ""
info "=== Phase 2: Content Replacements ==="

# Collect all target files once
TARGET_FILES=()
while IFS= read -r -d '' f; do
  TARGET_FILES+=("$f")
done < <(find "$REPO_ROOT" \
  \( -name '*.java' -o -name '*.yml' -o -name '*.yaml' \
     -o -name '*.tsx' -o -name '*.ts' -o -name '*.xml' \
     -o -name '*.md' -o -name '*.sh' -o -name '*.properties' \
     -o -name 'Dockerfile' -o -name 'Dockerfile.*' \
     -o -name 'docker-compose*.yml' -o -name '*.json' \) \
  -not -path '*/node_modules/*' \
  -not -path '*/.git/*' \
  -not -path '*/target/*' \
  -not -path '*/dist/*' \
  -not -path '*/build/*' \
  -not -path '*/package-lock.json' \
  -print0 2>/dev/null)

info "Found ${#TARGET_FILES[@]} files to scan for replacements"

# Helper: apply a single replacement across all target files
replace_all() {
  local find="$1" replace="$2"
  if [[ "$DRY_RUN" == "1" ]]; then
    local count
    count=$(grep -rl "$find" "${TARGET_FILES[@]}" 2>/dev/null | wc -l | tr -d ' ')
    if [[ "$count" -gt 0 ]]; then
      info "[DRY RUN] '$find' -> '$replace' in $count file(s)"
    fi
  else
    for f in "${TARGET_FILES[@]}"; do
      if [[ -f "$f" ]] && grep -q "$find" "$f" 2>/dev/null; then
        sed -i '' "s|${find}|${replace}|g" "$f"
      fi
    done
  fi
}

# --- 2a. Agent worker types (most specific first) ---
info "--- Worker type renames ---"

replace_all 'CODER'    'CODER'
replace_all 'Coder'    'Coder'
replace_all 'coder'    'coder'

replace_all 'TESTER' 'TESTER'
replace_all 'Tester' 'Tester'
replace_all 'tester' 'tester'

replace_all 'REVIEWER'    'REVIEWER'
replace_all 'Reviewer'    'Reviewer'
replace_all 'reviewer'    'reviewer'

replace_all 'RESEARCHER'    'RESEARCHER'
replace_all 'Researcher'    'Researcher'
replace_all 'researcher'    'researcher'

replace_all 'REFACTORER'    'REFACTORER'
replace_all 'Refactorer'    'Refactorer'
replace_all 'refactorer'    'refactorer'

# --- 2b. Compound class names (most specific first) ---
info "--- Compound class name renames ---"

replace_all 'AgentDispatcher'     'AgentDispatcher'
replace_all 'AgentDispatcherTest' 'AgentDispatcherTest'
replace_all 'SandboxManager'    'SandboxManager'
replace_all 'SandboxConfig'     'SandboxConfig'
replace_all 'SandboxProperties' 'SandboxProperties'
replace_all 'SandboxProvider'   'SandboxProvider'
replace_all 'AgentRequest'    'AgentRequest'
replace_all 'SandboxInfo'       'SandboxInfo'
replace_all 'DockerSandboxProvider'         'DockerSandboxProvider'
replace_all 'CloudFoundrySandboxProvider'   'CloudFoundrySandboxProvider'
replace_all 'CfSandboxConfig'   'CfSandboxConfig'
replace_all 'SandboxController' 'SandboxController'

# --- 2c. Base term: Sandbox ---
info "--- Sandbox -> Sandbox ---"

replace_all 'Sandboxes' 'Sandboxes'
replace_all 'sandboxes' 'sandboxes'
replace_all 'SANDBOXES' 'SANDBOXES'
replace_all 'Sandbox'  'Sandbox'
replace_all 'sandbox'  'sandbox'
replace_all 'SANDBOX'  'SANDBOX'

# --- 2d. Agent ---
info "--- Agent -> Agent ---"

replace_all 'DispatchAgentNode'     'DispatchAgentNode'
replace_all 'DispatchAgentNodeTest' 'DispatchAgentNodeTest'
replace_all 'Agents' 'Agents'
replace_all 'agents' 'agents'
replace_all 'AGENTS' 'AGENTS'
replace_all 'Agent' 'Agent'
replace_all 'agent' 'agent'
replace_all 'AGENT' 'AGENT'

# --- 2e. Task ---
info "--- Task -> Task ---"

replace_all 'TaskScheduler'     'TaskScheduler'
replace_all 'TaskSchedulerTest' 'TaskSchedulerTest'
replace_all 'TaskStatus'        'TaskStatus'
replace_all 'TaskCard'          'TaskCard'
replace_all 'TaskTimeline'      'TaskTimeline'
replace_all 'Tasks' 'Tasks'
replace_all 'tasks' 'tasks'
replace_all 'TASKS' 'TASKS'
replace_all 'Task'  'Task'
replace_all 'task'  'task'
replace_all 'TASK'  'TASK'
replace_all 'TASK-'       'TASK-'

# --- 2f. QualityGate ---
info "--- QualityGate -> QualityGate ---"

replace_all 'QualityGateEvaluationService'     'QualityGateEvaluationService'
replace_all 'QualityGateEvaluationServiceTest' 'QualityGateEvaluationServiceTest'
replace_all 'EvaluateQualityGateNode'          'EvaluateQualityGateNode'
replace_all 'EvaluateQualityGateNodeTest'      'EvaluateQualityGateNodeTest'
replace_all 'QualityGateDecision'             'QualityGateDecision'
replace_all 'EvaluateQualityGate'             'EvaluateQualityGate'
replace_all 'quality_gate_granted'             'quality_gate_granted'
replace_all 'quality_gate_denied'              'quality_gate_denied'
replace_all 'quality_gate\.granted'            'quality_gate.granted'
replace_all 'quality_gate\.denied'             'quality_gate.denied'
replace_all 'QualityGate'   'QualityGate'
replace_all 'quality_gate'   'quality_gate'
replace_all 'QUALITY_GATE'   'QUALITY_GATE'

# ---------------------------------------------------------------------------
# Phase 3: Verification
# ---------------------------------------------------------------------------
info ""
info "=== Phase 3: Verification ==="

if [[ "$DRY_RUN" == "1" ]]; then
  info "[DRY RUN] Would run: mvn compile -q"
  info "[DRY RUN] Would run: cd worldmind-ui && npm run build"
  info ""
  info "Dry run complete. Re-run without DRY_RUN=1 to apply changes."
  exit 0
fi

info "--- Checking Java compilation ---"
if mvn compile -q 2>&1; then
  info "Java compilation: SUCCESS"
else
  error "Java compilation: FAILED (see errors above)"
  error "You may need to fix remaining references manually."
fi

info "--- Checking UI build ---"
if (cd worldmind-ui && npm run build) 2>&1; then
  info "UI build: SUCCESS"
else
  error "UI build: FAILED (see errors above)"
  error "You may need to fix remaining references manually."
fi

info ""
info "--- Summary ---"
git diff --stat
info ""
info "Rename complete! Review with 'git diff' and run 'mvn test' when ready."
