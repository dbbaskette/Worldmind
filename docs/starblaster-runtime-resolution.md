# Starblaster Runtime Resolution — Implementation Prompt

## Context

Worldmind is an agentic coding assistant. It decomposes missions into directives and dispatches Centurion agents (running Goose) inside Docker containers called Starblasters. Currently there is a single Starblaster base image. This means the container can only execute missions for languages and toolchains that happen to be pre-installed.

I want Starblasters to support any language or framework the user throws at it.

## What I Want

A two-tier runtime resolution strategy: **tagged images first, builder fallback second**.

### Tier 1: Tagged Images

Pre-built Starblaster images tagged by ecosystem. Each image extends the Starblaster base (which has Goose, git, and core utilities) and adds the toolchain for that ecosystem.

Target images to start with:

- `starblaster:java` — JDK 21, Maven, Gradle
- `starblaster:python` — Python 3.12, pip, uv, venv
- `starblaster:node` — Node 22 LTS, npm, yarn, pnpm
- `starblaster:rust` — Rust stable, cargo
- `starblaster:go` — Go 1.22+
- `starblaster:dotnet` — .NET 8 SDK
- `starblaster:base` — No language toolchain, just Goose + git + shell (used for Tier 2 fallback)

Each tagged image should have a multi-stage Dockerfile that extends a common `starblaster:base` image. Keep them in a `docker/starblasters/` directory with one Dockerfile per tag.

### Tier 2: Builder Fallback

When a mission requires a language or toolchain that doesn't have a tagged image, dispatch `starblaster:base` and let Goose install what it needs at runtime via shell commands.

To make this work, the Centurion's system prompt should include a preamble when running on the base image. Something like:

> "Your container has basic build tools but may not have the specific language runtime needed. If a required tool is missing, install it using the appropriate package manager before proceeding. For example: apt-get install, curl, brew, etc."

This is the slow path — container startup is fast but first-time toolchain install adds minutes. That's acceptable as a fallback. The tagged images are the fast path.

### Classify Node Changes

The Classify node already analyzes incoming missions to determine type and routing. Extend it to also resolve the **runtime tag**.

Add a `runtimeTag` field to the classification output. The Classify node should determine this from:

1. Explicit mention in the mission ("build a Spring Boot app" → `java`)
2. Project files if the mission references an existing repo (look for `pom.xml`, `package.json`, `Cargo.toml`, `go.mod`, `requirements.txt`, `*.csproj`, etc.)
3. Default to `base` if the language can't be determined

The runtime tag should be one of the tagged image names (`java`, `python`, `node`, `rust`, `go`, `dotnet`) or `base` for the fallback.

Update the Classify node's system prompt to include this responsibility. The LLM should output the runtime tag as part of its classification response.

### Schedule / Dispatch Changes

The Schedule node (or wherever Starblaster dispatch happens) needs to use the `runtimeTag` from classification to select the Docker image.

The image name pattern should be configurable via `.env`:

```bash
# Image registry and tag pattern
STARBLASTER_IMAGE_REGISTRY=ghcr.io/dbbaskette
STARBLASTER_IMAGE_PREFIX=starblaster
# Resolved image: ${STARBLASTER_IMAGE_REGISTRY}/${STARBLASTER_IMAGE_PREFIX}:${runtimeTag}
# Example: ghcr.io/dbbaskette/starblaster:java
```

In the `DockerStarblasterProvider` (or equivalent), change the image selection from a single hardcoded image to:

```
image = "${STARBLASTER_IMAGE_REGISTRY}/${STARBLASTER_IMAGE_PREFIX}:${runtimeTag}"
```

Add a fallback: if the resolved image doesn't exist locally or can't be pulled, fall back to `starblaster:base` and log a warning.

### State / Data Model Changes

The mission or directive data model needs to carry the `runtimeTag` from Classify through to Schedule/Dispatch. Add it to whatever state object flows through the graph. It should be:

- Set by Classify
- Read by Schedule/Dispatch
- Visible in the mission dashboard (future, not required now)
- Default value: `base`

### What NOT to Do

- Don't try to detect the language at dispatch time by scanning files. Classify is the right place for this decision — it has LLM reasoning and can handle ambiguous cases.
- Don't install toolchains in tagged images at container startup. They should be baked into the image at build time. Startup should be fast.
- Don't create a separate image per Centurion type × language combination. The language toolchain is orthogonal to the Centurion role. A Forge centurion and a Gauntlet centurion running against a Java project both use `starblaster:java`.
- Don't block on having all tagged images before shipping. Start with `java` and `python` (most common), add others as needed. The `base` fallback covers everything else.

### File Inventory

**New files:**
- `docker/starblasters/Dockerfile.base` — Base Starblaster image (Goose + git + shell)
- `docker/starblasters/Dockerfile.java` — Extends base, adds JDK 21 + Maven + Gradle
- `docker/starblasters/Dockerfile.python` — Extends base, adds Python 3.12 + pip + uv
- `docker/starblasters/Dockerfile.node` — Extends base, adds Node 22 + npm + yarn + pnpm
- `docker/starblasters/Dockerfile.rust` — Extends base, adds Rust + cargo
- `docker/starblasters/Dockerfile.go` — Extends base, adds Go 1.22+
- `docker/starblasters/Dockerfile.dotnet` — Extends base, adds .NET 8 SDK
- `docker/starblasters/build-all.sh` — Script to build all tagged images

**Modified files:**
- Classify node — Add `runtimeTag` to classification output and system prompt
- State/data model — Add `runtimeTag` field
- Schedule/Dispatch — Use `runtimeTag` to select Docker image
- `DockerStarblasterProvider` — Dynamic image selection with fallback
- `.env.example` — Add `STARBLASTER_IMAGE_REGISTRY` and `STARBLASTER_IMAGE_PREFIX`
- `docker-compose.yml` — Update any hardcoded image references

### Priority

Ship `starblaster:base`, `starblaster:java`, and `starblaster:python` first. Add the rest when needed. The fallback to `base` means nothing is blocked.
