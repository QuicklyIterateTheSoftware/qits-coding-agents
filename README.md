# qits-coding-agents

The shared coding-agent harness for this platform: launching and supervising processes inside a
container's checkout, and rendering, spawning and reading back a coding-agent session on top of
that. Two published jars, no deployable.

| Module | Artifact | What it is |
| --- | --- | --- |
| `qits-commands` | `eu.wohlben.qits:qits-commands` | The PTY, the command registry and store, the log buffer, the chat transports |
| `qits-coding-agents` | `eu.wohlben.qits:qits-coding-agents` | The harnesses (Claude Code, Kimi), launch rendering, the ACP client, session lineage, transcripts, plugins, auth status |

The reactor root is `eu.wohlben.qits:qits-agents`. It is a parent, not something you resolve.

## Why it exists

Both of these modules lived **twice**: once inside `qits-projects-daemon`, once inside
`qits-workspace-daemon`, under two package roots (`eu.wohlben.qits.projectsdaemon.*` and
`eu.wohlben.qits.workspacedaemon.*`). They were adopted rather than shared, and they drifted:

- the projects copy grew `AgentDesk` and the tickets front-desk system prompt;
- the workspace copy grew `AgentPluginService`, `PromptRefinementService`, a third MCP scope, and
  four named pre-approved writes;
- `qits-commands` grew a checkout-availability refusal on one side only.

None of that was a disagreement about what the harness should do. It was two people fixing two
copies. Every change to the harness had to be written twice or land on one side, and the second
option is what kept happening.

The **union** of both copies moved here — nothing was dropped because one side lacked it — and both
daemons now depend on this artifact instead of carrying a copy.

## The seam a host daemon implements

The library must not be able to tell which product it is inside. Everything that could tell it is an
interface the host implements:

| Seam | What the host answers |
| --- | --- |
| `CheckoutContext` | The branch and commit currently checked out. Nothing else — a daemon's own identity (project id, repo id, workspace id) is its own business, and it declares its own interface extending this one. |
| `AgentMcpServers` | `List<ScopedMcp> serversFor(AgentMcpScope)` — which MCP servers a scope attaches, each already narrowed, each with its pre-approval list. |
| `McpEndpoints` | Where an MCP server lives, and which project this container serves. |
| `AgentDefaults` | The instance-level preferences a launch falls back on: default harness, activity tracking, refinement model. |
| `ActionResolver` | The action table, read from the checkout's own `.qits-config.yml`. |
| `AgentCommands` | The command-launching surface `AgentLaunchService` spawns through. |

`AgentMcpServers` is the one worth reading twice. The two daemons' mappings are not variations on a
theme — the projects daemon attaches **one** server (`repository`, because a project agent's job is
the plan), the workspace daemon attaches **three** (`repository` + `observability`, plus `actions`
on the `ACTIONS` scope), with different narrowing and different pre-approval lists in a different
order. Neither is a default the other could fall back on. So the mapping is the host's, and
`AgentLaunchService` renders whatever it is handed: it attaches each server, marks the URLs
read-only for an autonomous run, and strips the `mcp__<key>__` prefix for Kimi.

The pre-approval lists travel with the mapping for the same reason. They are not a property of the
harness; they are a policy about what this product's agents may do without asking. The library keeps
only the rule those URLs are subject to — `AgentMcpIds.requireId`, because a scoped URL ends up
inside a single-quoted shell argument and the renderer does no escaping of its own.

Both hosts' mappings are reproduced as fixtures in this repository's test scope
(`ProjectHostMcpServers`, `WorkspaceHostMcpServers`) so that both daemons' rendered commands stay
asserted byte for byte **here**, where the harness now lives.

## Framework-free, deliberately

No Quarkus, no CDI, no JAX-RS, no Jackson, no configuration reading. Three things follow from it,
and all three are the point:

- a Quarkus daemon and a plain-JVM daemon can both embed it;
- it links into a GraalVM native image with nothing to register (the one resources directory is
  `qits-commands`' native-image metadata, which pins `ForeignPty` to run-time initialization — build
  metadata for the consumer, not configuration this library reads);
- a steering prompt is a Java text block rather than a classpath resource, which is what lets a unit
  test assert a rendered launch command byte for byte.

The single framework dependency is `io.vertx:vertx-core`, and only for `io.vertx.core.json`: the
stream-json chat transport and the harness transcripts are line-delimited JSON, and adding Jackson
for that would put a second JSON stack in the native image. `vertx-core` and `junit-jupiter` are
pinned outright rather than imported from the Quarkus BOM — importing a platform BOM to learn two
version numbers would be the only Quarkus fact in a repository whose whole point is not having one.

## The release rhythm this forces

This is a **released artifact**, and that changes how harness work is done.

    1. change the harness here, prove it in this repository's suite
    2. release this repository        → a CalVer, e.g. 2026.909.40000
    3. bump and release qits-projects-daemon
    4. bump and release qits-workspace-daemon

The daemons depend on a *released* coordinate, so a daemon runs the library version it was built
against, and there is no way to change shared harness behaviour and a daemon in one commit. That is
the cost of unifying and it is deliberate — but it has one consequence worth planning for:

> **This repository's own suite is where harness behaviour is proven**, because a daemon can no
> longer prove it in the same commit as the change. A harness change that arrives without a test
> here arrives untested, and the daemon bump that follows will not catch it.

Versions are CalVer, stamped into every pom by the release. Each pom carries the literal rather than
deriving it, the same convention as `qits-registries-javalib`, so the stamp rewrites one line per
pom and `deploy` at the root publishes both modules together.

## Building

    ./mvnw -B -ntp verify

Inside a CI step container, with the platform's Maven mirror wiring:

    ./mvnw -B -ntp -s .qits-maven-settings.xml verify

That second command is what the `maven-library` release archetype runs for the gating build on a
release request; see `.config/qits/release.yml`.
