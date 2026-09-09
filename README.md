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
| `AgentMcpServers` | `serversFor(AgentMcpScope)` — which MCP servers a scope attaches, each already narrowed, each with its pre-approval list — and `serverFor(key, scope, narrowing)`, which answers one server narrowed as a surface's configuration asks. The second is what a host must implement to honour the editor's narrowing checkboxes; until it does, `honoursNarrowing()` is false and every launch that attaches servers records that its addressing was the host's. |
| `McpEndpoints` | Where an MCP server lives, and which project this container serves. |
| `AgentDefaults` | The instance-level preferences a launch falls back on: default harness, activity tracking, refinement model — and, since the configuration epic, the mounted per-surface configuration document (`surfaceConfigurations()`, read once at boot from a path the host passes in) and the container's own ambient facts (`ambientFacts()`, which fill an initial prompt's `{{epic}}`/`{{workspace}}`/`{{ticket}}`). |
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

## What a session runs as

A session is keyed by its **surface** — where in the product it was started from (`AgentSurface`:
`project.epics`, `project.tickets`, `epic.chat`, `epic.agent`, `workspace.chat`, `workspace.agent`,
`epic.autonomous`, `ticket.dispatch`). The vocabulary is open: adding a ninth is a constant and a
shipped default beside it, not a migration. An unknown surface is refused like an unknown scope; a
*missing* one resolves to what the request's shape implies, and that guess is a dated migration
crutch rather than a contract.

Each surface's configuration — harness, model, effort, remote control, permission mode, activity
tracking, system prompt, initial prompt, and which MCP servers attach — is stored and edited in
qits-projects and mounted into a container as one JSON document when it is created.
`AgentConfigurationDocument.readFrom(path)` reads it at boot. **Absent is not broken**: no document
means a container created before this shipped, and the launch falls back to the constants this
library still ships. A *malformed* one throws at boot naming the offending key. The document is at
**version 2**, whose surface entry wraps the configuration beside the fully rendered external
servers; version 1's flat entry is still read, because a container created between those releases
keeps what it was born with for its whole life.

Three of those knobs are worth a sentence each:

- **model and effort** render on every surface, honestly per harness. Claude takes `--model` and
  `--effort`; Kimi takes `-m`, has no effort concept at all, and a configured effort renders nothing
  and is *reported* on the launch record rather than passed as an unknown flag or dropped in silence;
- **remote control** is one knob and two mechanisms, the opposite way round from the intuition. A
  chat asks for its bridge over the SDK control channel (`--remote-control` is dropped under
  `--print`); an interactive launch takes `--remote-control "qits <surface> <branch>"`, named so a
  platform session is not one hostname among many in the claude.ai session list. It is never
  validated against the launch shape: when the knob is on, the flag is set;
- the **initial prompt** is the session's own first turn, before anything the caller composed, and is
  templated over the container's ambient facts (`{{project}}`, `{{epic}}`, `{{repository}}`,
  `{{branch}}`, `{{workspace}}`, `{{ticket}}`, `{{commit}}`) with an unresolvable name left literal.
  The task-prompt bootstrap sentence the two composed runs push is exactly that value, seeded per
  surface by the store; the host's own sentence stays as the fallback for a container with no
  document.

The line through the MCP wiring is worth stating, because it crosses the `AgentMcpServers` seam:

- the **document** says which servers attach, in which order, with which pre-approval, and whether
  they are read-only fenced. Policy, edited in one place, travelling with the container;
- the **host** says how a server key becomes a url at a given `AgentMcpScope`. Addressing, needing
  the container's own project/repository/workspace ids, which this library deliberately does not
  have.

So a configured attachment is looked up in what the host offers for the launch's scope. A
configuration naming a server the host does not serve at that scope **refuses the launch** rather
than dropping it silently — a session missing a server it was configured with looks entirely normal
and simply cannot do half its job. The attachment's `narrow*` flags cross the same seam, through
`AgentMcpServers.serverFor(key, scope, narrowing)`: the host builds the url with exactly the
narrowing the document asked for, in the canonical `projectId`, `repositoryId`, `workspaceId` order,
refusing a narrowing it cannot satisfy rather than dropping the parameter. Its default
implementation ignores the narrowing and answers the scope's own mapping, so a daemon that has not
adopted the seam keeps rendering what it rendered — flagged, not silent: `honoursNarrowing()` is
false and each such launch records a note saying its addressing was the host's rather than the
document's.

Beside the platform's own three servers, a surface can attach **external MCP servers** from the
catalog qits-projects holds. They arrive in the document fully rendered — url and header value
already resolved, so a container needs no second lookup — and they render into Claude's single
`--strict-mcp-config --mcp-config` object and onto Kimi's ACP `session/new`, after the built-ins,
because both harnesses interpolate the serialized form into a shell argument the suites assert
literally. Three rules travel with them:

- `repository`, `actions` and `observability` are **reserved**, checked again at render and not only
  at the store's write door. An external entry under one of those names does not attach twice, it
  displaces the platform's own server in the one key-to-config object, and the session looks entirely
  normal while talking to somebody else's;
- a **header value is never stored**. The process is spawned with the script as rendered; the command
  keeps that script with each header value replaced (`AgentLaunchMetadata.redact`), the launch record
  names attached servers **by key**, and `AgentExternalMcpServer.toString` redacts;
- url transport only, because Kimi carries servers protocol-native over ACP and has no place for a
  stdio command.

Each launch **records what it ran with** on the command — surface, harness, model, effort, permission
mode, remote control, activity tracking, the attached servers with their fences, and whatever the
harness could not render. That is what makes "a container keeps the document it was born with, and an
edit applies to the next container" a safe rule rather than an opaque one: the store can be edited at
any time, so a session that behaved oddly last week is unreadable off anything else.

Beside "render a launch", the library **reports what a harness can be configured with**
(`HarnessCapabilities`, via `HarnessCapabilityService`): its models, its effort levels or the fact
that it has none, its version, and whether anybody is signed in on the shared credential volume. It
is produced by running the binaries once at container start, off the request path, because the
binaries live in the image and the editor is a platform-wide route with no container in front of it.
Every probe command sits beside its parser with a fixture of the binary's real output, so a harness
upgrade that changes its help text fails a test rather than quietly emptying the editor's dropdowns.
The report says which values it **enumerated**, not which are legal — a launch renders whatever
string the configuration holds.

Authentication is part of that report, and a launch against a harness nobody has signed in
**refuses** (`AgentNotSignedInException`) instead of quietly becoming the sign-in terminal it used to
return. `launchLogin` stays as a door; what went is the substitution.

A container keeps what it was born with. An edit applies to the next container; that is not surfaced
anywhere, and it is what keeps the launch path a pure local render with no runtime dependency on the
store.

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
