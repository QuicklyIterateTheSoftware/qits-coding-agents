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
| `AgentMcpServers` | `serversFor(scope)` — the platform MCP servers a launch attaches: the central `qits` server when the host has an address for it (`QITS_PLATFORM_MCP_URL`), else none — and `serverFor(key, …)`, one server by key for a surface's configuration. The scope parameter is ignored and stays only because `AgentMcpScope` is on the launch wire (qits-1174). |
| `AgentMcpServers.localServers()` | Servers that run as a process inside the agent's own container (`LocalMcp`: a key, a command the image ships, a pre-approval list), rendered as `{"type":"stdio","command":…}` after the url servers. Empty by default. Claude only: Kimi's ACP session carries url servers alone. The workspace daemon attaches the image's browser this way. |
| `AgentDefaults` | The instance-level preferences a launch falls back on: default harness, activity tracking, refinement model — and, since the configuration epic, the mounted per-surface configuration document (`surfaceConfigurations()`, read once at boot from a path the host passes in) and the container's own ambient facts (`ambientFacts()`, which fill an initial prompt's `{{epic}}`/`{{workspace}}`/`{{ticket}}`). |
| `ActionResolver` | The action table, read from the checkout's own `.qits-config.yml`. |
| `AgentCommands` | The command-launching surface `AgentLaunchService` spawns through. |

Since qits-1174 agents get **one** platform MCP server, `qits` — the central platform-access MCP
server, scoped by the caller's own bearer — plus whatever runs inside the container (the workspace
image's browser). The per-service servers `repository`, `observability` and `actions` are retired.
`AgentMcpScope` remains only because it sits on the launch wire.

`AgentLaunchService` renders whatever the host hands it: it attaches each server, read-only marks
the URLs (`agentReadOnly=true`) for an autonomous run or a `readOnly` attachment, and strips the
`mcp__<key>__` prefix for Kimi. The fence lives in the central `qits` server: with the marker it
lists and runs only commands marked as reads, and fails closed. Both hosts are reproduced as fixtures in this repository's test
scope (`ProjectHostMcpServers`, `WorkspaceHostMcpServers`) so their rendered commands stay asserted
**here**, where the harness lives.

## What a session runs as

A session is keyed by its **surface** — where in the product it was started from (`AgentSurface`:
`project.work`, `epic.chat`, `epic.agent`, `workspace.chat`, `workspace.agent`, `epic.autonomous`,
`ticket.dispatch`). `project.work` is the merged epics+tickets front desk's surface; it replaced
`project.epics` and `project.tickets`, which retired once the estate turned over onto it (qits-404).
The vocabulary is open: adding another is a constant and a shipped default beside it, not a migration.
An unknown surface is refused like an unknown scope; a
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

The **document** says which platform servers attach, in which order, with which pre-approval; the
**host** says how a key becomes a url. A configured attachment that names a key the host does not
serve **refuses the launch** — a session missing a server it was configured with looks normal and
cannot do its job — with two exceptions that are skipped and noted on the launch record instead: a
retired server (`AgentConfigurationDocument.RETIRED_SERVER_KEYS`), which stored surfaces still name
until qits-projects cleans them up, and `qits` on a host with no address for it. Skipping removes
access and grants none. The attachment's `narrow*` flags are still read but change nothing.

Beside the platform's own server, a surface can attach **external MCP servers** from the
catalog qits-projects holds. They arrive in the document fully rendered — url and header value
already resolved, so a container needs no second lookup — and they render into Claude's single
`--strict-mcp-config --mcp-config` object and onto Kimi's ACP `session/new`, after the built-ins,
because both harnesses interpolate the serialized form into a shell argument the suites assert
literally. Three rules travel with them:

- `qits` and the retired `repository`, `actions` and `observability` are **reserved**, checked again
  at render and not only at the store's write door. An external entry under one of those names does
  not attach twice, it displaces the platform's own server in the one key-to-config object, and the
  session looks entirely normal while talking to somebody else's. The retired names stay reserved
  until stored surfaces and older daemons stop using them. `qits` (qits-630) is the central
  platform-access MCP server — the `qits` CLI served over HTTP — rendered with a `headersHelper`
  (`McpServers.httpMcpWithHeadersHelper`, interim until qits-684) and pre-approved on Claude
  (`mcp__qits__*`);
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

The reactor root also fixes `project.build.outputTimestamp`, so a build of the same commit always
produces the same jar bytes. That matters because a retry of a half-published release must upload
identical bytes: the store accepts a re-upload of a released version only when the bytes match, and
without a fixed timestamp each build stamps a new one into the jar, so a retry after a failed step
(for example, an SBOM submission failing after the jars already deployed) would fail `mvn deploy`
with a 403 rather than a harmless no-op re-upload.

## Building

    ./mvnw -B -ntp verify

Inside a CI step container, with the platform's Maven mirror wiring:

    ./mvnw -B -ntp -s .qits-maven-settings.xml verify

That second command is what the `maven-library` release archetype runs for the gating build on a
release request; see `.config/qits/release.yml`.
