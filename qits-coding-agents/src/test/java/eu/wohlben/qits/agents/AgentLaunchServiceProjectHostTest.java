package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.acp.AcpSessionConfig;
import eu.wohlben.qits.commands.AgentLaunchMetadata;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.AgentSessionSource;
import eu.wohlben.qits.commands.ChatProtocol;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.CheckoutContext;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launch hub as <b>the projects daemon</b> drives it, against a recording {@link AgentCommands}
 * and {@link ProjectHostMcpServers}.
 *
 * <p>These were {@code @QuarkusTest}s with twelve injected beans and a database. What is worth
 * keeping from them is the half that still exists: the MCP scope narrowing, the read-only marking and
 * allowlists, the credential overlay, the session lineage, and the auth redirect. Those translate
 * directly.
 *
 * <p>There are two of these suites — this one and {@link AgentLaunchServiceWorkspaceHostTest} —
 * because there were two copies of the class under test, one per daemon, and the move is only
 * behaviour-free if <em>both</em> renderings still hold. They overlap heavily in the parts that were
 * never the difference (session lineage, auth, transcripts); that overlap is the price of proving
 * the union rather than a winner.
 */
class AgentLaunchServiceProjectHostTest {

  private static final String REPO = "qits-qits";
  private static final String PROJECT = "22222222-2222-2222-2222-222222222222";
  private static final String CLAUDE_MOUNT = "/claude-home";
  private static final int HOOKS_PORT = 13337;
  private static final String KIMI_SESSION = "session_aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  /**
   * The bootstrap sentence this daemon passes in. The projects copy of the class said "for this
   * project" where the workspace copy said "for this workspace"; neither is wrong for its host, so
   * the sentence became a constructor argument and both literals survive — this one here, the other
   * as the library's shipped default.
   */
  private static final String PROJECT_TASK_PROMPT_BOOTSTRAP =
      "Fetch the current task prompt for this project with the taskPrompt tool, then implement what"
          + " it describes.";

  @TempDir Path workspaceRoot;

  /**
   * Where the launch service thinks the credential volume mounts. {@link #CLAUDE_MOUNT} for every
   * test that only reads it back; a temp dir for one that puts a transcript on it.
   */
  private String claudeMount;

  private Commands commands;
  private boolean loggedIn;
  private AgentType defaultType;
  private boolean activityTracking;

  /**
   * What this container was created with. {@link AgentSurfaceConfigurations#shipped()} is a
   * container born before the configuration document existed, which is what every test but the
   * equivalence suite runs as.
   */
  private AgentSurfaceConfigurations configurations;

  /** What this container knows about itself, for an initial prompt's placeholders. */
  private Map<String, String> ambientFacts;

  /** The qualified ticket or epic id this container was created for, or empty for most tests. */
  private Optional<String> entityId;

  /** Whether that entity was BLOCKED when this container booted. */
  private boolean entityBlocked;

  /** That entity's title and status when this container booted, or empty. */
  private Optional<String> entityTitle;

  private Optional<String> entityStatus;

  /** The work-links answers; unset ({@code null}) leaves the interface's defaults in charge. */
  private Boolean workLinks;

  private List<String> workLinkProjects;
  private Optional<String> platformDomain;

  @BeforeEach
  void setUp() {
    commands = new Commands();
    claudeMount = CLAUDE_MOUNT;
    loggedIn = true;
    defaultType = AgentType.CLAUDE;
    activityTracking = true;
    configurations = AgentSurfaceConfigurations.shipped();
    ambientFacts = Map.of("project", "qits", "repository", REPO);
    entityId = Optional.empty();
    entityBlocked = false;
    entityTitle = Optional.empty();
    entityStatus = Optional.empty();
    workLinks = null;
    workLinkProjects = null;
    platformDomain = null;
  }

  // --- fakes ------------------------------------------------------------------------------------

  /** Records every launch instead of spawning one. */
  private static final class Commands implements AgentCommands {
    private final List<Launch> launches = new ArrayList<>();
    private final Map<String, String> ownedSessions = new HashMap<>();
    private final List<String> chatSends = new ArrayList<>();
    private final List<String> keystrokes = new ArrayList<>();

    /**
     * Whether a chat launch builds its transport the way the real commands layer does — inside the
     * spawn, before the launch returns. Off by default: most tests read the recorded factory and
     * would not want a protocol built behind their back.
     */
    private boolean spawnTransports;

    /** The chats a {@link #chatRename} reaches; one that is not here has ended. */
    private final java.util.Set<String> liveChats = new java.util.HashSet<>();

    /** Every {@code chatRename} that reached a live chat, as {@code "<commandId> <name>"}. */
    private final List<String> renames = new ArrayList<>();

    private record Launch(
        String name,
        String script,
        boolean interactive,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef session,
        ChatProtocolFactory protocolFactory,
        AgentLaunchMetadata agent,
        CommandKind kind) {}

    private Launch last() {
      return launches.get(launches.size() - 1);
    }

    private Command record(Launch launch) {
      launches.add(launch);
      return Command.running(
          launch.commandId() == null ? "generated" : launch.commandId(),
          launch.kind(),
          "main",
          "abc1234",
          null,
          launch.name(),
          launch.script(),
          launch.interactive(),
          launch.agent().agentType(),
          launch.agent().agentSurface(),
          launch.agent().launchRecord(),
          Instant.now());
    }

    @Override
    public Command launchAgent(
        String name,
        String script,
        boolean interactive,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef agentSession,
        CommandExitListener onExit,
        AgentLaunchMetadata agent) {
      return record(
          new Launch(
              name,
              script,
              interactive,
              environment,
              commandId,
              agentSession,
              null,
              agent,
              CommandKind.TERMINAL));
    }

    @Override
    public Command launchChat(
        String name,
        String script,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef agentSession,
        CommandExitListener onExit,
        ChatProtocolFactory protocolFactory,
        AgentLaunchMetadata agent) {
      if (spawnTransports && protocolFactory != null) {
        protocolFactory.create(new SilentProcess());
        liveChats.add(commandId);
      }
      return record(
          new Launch(
              name,
              script,
              false,
              environment,
              commandId,
              agentSession,
              protocolFactory,
              agent,
              CommandKind.CHAT));
    }

    @Override
    public boolean chatSend(String commandId, String text) {
      chatSends.add(text);
      return true;
    }

    @Override
    public boolean sendKeystrokes(String commandId, String text) {
      if (endedTerminals.contains(commandId)) {
        return false;
      }
      keystrokes.add(text);
      keystrokesTo.add(commandId + " " + text);
      return true;
    }

    /** Terminals whose person left an unsent draft in the input line. */
    private final java.util.Set<String> drafts = new java.util.HashSet<>();

    /** Terminals that have ended: a keystroke write to one answers false. */
    private final java.util.Set<String> endedTerminals = new java.util.HashSet<>();

    /** Every keystroke write that landed, as {@code "<commandId> <text>"}. */
    private final List<String> keystrokesTo = new ArrayList<>();

    @Override
    public boolean hasDraft(String commandId) {
      return drafts.contains(commandId);
    }

    @Override
    public boolean chatRename(String commandId, String name) {
      if (!liveChats.contains(commandId)) {
        return false;
      }
      renames.add(commandId + " " + name);
      return true;
    }

    @Override
    public void reportAgentSession(String commandId, String sessionId, String transcriptPath) {}

    @Override
    public boolean ownsSession(String sessionId) {
      return ownedSessions.containsKey(sessionId);
    }

    @Override
    public Optional<String> agentTypeForSession(String sessionId) {
      return Optional.ofNullable(ownedSessions.get(sessionId));
    }
  }

  /** A process that is never started: a transport may wrap its stdin, and nothing is ever read. */
  private static final class SilentProcess extends Process {
    @Override
    public java.io.OutputStream getOutputStream() {
      return java.io.OutputStream.nullOutputStream();
    }

    @Override
    public java.io.InputStream getInputStream() {
      return java.io.InputStream.nullInputStream();
    }

    @Override
    public java.io.InputStream getErrorStream() {
      return java.io.InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {}
  }

  private static final CheckoutContext CHECKOUT_CONTEXT =
      new CheckoutContext() {
        @Override
        public String branch() {
          return "main";
        }

        @Override
        public String commitHash() {
          return "abc1234";
        }
      };

  /** This daemon's servers: the central {@code qits} server, and nothing else. */
  private static final AgentMcpServers MCP_SERVERS = new ProjectHostMcpServers();

  /** The live transcript tail of the last {@link #serviceWith} service. */
  private AgentTranscriptTailService tail;


  private AgentLaunchService service() {
    return serviceWith(MCP_SERVERS);
  }

  private AgentLaunchService serviceWith(AgentMcpServers mcpServers) {
    ProcessRunner probe =
        (command, cwd, env, timeout) ->
            new ProcessRunner.Result(loggedIn ? 0 : 1, loggedIn ? "" : "signed out", "", false);
    AgentDefaults defaults =
        new AgentDefaults() {
          @Override
          public AgentType defaultAgentType() {
            return defaultType;
          }

          @Override
          public boolean activityTrackingEnabled() {
            return activityTracking;
          }

          @Override
          public Optional<String> refinementModel() {
            return Optional.empty();
          }

          @Override
          public AgentSurfaceConfigurations surfaceConfigurations() {
            return configurations;
          }

          @Override
          public Map<String, String> ambientFacts() {
            return ambientFacts;
          }

          @Override
          public Optional<String> entityId() {
            return entityId;
          }

          @Override
          public boolean entityBlocked() {
            return entityBlocked;
          }

          @Override
          public Optional<String> entityTitle() {
            return entityTitle;
          }

          @Override
          public Optional<String> entityStatus() {
            return entityStatus;
          }

          @Override
          public boolean workLinksEnabled() {
            return workLinks == null ? AgentDefaults.super.workLinksEnabled() : workLinks;
          }

          @Override
          public List<String> workLinkProjects() {
            return workLinkProjects == null
                ? AgentDefaults.super.workLinkProjects()
                : workLinkProjects;
          }

          @Override
          public Optional<String> platformDomain() {
            return platformDomain == null ? AgentDefaults.super.platformDomain() : platformDomain;
          }
        };
    CommandStore store = new CommandStore();
    AgentTranscriptService transcripts =
        new AgentTranscriptService(
            store,
            new CommandLogService(store, null),
            new AgentSessionStore(),
            workspaceRoot.toString(),
            null);
    tail = new AgentTranscriptTailService(transcripts, new CommandLogService(store, null));
    return new AgentLaunchService(
        commands,
        new AgentAuthStatus(probe, claudeMount, workspaceRoot),
        transcripts,
        tail,
        defaults,
        mcpServers,
        CHECKOUT_CONTEXT,
        claudeMount,
        HOOKS_PORT,
        PROJECT_TASK_PROMPT_BOOTSTRAP);
  }

  private static AgentLaunchRequest chat(AgentMcpScope scope) {
    // The surface is required now — there is no shape-implied guess left to lean on — so the
    // scope-only helper names this host's default desk explicitly. It steers with nothing, which is
    // why every launch through here still renders what it rendered before the axis existed.
    return chat(scope, AgentSurface.PROJECT_WORK);
  }

  private static AgentLaunchRequest chat(AgentMcpScope scope, AgentSurface surface) {
    return new AgentLaunchRequest(
        scope, surface, AgentLaunchMode.CHAT, null, null, false, false, null);
  }

  // --- MCP scoping ------------------------------------------------------------------------------

  @Nested
  class McpScoping {

    @Test
    void theOnlyPlatformServerIsQits() {
      // qits-1174: the per-service servers are retired. --strict-mcp-config is what stops the
      // shared /claude-home volume putting any of them back.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(script.contains("--strict-mcp-config"), script);
      assertEquals(1, script.split("\"mcpServers\"", -1).length - 1, script);
      assertTrue(script.contains("\"qits\":{\"type\":\"http\""), script);
      assertFalse(script.contains("\"repository\":"), script);
      assertFalse(script.contains("\"actions\":"), script);
      assertFalse(script.contains("\"observability\":"), script);
    }

    @Test
    void aHostWithNoAddressForQitsStillLaunches() {
      AgentLaunchService service =
          serviceWith(new ProjectHostMcpServers(Optional.empty(), Map.of()));
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();

      assertFalse(script.contains("\"qits\":"), script);
    }
  }

  // --- rendering --------------------------------------------------------------------------------

  @Nested
  class Rendering {

    @Test
    void aChatCarriesTheScopedServerTheHomeOverlayAndTheHook() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec =
          service.renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE);

      assertTrue(spec.script().contains("--input-format stream-json"));
      assertTrue(spec.script().contains(ProjectHostMcpServers.PLATFORM_URL), spec.script());
      assertTrue(spec.script().contains("--dangerously-skip-permissions"));
      assertEquals(CLAUDE_MOUNT, spec.environment().get("HOME"), "the one credential that crosses in");
      assertTrue(
          spec.script().contains("127.0.0.1:" + HOOKS_PORT + "/hooks/claude-code?commandId="),
          spec.script());
    }

    @Test
    void theHookUrlUsesTheInjectedPortRatherThanASecondConfigRead() {
      AgentLaunchService service =
          new AgentLaunchService(
              commands, null, null, null, null, MCP_SERVERS, CHECKOUT_CONTEXT, CLAUDE_MOUNT, 24680);

      assertEquals(
          "http://127.0.0.1:24680/hooks/claude-code?commandId=c1", service.sessionReportUrl("c1"));
    }

    @Test
    void anAutonomousRunMarksEveryServerReadOnly() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec =
          service.renderAutonomousChat(
              AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE);

      assertEquals(
          1,
          spec.script().split("agentReadOnly=true", -1).length - 1,
          "every server is fenced, or the unattended turn could mutate through MCP");
    }

    @Test
    void anInteractiveLaunchEmbedsTheSeedAndRendersTheRepl() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec =
          service.renderInteractive(
              AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, "do the thing", pinned, AgentType.CLAUDE);

      assertTrue(spec.script().startsWith("exec claude 'do the thing'"), spec.script());
      assertTrue(spec.interactive());
    }

    @Test
    void onlyTheProjectDeskCompactsLate() {
      // The front desk is one long-lived, resumed session; every other surface — a refinement room
      // among them — keeps the harness's own threshold.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String desk =
          service
              .renderInteractive(
                  AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, null, pinned, AgentType.CLAUDE)
              .script();
      String deskChat =
          service
              .renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();
      String room =
          service
              .renderChat(AgentMcpScope.REPOSITORY, AgentSurface.EPIC_CHAT, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(desk.contains(" --autocompact '400k'"), desk);
      assertTrue(deskChat.contains(" --autocompact '400k'"), deskChat);
      assertFalse(room.contains("--autocompact"), room);
    }

    @Test
    void kimiTakesNoHomeOverlay() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      LaunchSpec spec =
          service.renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned, AgentType.KIMI);

      assertFalse(
          spec.environment().containsKey("HOME"), "Kimi reads KIMI_CODE_HOME, set container-wide");
      assertEquals("exec kimi acp", spec.script());
    }

    @Test
    void anInteractiveLaunchCarriesTheHostsWorkLinks() {
      workLinkProjects = List.of("acme", "beta");
      platformDomain = Optional.of("example.test");
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String interactive =
          service
              .renderInteractive(
                  AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, "go", pinned, AgentType.CLAUDE)
              .script();
      io.vertx.core.json.JsonObject settings = ClaudeWorkLinksTest.settingsOf(interactive);

      assertTrue(settings.getJsonObject("hooks").containsKey("SessionStart"), interactive);
      assertTrue(settings.getJsonObject("hooks").containsKey("MessageDisplay"), interactive);
      assertTrue(settings.containsKey("statusLine"), interactive);
      assertEquals(
          "https://qits.example.test/projects/beta/work/detail/{key}",
          settings.getJsonArray("footerLinksRegexes").getJsonObject(1).getString("url"));

      String chat =
          service
              .renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();
      assertFalse(chat.contains("MessageDisplay"), chat);
      assertFalse(chat.contains("footerLinksRegexes"), chat);
    }

    @Test
    void aHostThatTurnsWorkLinksOffGetsTheBareSettings() {
      workLinks = false;
      workLinkProjects = List.of("acme");
      platformDomain = Optional.of("example.test");
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String interactive =
          service
              .renderInteractive(
                  AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, "go", pinned, AgentType.CLAUDE)
              .script();

      assertTrue(interactive.contains("\"SessionStart\""), interactive);
      assertFalse(interactive.contains("MessageDisplay"), interactive);
      assertFalse(interactive.contains("statusLine"), interactive);
      assertFalse(interactive.contains("footerLinksRegexes"), interactive);
    }

    @Test
    void activityTrackingOffStillWiresTheLineageHook() {
      activityTracking = false;
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(script.contains("\"SessionStart\""), "lineage is not optional");
      assertFalse(script.contains("\"UserPromptSubmit\""), "the turn-boundary hooks are");
    }

    @Test
    void theLoginTerminalOverlaysTheRightHomeForEachHarness() {
      assertEquals(CLAUDE_MOUNT, service().renderLogin(AgentType.CLAUDE).environment().get("HOME"));
      assertEquals("exec claude", service().renderLogin(AgentType.CLAUDE).script());
      assertEquals(
          CLAUDE_MOUNT + "/.kimi-code",
          service().renderLogin(AgentType.KIMI).environment().get("KIMI_CODE_HOME"));
      assertEquals("exec kimi login", service().renderLogin(AgentType.KIMI).script());
    }
  }

  // --- the surfaces -----------------------------------------------------------------------------

  @Nested
  class Surfaces {

    @Test
    void theProjectDeskAppendsNothingAtAll() {
      // The equivalence the steering axis was added on: project.work renders what the surfaces it
      // replaced rendered when they steered with nothing, which is what the launch rendered before
      // any of them existed.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      assertNull(AgentLaunchService.systemPromptFor(AgentSurface.PROJECT_WORK));
      assertFalse(
          service
              .renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script()
              .contains("--append-system-prompt"));
      assertFalse(
          service
              .renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, null, pinned, AgentType.CLAUDE)
              .script()
              .contains("--append-system-prompt"));
    }

    @Test
    void theComposedRunCarriesTheOrchestrationPrompt() {
      // epic.autonomous did all its coding in the main loop because it shipped nothing at all.
      // Nobody is in that conversation to say "delegate that", so the steering has to be shipped.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      assertEquals(
          AgentLaunchService.COMPOSED_RUN_PROMPT,
          AgentLaunchService.systemPromptFor(AgentSurface.EPIC_AUTONOMOUS));

      String script =
          service
              .renderAutonomousChat(
                  AgentMcpScope.REPOSITORY, AgentSurface.EPIC_AUTONOMOUS, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(
          script.contains("--append-system-prompt 'You are orchestrating this run rather than"),
          script);
      assertTrue(script.contains("hand each one to a subagent"), script);
      assertTrue(script.contains("Delegating the work does not delegate the verification."), script);
    }

    @Test
    void theProjectDeskRendersTheNameItAlwaysHad() {
      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      assertEquals("Claude Code", commands.last().name(), "and it is named as before");
    }

    @Test
    void theSurfaceComesBackOnTheCommand() {
      // The command says which surface it is, as a field, in the launch's own answer.
      Command work = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      assertEquals("project.work", work.agentSurface());

      Command terminal =
          service()
              .launch(
                  new AgentLaunchRequest(
                      AgentMcpScope.PROJECT,
                      AgentSurface.PROJECT_WORK,
                      AgentLaunchMode.INTERACTIVE,
                      null,
                      null,
                      false,
                      false,
                      null));
      assertEquals("project.work", terminal.agentSurface());
    }

    @Test
    void theSignInTerminalCarriesNoSurface() {
      // Nobody starts a sign-in terminal from anywhere in the product, and keying it to a surface
      // would render that surface's configuration into a REPL that must render nothing.
      assertNull(service().launchLogin(AgentType.CLAUDE).agentSurface());
    }

    @Test
    void anAutonomousRunNamesItsOwnSurfaceRatherThanBorrowingTheProjectDesk() {
      // A different session from a human's project-desk chat — read-only marked servers, a
      // bootstrap seed, nobody watching. It borrowed AgentDesk.EPICS only because there was nothing
      // else to name, back when this was AgentDesk rather than AgentSurface.
      Command command = service().launchAutonomous("Composed run");

      assertEquals("epic.autonomous", command.agentSurface());
      assertEquals("Composed run", command.actionName(), "the caller still names it");
    }

    @Test
    void anUnknownSurfaceIsRefusedAndAKnownOneIsNot() {
      // Like an unknown scope: a misspelled surface that fell through to a default would be a
      // misconfigured caller that looks like a working one.
      assertEquals(AgentSurface.EPIC_CHAT, AgentSurface.of("epic.chat"));
      assertEquals(AgentSurface.EPIC_CHAT, AgentSurface.of("  EPIC.CHAT "));
      assertThrows(InvalidCommandRequestException.class, () -> AgentSurface.of("project.epic"));
      assertThrows(InvalidCommandRequestException.class, () -> AgentSurface.of(""));
      assertEquals(Optional.empty(), AgentSurface.parse(null));
      assertEquals(8, AgentSurface.KNOWN.size());
    }

    @Test
    void theDeliveryDeskResolvesAndShipsWhatTheFrontDeskShips() {
      // Its own surface, because the projects daemon tells its live sessions apart by surface; its
      // shipped defaults are project.work's — no prompts, remote control on — under its own key.
      assertEquals(AgentSurface.PROJECT_DELIVERY, AgentSurface.of("project.delivery"));
      assertTrue(AgentSurface.KNOWN.contains(AgentSurface.PROJECT_DELIVERY));

      AgentSurfaceConfiguration delivery =
          AgentSurfaceConfigurations.shippedFor(AgentSurface.PROJECT_DELIVERY, null, true);
      AgentSurfaceConfiguration work =
          AgentSurfaceConfigurations.shippedFor(AgentSurface.PROJECT_WORK, null, true);
      assertEquals("project.delivery", delivery.surface());
      assertEquals("", delivery.systemPrompt());
      assertEquals("", delivery.initialPrompt());
      assertTrue(delivery.remoteControl());
      assertEquals(work.harness(), delivery.harness());
      assertEquals(work.model(), delivery.model());
      assertEquals(work.permissionMode(), delivery.permissionMode());
    }

    @Test
    void theDeliveryDeskIsNamedAsADeskNotByItsKey() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      assertTrue(
          service
              .renderInteractive(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_DELIVERY,
                  null,
                  pinned,
                  AgentType.CLAUDE)
              .script()
              .contains("--remote-control '" + AgentRemoteControl.DELIVERY_DESK_NAME + "'"));
    }

    @Test
    void theTwoRetiredSurfacesAreRefusedAndTheMergedFrontDeskResolves() {
      // project.work is the merged epics+tickets desk's surface. project.epics and project.tickets
      // retired once the estate turned over onto it (qits-404): both now refuse like any other
      // unknown key rather than resolving to what they used to name.
      assertEquals(AgentSurface.PROJECT_WORK, AgentSurface.of("project.work"));
      assertThrows(InvalidCommandRequestException.class, () -> AgentSurface.of("project.epics"));
      assertThrows(InvalidCommandRequestException.class, () -> AgentSurface.of("project.tickets"));
      assertEquals(Optional.empty(), AgentSurface.parse("project.epics"));
      assertEquals(Optional.empty(), AgentSurface.parse("project.tickets"));
    }

    @Test
    void aMissingSurfaceIsRefusedRatherThanGuessed() {
      // There was a shape-implied guess here for one release, so the daemons could ship ahead of
      // the frontends: a PROJECT-scoped launch read as the epics desk, anything else as a workspace
      // chat or agent tab. Both frontends send the key now, so the guess is gone — it collapsed
      // epic.* onto workspace.*, which is the very distinction this axis exists to draw, and a
      // caller that forgot the key looked exactly like one that meant the default.
      assertThrows(
          InvalidCommandRequestException.class,
          () -> chat(AgentMcpScope.PROJECT, null).requiredSurface());
      assertThrows(
          InvalidCommandRequestException.class,
          () ->
              new AgentLaunchRequest(
                      AgentMcpScope.REPOSITORY,
                      null,
                      AgentLaunchMode.INTERACTIVE,
                      null,
                      null,
                      false,
                      false,
                      null)
                  .requiredSurface());

      // And it is refused at the door, before a harness is resolved or a credential volume read.
      assertThrows(
          InvalidCommandRequestException.class,
          () -> service().launchChat(chat(AgentMcpScope.PROJECT, null)));
      assertEquals(
          AgentSurface.PROJECT_WORK,
          chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK).requiredSurface(),
          "a named surface passes through untouched");
    }
  }

  // --- session lineage --------------------------------------------------------------------------

  @Nested
  class Lineage {

    @Test
    void aFreshClaudeLaunchPinsANewSession() {
      AgentLaunchService.PinnedSession pinned = service().pinSession(null, false, AgentType.CLAUDE);

      assertNotNull(pinned.ref());
      assertEquals(AgentSessionSource.PINNED, pinned.ref().source());
      assertNotNull(pinned.commandId());
    }

    @Test
    void aFreshKimiLaunchPinsNothingBecauseItCannot() {
      AgentLaunchService.PinnedSession pinned = service().pinSession(null, false, AgentType.KIMI);

      assertNull(pinned.ref(), "the id arrives later, on the SessionStart hook");
      assertNotNull(pinned.commandId());
    }

    @Test
    void resumeOfASessionFromAPreviousContainerWithoutATranscriptIsRefused() {
      // CommandStore does not outlive the container, so it cannot vouch for a session an earlier one
      // drove. Without a transcript on the credential volume either, it fails closed: refused.
      claudeMount = workspaceRoot.resolve("claude-home").toString();
      AgentLaunchService service = service();

      InvalidCommandRequestException refused =
          assertThrows(
              InvalidCommandRequestException.class,
              () ->
                  service.pinSession(
                      "33333333-3333-3333-3333-333333333333", false, AgentType.CLAUDE));

      assertTrue(refused.getMessage().contains("not started in this container"), refused.getMessage());
    }

    @Test
    void resumeOfASessionFromAPreviousContainerWhoseTranscriptSurvivedContinuesIt()
        throws Exception {
      // The front desk across a container recreate: the store is new, the volume is not, and the
      // harness resumes from the transcript on it.
      String session = "33333333-3333-3333-3333-333333333333";
      Path home = workspaceRoot.resolve("claude-home");
      Path transcript = home.resolve(".claude/projects/-workspace/" + session + ".jsonl");
      Files.createDirectories(transcript.getParent());
      Files.writeString(transcript, "{}\n");
      claudeMount = home.toString();

      service()
          .launchInteractive(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.INTERACTIVE,
                  null,
                  session,
                  false,
                  false,
                  null));

      assertTrue(commands.last().script().contains(" --resume " + session), commands.last().script());
      assertEquals(AgentSessionSource.RESUMED, commands.last().session().source());
      assertEquals(session, commands.last().session().sessionId());
      assertEquals("CLAUDE", commands.last().agent().agentType(), "the request's harness, resolved");
    }

    @Test
    void aSurvivingTranscriptIsLookedForUnderTheResolvedHarnessOnly() throws Exception {
      String session = "33333333-3333-3333-3333-333333333333";
      Path home = workspaceRoot.resolve("claude-home");
      Path transcript = home.resolve(".claude/projects/-workspace/" + session + ".jsonl");
      Files.createDirectories(transcript.getParent());
      Files.writeString(transcript, "{}\n");
      claudeMount = home.toString();

      assertThrows(
          InvalidCommandRequestException.class,
          () -> service().pinSession(KIMI_SESSION, false, AgentType.KIMI),
          "a Claude transcript does not vouch for a Kimi resume");
      Files.createDirectories(home.resolve(".kimi-code/sessions/abc123/" + KIMI_SESSION));
      assertEquals(
          AgentSessionSource.RESUMED,
          service().pinSession(KIMI_SESSION, false, AgentType.KIMI).ref().source());
    }

    @Test
    void resumeOfAKnownSessionContinuesItInPlace() {
      commands.ownedSessions.put("33333333-3333-3333-3333-333333333333", "CLAUDE");

      AgentLaunchService.PinnedSession pinned =
          service().pinSession("33333333-3333-3333-3333-333333333333", false, AgentType.CLAUDE);

      assertEquals(AgentSessionSource.RESUMED, pinned.ref().source());
      assertEquals("33333333-3333-3333-3333-333333333333", pinned.ref().sessionId());
    }

    @Test
    void aForkBranchesIntoAFreshIdAndRecordsItsOrigin() {
      commands.ownedSessions.put("33333333-3333-3333-3333-333333333333", "CLAUDE");

      AgentLaunchService.PinnedSession pinned =
          service().pinSession("33333333-3333-3333-3333-333333333333", true, AgentType.CLAUDE);

      assertEquals(AgentSessionSource.FORKED, pinned.ref().source());
      assertEquals("33333333-3333-3333-3333-333333333333", pinned.ref().forkedFromSessionId());
      assertFalse(pinned.ref().sessionId().equals("33333333-3333-3333-3333-333333333333"));
    }

    @Test
    void kimiRefusesToFork() {
      commands.ownedSessions.put(KIMI_SESSION, "KIMI");

      assertThrows(
          InvalidCommandRequestException.class,
          () -> service().pinSession(KIMI_SESSION, true, AgentType.KIMI));
    }

    @Test
    void aResumeKeepsTheResumedSessionsHarnessOverTheDefault() {
      // You cannot resume a Kimi session under Claude: the transcript layout and auth probe differ.
      commands.ownedSessions.put(KIMI_SESSION, "KIMI");
      defaultType = AgentType.CLAUDE;

      Command command =
          service()
              .launchChat(
                  new AgentLaunchRequest(
                      AgentMcpScope.REPOSITORY,
                      AgentSurface.PROJECT_WORK,
                      AgentLaunchMode.CHAT,
                      null,
                      KIMI_SESSION,
                      false,
                      false,
                      AgentType.CLAUDE));

      assertEquals("KIMI", commands.last().agent().agentType(), "the session's harness wins");
      assertNotNull(command);
    }

    @Test
    void aMalformedSessionIdIsARequestError() {
      commands.ownedSessions.put("not-a-uuid", "CLAUDE");

      assertThrows(
          InvalidCommandRequestException.class,
          () -> service().pinSession("not-a-uuid", false, AgentType.CLAUDE));
    }
  }

  // --- launch flow ------------------------------------------------------------------------------

  @Nested
  class Flow {

    @Test
    void aChatLaunchNamesItselfAfterTheScopeAndHarness() {
      service().launchChat(chat(AgentMcpScope.PROJECT));

      assertEquals("Claude Code", commands.last().name());
      assertEquals(CommandKind.CHAT, commands.last().kind());
      assertEquals("CLAUDE", commands.last().agent().agentType());
    }

    @Test
    void everyChatCarriesItsHarnessTransport() {
      defaultType = AgentType.KIMI;
      service().launchChat(chat(AgentMcpScope.REPOSITORY));
      assertNotNull(commands.last().protocolFactory(), "Kimi has no stdin chat mode");

      defaultType = AgentType.CLAUDE;
      service().launchChat(chat(AgentMcpScope.REPOSITORY));
      assertNotNull(
          commands.last().protocolFactory(),
          "Claude's stream-json transport is supplied too — it carries the Remote Control enable");
    }

    @Test
    void aSeedIsDeliveredAsTheFirstUserTurn() {
      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.CHAT,
                  "start here",
                  null,
                  false,
                  false,
                  null));

      assertEquals(List.of("start here"), commands.chatSends);
    }

    @Test
    void deliverTaskPromptSeedsTheBootstrapInsteadOfTheLiteral() {
      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.CHAT,
                  "ignored",
                  null,
                  false,
                  true,
                  null));

      assertEquals(
          List.of(PROJECT_TASK_PROMPT_BOOTSTRAP),
          commands.chatSends,
          "the caller owns the draft now, so its word is taken");
    }

    @Test
    void aBlankSeedSendsNothing() {
      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.CHAT,
                  "   ",
                  null,
                  false,
                  false,
                  null));

      assertTrue(commands.chatSends.isEmpty());
    }

    @Test
    void aSignedOutAgentIsRefusedRatherThanSwappedForALoginTerminal() {
      // THE behaviour this feature removes. The three launch paths used to answer launchLogin's
      // bare REPL, and the caller attached to it exactly as it would to a real session: you asked
      // for a chat about an epic, got a login terminal, and nothing in the answer said so.
      loggedIn = false;

      AgentNotSignedInException refused =
          assertThrows(
              AgentNotSignedInException.class, () -> service().launchChat(chat(AgentMcpScope.REPOSITORY)));

      assertEquals(AgentType.CLAUDE, refused.harness());
      assertTrue(refused.getMessage().contains("Claude Code"), refused.getMessage());
      assertTrue(refused.getMessage().contains("sign-in terminal"), "it names where to sign in");
      assertTrue(commands.launches.isEmpty(), "and nothing at all was launched");
    }

    @Test
    void theUnattendedPathsFailLoudlyRatherThanStartingAPromptNobodyWatches() {
      // A dispatch that "started an agent" which is actually a login prompt is green-while-dead:
      // the caller reports success, the container sits at a sign-in screen, the work never happens.
      loggedIn = false;

      assertThrows(
          AgentNotSignedInException.class, () -> service().launchAutonomous("Composed run"));
      assertThrows(
          AgentNotSignedInException.class,
          () ->
              service()
                  .launch(
                      new AgentLaunchRequest(
                          AgentMcpScope.REPOSITORY,
                          AgentSurface.PROJECT_WORK,
                          AgentLaunchMode.INTERACTIVE,
                          null,
                          null,
                          false,
                          false,
                          null)));
      assertTrue(commands.launches.isEmpty());
    }

    @Test
    void theLoginTerminalStaysReachableOnItsOwn() {
      // Somebody has to complete the OAuth once per credential volume. What went is the
      // substitution, not the door.
      loggedIn = false;

      Command login = service().launchLogin(AgentType.CLAUDE);

      assertEquals("Claude sign-in", login.actionName());
      assertEquals(CommandKind.TERMINAL, login.kind());
      assertTrue(login.interactive(), "the operator finishes OAuth over a real PTY");
      assertTrue(commands.chatSends.isEmpty(), "nothing is seeded into a login terminal");
    }

    @Test
    void anAutonomousRunAlwaysSeedsTheBootstrap() {
      service().launchAutonomous("Resolve conflicts");

      assertEquals("Resolve conflicts", commands.last().name());
      assertEquals(List.of(PROJECT_TASK_PROMPT_BOOTSTRAP), commands.chatSends);
    }

    @Test
    void anInteractiveLaunchIsATerminalCommand() {
      service()
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.INTERACTIVE,
                  null,
                  null,
                  false,
                  false,
                  null));

      assertEquals(CommandKind.TERMINAL, commands.last().kind());
      assertEquals("Claude Code terminal", commands.last().name());
    }

    @Test
    void launchDefaultsToChatWhenNoModeIsGiven() {
      service()
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_WORK,
                  null,
                  null,
                  null,
                  false,
                  false,
                  null));

      assertEquals(CommandKind.CHAT, commands.last().kind());
    }

    @Test
    void aMissingScopeAndAForkWithoutAResumeAreRequestErrors() {
      assertThrows(InvalidCommandRequestException.class, () -> service().launch(chat(null)));
      assertThrows(InvalidCommandRequestException.class, () -> service().launch(null));
      assertThrows(
          InvalidCommandRequestException.class,
          () ->
              service()
                  .launch(
                      new AgentLaunchRequest(
                          AgentMcpScope.REPOSITORY,
                          AgentSurface.PROJECT_WORK,
                          AgentLaunchMode.CHAT,
                          null,
                          null,
                          true,
                          false,
                          null)));
    }
  }

  // --- the initial prompt -----------------------------------------------------------------------

  /**
   * The configured initial prompt as the session's own first turn — the surface's sentence, written
   * once in the editor, before anything this one caller composed.
   */
  @Nested
  class InitialPrompt {

    @Test
    void itIsTheFirstTurnAndTheCallersIsTheSecond() {
      configurations = withInitialPrompt("Survey the open tickets before answering.");

      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.PROJECT,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.CHAT,
                  "what is left on the plan?",
                  null,
                  false,
                  false,
                  null));

      assertEquals(
          List.of("Survey the open tickets before answering.", "what is left on the plan?"),
          commands.chatSends,
          "the surface's turn opens the session; the caller's follows it");
    }

    @Test
    void itIsTemplatedOverWhatTheContainerKnowsAboutItself() {
      configurations = withInitialPrompt("You are in {{repository}} on {{branch}} for {{project}}.");

      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      assertEquals(
          List.of("You are in " + REPO + " on main for qits."),
          commands.chatSends,
          "the checkout answers branch; the host answers the rest");
    }

    @Test
    void aFactNobodyCanAnswerStaysAsItWasWritten() {
      configurations = withInitialPrompt("Fix {{ticket}}.");

      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      assertEquals(List.of("Fix {{ticket}}."), commands.chatSends, "literal, not null and not gone");
    }

    @Test
    void anInteractiveLaunchSeedsTheFirstOnArgvAndTypesTheSecond() {
      // A REPL has no stdin channel of its own, so the opening turn is the argv seed and anything
      // after it is keystrokes. The second turn is the failure shape rather than the normal one,
      // and it is delivered rather than dropped.
      configurations = withInitialPrompt("Read the plan first.");

      service()
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.PROJECT,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.INTERACTIVE,
                  "and then draft the epic",
                  null,
                  false,
                  false,
                  null));

      assertTrue(
          commands.last().script().startsWith("exec claude 'Read the plan first.'"),
          commands.last().script());
      assertEquals(List.of("and then draft the epic"), commands.keystrokes);
      assertTrue(commands.chatSends.isEmpty(), "a terminal has no chat channel");
    }

    @Test
    void aSurfaceWithNoInitialPromptOpensExactlyAsItDid() {
      service()
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.PROJECT,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.INTERACTIVE,
                  "do the thing",
                  null,
                  false,
                  false,
                  null));

      assertTrue(commands.last().script().startsWith("exec claude 'do the thing'"));
      assertTrue(commands.keystrokes.isEmpty(), "one turn is one turn, on argv, as before");
    }

    @Test
    void anInteractiveLaunchTailsItsTranscriptLiveLikeAChat() {
      // The host's session log and conversation view read the transcript while the run is live; the
      // PTY stream is only for the person at the terminal.
      Command command =
          service()
              .launch(
                  new AgentLaunchRequest(
                      AgentMcpScope.PROJECT,
                      AgentSurface.PROJECT_WORK,
                      AgentLaunchMode.INTERACTIVE,
                      "do the thing",
                      null,
                      false,
                      false,
                      null));

      assertTrue(tail.tailing(command.id()));
    }

    @Test
    void theTaskPromptBootstrapIsThoseSurfacesInitialPromptValue() {
      // The resolution this task settles: the sentence the two composed runs push IS an initial
      // prompt. The store seeds it per surface — each daemon's own noun — and a configured value
      // simply wins over the host's constructor argument.
      configurations =
          withInitialPrompt(
              AgentSurface.EPIC_AUTONOMOUS,
              "Fetch the current task prompt for {{project}} with the taskPrompt tool.");

      service().launchAutonomous("Composed run");

      assertEquals(
          List.of("Fetch the current task prompt for qits with the taskPrompt tool."),
          commands.chatSends);
    }

    @Test
    void theHostsSentenceIsTheFallbackForAContainerBornWithoutADocument() {
      // Not gone: it is the same rung every other shipped constant falls back to.
      service().launchAutonomous("Composed run");
      assertEquals(List.of(PROJECT_TASK_PROMPT_BOOTSTRAP), commands.chatSends);
    }

    @Test
    void deliverTaskPromptStillDecidesWhetherTheFetchIsWhatThisRunDoes() {
      // The flag keeps its own job. When it is set, the fetch instruction is the ONLY opening turn:
      // the caller's composed text is the draft the agent is about to pull over MCP, and pushing it
      // as well would deliver it twice in two shapes.
      configurations = withInitialPrompt("Read the plan first.");

      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.PROJECT,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.CHAT,
                  "ignored",
                  null,
                  false,
                  true,
                  null));

      assertEquals(List.of("Read the plan first."), commands.chatSends);
    }

    private AgentSurfaceConfigurations withInitialPrompt(String prompt) {
      return withInitialPrompt(AgentSurface.PROJECT_WORK, prompt);
    }

    private AgentSurfaceConfigurations withInitialPrompt(AgentSurface surface, String prompt) {
      return AgentSurfaceConfigurations.of(
          AgentConfigurationDocument.parse(
              new JsonObject()
                  .put("version", 1)
                  .put(
                      "surfaces",
                      new io.vertx.core.json.JsonArray()
                          .add(
                              new JsonObject()
                                  .put("surface", surface.key())
                                  .put("harness", "CLAUDE")
                                  .put("permissionMode", "SKIP_PERMISSIONS")
                                  .put("initialPrompt", prompt)))
                  .encode(),
              "test"));
    }
  }

  // --- skipped attachments ----------------------------------------------------------------------

  /**
   * An attachment the host cannot serve: a retired server, or {@code qits} on a host with no address
   * for it. The launch goes on without it, and the record says so.
   */
  @Nested
  class SkippedAttachments {

    @Test
    void aStoredSurfaceThatStillNamesARetiredServerLaunchesWithoutIt() {
      // qits-1174: the stored surfaces are cleaned up in a later release, so a stale attachment
      // must not stop a launch. It is skipped, and the record says so.
      configurations =
          attaching(
              SeededConfigurationDocument.server("repository", true, true, false, true, List.of()),
              SeededConfigurationDocument.server("qits", false, false, false, false, List.of()));

      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      String script = commands.last().script();
      assertFalse(script.contains("\"repository\":"), script);
      assertTrue(script.contains("\"qits\":"), script);
      assertTrue(
          new JsonObject(command.agentLaunchRecord())
              .getJsonArray("notes")
              .encode()
              .contains("Skipped the 'repository' MCP server"),
          command.agentLaunchRecord());
    }

    @Test
    void aSurfaceThatNamesQitsOnAHostWithNoAddressLaunchesWithoutIt() {
      // The only platform server: refusing here would stop every launch on that host.
      configurations =
          attaching(SeededConfigurationDocument.server("qits", false, false, false, false, List.of()));

      Command command =
          serviceWith(new ProjectHostMcpServers(Optional.empty(), Map.of()))
              .launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      assertFalse(commands.last().script().contains("\"qits\":"), commands.last().script());
      assertTrue(
          new JsonObject(command.agentLaunchRecord())
              .getJsonArray("notes")
              .encode()
              .contains("QITS_PLATFORM_MCP_URL"),
          command.agentLaunchRecord());
    }

    @Test
    void aSurfaceThatConfiguresNoServersIsNotNagged() {
      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      assertEquals(0, new JsonObject(command.agentLaunchRecord()).getJsonArray("notes").size());
    }

    private AgentSurfaceConfigurations attaching(JsonObject... servers) {
      return new SeededConfigurationDocument()
          .surface(AgentSurface.PROJECT_WORK, false, "", servers)
          .configurations();
    }
  }

  // --- external MCP servers ---------------------------------------------------------------------

  /**
   * The catalog's servers, rendered beside the platform's own — and the two rules that keep that
   * safe: a reserved key is refused at render as well as on write, and a header value never reaches
   * anything that is stored, logged or answered.
   */
  @Nested
  class ExternalMcpServers {

    private static final String STRIPE_TOKEN = "Bearer sk-live-not-a-real-token";

    @Test
    void theyJoinClaudesOneMcpConfigAfterThePlatformsOwn() {
      // One --strict-mcp-config object is the whole set a session may use, so an external server has
      // to be IN it — and after the built-ins, because both harnesses interpolate the serialized
      // form into a shell argument and the suites assert the command line literally.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);
      configurations = withStripe();

      String script =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(
          script.contains(
              "\"stripe\":{\"type\":\"http\",\"url\":\"https://mcp.stripe.com/v1\","
                  + "\"headers\":{\"Authorization\":\"" + STRIPE_TOKEN + "\"}}"),
          script);
      assertTrue(
          script.indexOf("\"repository\":") < script.indexOf("\"stripe\":"),
          "the platform's own first, deliberately");
      assertTrue(
          script.contains("--allowedTools 'mcp__stripe__listCustomers'"),
          "an external server's pre-approval renders: it is the first case where the permission mode"
              + " is likely to be anything but skip");
      assertEquals(1, script.split("\"mcpServers\"", -1).length - 1, "still one object");
    }

    @Test
    void aServerWithNoCredentialRendersExactlyAsAPlatformOneDoes() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);
      configurations =
          new SeededConfigurationDocument()
              .surface(AgentSurface.PROJECT_WORK, false, "")
              .attaching(
                  SeededConfigurationDocument.external(
                      "docs", "https://docs.example/mcp", "", "", List.of()))
              .configurations();

      String script =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(
          script.contains("\"docs\":{\"type\":\"http\",\"url\":\"https://docs.example/mcp\"}"), script);
      assertFalse(script.contains("headers"), "no credential means no headers key at all");
    }

    @Test
    void theyRideKimisAcpSessionWithTheirHeaders() {
      // Kimi carries servers protocol-native on session/new — which is also why the catalog is url
      // transport only: there is no place on that message for a stdio command.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);
      configurations = withStripe();

      AcpSessionConfig config =
          service.buildAcpSessionConfig(
              AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned);

      // The qits server is left out: Kimi gets it only with fixed headers (qits-625).
      assertEquals(1, config.mcpServers().size());
      AcpSessionConfig.AcpMcpServer stripe = config.mcpServers().get(0);
      assertEquals("stripe", stripe.name());
      assertEquals(Map.of("Authorization", STRIPE_TOKEN), stripe.headers());
      assertEquals(
          List.of("listCustomers"),
          stripe.enabledTools(),
          "kimi takes bare names, and an operator's list is honoured in either form");
      assertFalse(stripe.toString().contains("sk-live"), stripe.toString());
    }

    @Test
    void aReservedKeyRefusesTheLaunchAtRenderToo() {
      // The store validates on write, but a document can reach a container from an older service —
      // and a displaced 'repository' server is the silent, dangerous one: the session looks entirely
      // normal and is talking to somebody else's.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);
      configurations =
          AgentSurfaceConfigurations.of(
              new AgentConfigurationDocument(
                  2,
                  "",
                  Map.of(
                      AgentSurface.PROJECT_WORK.key(),
                      new AgentSurfaceConfiguration(
                          AgentSurface.PROJECT_WORK.key(),
                          AgentType.CLAUDE,
                          "",
                          "",
                          false,
                          AgentPermissionMode.SKIP_PERMISSIONS,
                          true,
                          "",
                          "",
                          null,
                          List.of(
                              new AgentExternalMcpServer(
                                  "repository", "https://elsewhere.example", "", "", List.of())),
                          false))));

      InvalidCommandRequestException refused =
          assertThrows(
              InvalidCommandRequestException.class,
              () ->
                  service.renderChat(
                      AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE));

      assertTrue(refused.getMessage().contains("displace it silently"), refused.getMessage());
      assertTrue(refused.getMessage().contains("project.work"), refused.getMessage());
    }

    @Test
    void theHeaderValueIsHandedOverForRedactionRatherThanStored() {
      // The rendered command line is kept on the command, answered by the API and shown on a command
      // page — which was right for every launch this platform had until a credential started riding
      // in one. The process is spawned with the script as rendered; what is stored is this.
      configurations = withStripe();

      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      AgentLaunchMetadata metadata = commands.last().agent();

      assertEquals(List.of(STRIPE_TOKEN), metadata.redactions());
      assertFalse(
          metadata.redact(commands.last().script()).contains("sk-live"),
          "what a command stores carries no credential");
      assertTrue(
          metadata.redact(commands.last().script()).contains("<redacted>"),
          "and a reader can see that something was withheld");
      assertTrue(commands.last().script().contains(STRIPE_TOKEN), "the script that RUNS is intact");
    }

    @Test
    void theLaunchRecordNamesThemByKeyAndNothingElse() {
      configurations = withStripe();

      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      JsonObject record = new JsonObject(command.agentLaunchRecord());

      assertEquals(List.of("stripe"), record.getJsonArray("externalMcpServers").getList());
      assertFalse(command.agentLaunchRecord().contains("sk-live"), command.agentLaunchRecord());
      assertFalse(command.agentLaunchRecord().contains("mcp.stripe.com"));
    }

    /** The epics desk with the platform's repository server and one catalog entry attached. */
    private AgentSurfaceConfigurations withStripe() {
      return new SeededConfigurationDocument()
          .surface(
              AgentSurface.PROJECT_WORK,
              false,
              "",
              SeededConfigurationDocument.server(
                  "repository", true, false, false, false, List.of()))
          .attaching(
              SeededConfigurationDocument.external(
                  "stripe",
                  "https://mcp.stripe.com/v1",
                  "Authorization",
                  STRIPE_TOKEN,
                  List.of("mcp__stripe__listCustomers")))
          .configurations();
    }
  }

  // --- the central qits platform MCP server (qits-630) -------------------------------------------

  /**
   * The central {@code qits} platform server — reserved as a key (see {@link
   * AgentConfigurationDocumentTest}) and pre-approved on Claude wherever it is attached, because the
   * bearer on each call already decides what it may do.
   */
  @Nested
  class QitsPlatformServer {

    private AgentMcpServers withQits() {
      return scope ->
          List.of(
              new ScopedMcp("qits", "http://dev-qits-platform-access-mcp-service:8080/mcp", List.of()));
    }

    @Test
    void claudeRendersItWithAHeadersHelperAndPreApprovesEveryTool() {
      // The defect this covers: a daemon hands the library an ordinary ScopedMcp(key "qits", url),
      // and the library alone has to know this one key needs a headers helper rather than a bare
      // url, because the central server answers 401 with nothing
      // in the --mcp-config to say why.
      AgentLaunchService service = serviceWith(withQits());
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(
          script.contains(
              "\"qits\":{\"type\":\"http\",\"url\":\"http://dev-qits-platform-access-mcp-service:8080/mcp\","
                  + "\"headersHelper\":\"qits mcp-credential\"}"),
          script);
      assertTrue(
          script.contains("--allowedTools 'mcp__qits__*'"),
          "the qits server is pre-approved on every surface: " + script);
    }

    @Test
    void anAutonomousRunMarksTheQitsUrlReadOnly() {
      // The fence lives in the central server: with agentReadOnly=true it lists and runs only
      // commands marked as reads, and fails closed. The headers-helper entry carries the marked url.
      AgentLaunchService service = serviceWith(withQits());
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderAutonomousChat(
                  AgentMcpScope.PROJECT, AgentSurface.EPIC_AUTONOMOUS, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(
          script.contains(
              "\"qits\":{\"type\":\"http\",\"url\":\"http://dev-qits-platform-access-mcp-service:8080/mcp?agentReadOnly=true\","
                  + "\"headersHelper\":\"qits mcp-credential\"}"),
          script);
    }

    @Test
    void aChatLeavesTheQitsUrlUnmarked() {
      AgentLaunchService service = serviceWith(withQits());
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();

      assertFalse(script.contains("agentReadOnly"), script);
    }

    @Test
    void kimisAutonomousSessionCarriesTheMarkedQitsUrl() {
      AgentLaunchService service = serviceWith(withQitsAndABearer());

      AcpSessionConfig config =
          service.buildAcpSessionConfig(
              AgentMcpScope.PROJECT,
              AgentSurface.EPIC_AUTONOMOUS,
              service.pinSession(null, false, AgentType.KIMI),
              true);

      assertEquals(
          "http://dev-qits-platform-access-mcp-service:8080/mcp?agentReadOnly=true",
          config.mcpServers().get(0).url());
    }

    private AgentMcpServers withQitsAndABearer() {
      Map<String, String> bearer = Map.of("Authorization", "Bearer t");
      return scope ->
          List.of(
              new ScopedMcp(
                  "qits", "http://dev-qits-platform-access-mcp-service:8080/mcp", List.of(), bearer));
    }

    @Test
    void fixedHeadersFromTheHostReplaceTheHeadersHelperAndKeepThePreApproval() {
      // qits-625: a host whose agent reaches the platform through the edge hands every platform
      // server a fixed, non-expiring bearer. On the qits key that replaces the helper outright —
      // two credential sources on one entry would leave which one Claude sends to chance.
      AgentLaunchService service = serviceWith(withQitsAndABearer());
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderAutonomousChat(
                  AgentMcpScope.PROJECT, AgentSurface.EPIC_AUTONOMOUS, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(
          script.contains(
              "\"qits\":{\"type\":\"http\",\"url\":\"http://dev-qits-platform-access-mcp-service:8080/mcp?agentReadOnly=true\","
                  + "\"headers\":{\"Authorization\":\"Bearer t\"}}"),
          script);
      assertFalse(script.contains("headersHelper"), script);
      assertTrue(script.contains("--allowedTools 'mcp__qits__*'"), script);
    }

    @Test
    void kimiGetsTheQitsServerOnlyWithFixedHeaders() {
      AgentLaunchService withHeaders = serviceWith(withQitsAndABearer());
      AgentLaunchService withoutHeaders = serviceWith(withQits());

      List<String> attached =
          withHeaders
              .buildAcpSessionConfig(
                  AgentMcpScope.PROJECT,
                  AgentSurface.PROJECT_WORK,
                  withHeaders.pinSession(null, false, AgentType.KIMI))
              .mcpServers()
              .stream()
              .map(server -> server.name() + "=" + server.headers())
              .toList();
      List<String> withoutAttached =
          withoutHeaders
              .buildAcpSessionConfig(
                  AgentMcpScope.PROJECT,
                  AgentSurface.PROJECT_WORK,
                  withoutHeaders.pinSession(null, false, AgentType.KIMI))
              .mcpServers()
              .stream()
              .map(server -> server.name())
              .toList();

      assertEquals(List.of("qits={Authorization=Bearer t}"), attached);
      assertEquals(List.of(), withoutAttached);
    }

    @Test
    void aSurfaceWithNoQitsServerRendersNoAllowedTools() {
      AgentLaunchService service =
          serviceWith(new ProjectHostMcpServers(Optional.empty(), Map.of()));
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();

      assertFalse(script.contains("--allowedTools"), script);
    }
  }

  // --- the request's environment overlay (qits-1488) --------------------------------------------

  /**
   * A host can hand one launch an environment of its own — the delivery desk runs under its own
   * token rather than the daemon's. The overlay wins over everything the library sets, an empty
   * value removes a variable, and its {@code QITS_TOKEN} is the bearer on the platform servers of
   * that launch and of no other.
   */
  @Nested
  class EnvironmentOverlay {

    private static final String QITS_URL = "http://dev-qits-platform-access-mcp-service:8080/mcp";

    /** A host that puts its own bearer on the {@code qits} server, as a RUNNER host does. */
    private AgentMcpServers hostWithABearer() {
      return scope ->
          List.of(
              new ScopedMcp(
                  "qits", QITS_URL, List.of(), Map.of("Authorization", "Bearer host")));
    }

    /** A host with the {@code qits} server and no headers: Claude renders the headers helper. */
    private AgentMcpServers hostWithoutHeaders() {
      return scope -> List.of(new ScopedMcp("qits", QITS_URL, List.of()));
    }

    private AgentLaunchRequest delivery(Map<String, String> environment) {
      return new AgentLaunchRequest(
              AgentMcpScope.REPOSITORY,
              AgentSurface.PROJECT_DELIVERY,
              AgentLaunchMode.INTERACTIVE,
              null,
              null,
              false,
              false,
              null)
          .withEnvironment(environment);
    }

    @Test
    void theOverlaysTokenReachesTheProcessAndBecomesTheQitsBearer() {
      serviceWith(hostWithoutHeaders()).launch(delivery(Map.of("QITS_TOKEN", "t2")));

      Commands.Launch launch = commands.last();
      assertEquals("t2", launch.environment().get("QITS_TOKEN"));
      assertEquals(CLAUDE_MOUNT, launch.environment().get("HOME"), "the library's own still apply");
      assertTrue(
          launch
              .script()
              .contains(
                  "\"qits\":{\"type\":\"http\",\"url\":\""
                      + QITS_URL
                      + "\",\"headers\":{\"Authorization\":\"Bearer t2\"}}"),
          launch.script());
      assertFalse(launch.script().contains("headersHelper"), launch.script());
      assertEquals(
          List.of("Bearer t2"),
          launch.agent().redactions(),
          "the launch's bearer is redacted from the stored script like the host's");
    }

    @Test
    void theOverlaysTokenReplacesTheHostsBearerForThisLaunchOnly() {
      AgentLaunchService service = serviceWith(hostWithABearer());

      service.launch(delivery(Map.of("QITS_TOKEN", "t2")));
      String overlaid = commands.last().script();
      service.launch(delivery(null));
      String plain = commands.last().script();

      assertTrue(overlaid.contains("\"Authorization\":\"Bearer t2\""), overlaid);
      assertFalse(overlaid.contains("Bearer host"), overlaid);
      assertTrue(plain.contains("\"Authorization\":\"Bearer host\""), plain);
      assertFalse(plain.contains("Bearer t2"), plain);
    }

    @Test
    void aLaunchWithoutAnOverlayIsUnchanged() {
      AgentLaunchService service = serviceWith(hostWithABearer());
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);
      LaunchSpec rendered =
          service.renderInteractive(
              AgentMcpScope.REPOSITORY,
              AgentSurface.PROJECT_DELIVERY,
              null,
              pinned,
              AgentType.CLAUDE);

      service.launch(delivery(null));
      Commands.Launch none = commands.last();
      service.launch(delivery(Map.of()));
      Commands.Launch empty = commands.last();

      assertEquals(rendered.environment(), none.environment(), "same env");
      assertEquals(none.environment(), empty.environment());
      assertFalse(none.environment().containsKey("QITS_TOKEN"));
      assertEquals(
          rendered.script().replaceAll("[0-9a-f-]{36}", "<id>"),
          none.script().replaceAll("[0-9a-f-]{36}", "<id>"),
          "same script, same headers");
      assertEquals(
          none.script().replaceAll("[0-9a-f-]{36}", "<id>"),
          empty.script().replaceAll("[0-9a-f-]{36}", "<id>"));
      assertTrue(none.script().contains("\"Authorization\":\"Bearer host\""), none.script());
    }

    @Test
    void anEmptyValueRemovesTheVariable() {
      service().launch(delivery(Map.of("X", "", "HOME", "")));

      Commands.Launch launch = commands.last();
      assertFalse(launch.environment().containsKey("X"));
      assertFalse(
          launch.environment().containsKey("HOME"),
          "the overlay is applied after the library's own HOME, so it can remove that too");
      assertTrue(
          launch.script().startsWith("unset HOME X; "),
          "the daemon's own environment is inherited, so the shell unsets it: " + launch.script());
    }

    @Test
    void anOverlayValueWinsOverWhatTheLibrarySets() {
      service().launch(delivery(Map.of("HOME", "/elsewhere", "CLAUDE_CODE_SANDBOXED", "0")));

      assertEquals("/elsewhere", commands.last().environment().get("HOME"));
      assertEquals("0", commands.last().environment().get("CLAUDE_CODE_SANDBOXED"));
    }

    @Test
    void aChatLaunchAppliesItTooAndKimisSessionCarriesTheBearer() {
      serviceWith(hostWithoutHeaders())
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_DELIVERY,
                  AgentLaunchMode.CHAT,
                  null,
                  null,
                  false,
                  false,
                  null,
                  null,
                  Map.of("QITS_TOKEN", "t2")));
      assertEquals("t2", commands.last().environment().get("QITS_TOKEN"));
      assertTrue(commands.last().script().contains("Bearer t2"), commands.last().script());

      AgentLaunchService service = serviceWith(hostWithoutHeaders());
      AcpSessionConfig config =
          service.buildAcpSessionConfig(
              AgentMcpScope.REPOSITORY,
              AgentSurface.PROJECT_DELIVERY,
              service.pinSession(null, false, AgentType.KIMI),
              false,
              Map.of("QITS_TOKEN", "t2"));
      assertEquals(
          List.of("qits={Authorization=Bearer t2}"),
          config.mcpServers().stream().map(s -> s.name() + "=" + s.headers()).toList(),
          "Kimi gets the qits server once it has a header to carry");
    }

    @Test
    void anExternalCatalogServerNeverGetsTheToken() {
      configurations =
          new SeededConfigurationDocument()
              .surface(
                  AgentSurface.PROJECT_DELIVERY,
                  true,
                  "",
                  SeededConfigurationDocument.server("qits", false, false, false, false, List.of()))
              .attaching(
                  SeededConfigurationDocument.external(
                      "docs", "https://docs.example/mcp", "", "", List.of()))
              .configurations();

      serviceWith(hostWithABearer()).launch(delivery(Map.of("QITS_TOKEN", "t2")));

      String script = commands.last().script();
      assertTrue(script.contains("\"qits\":{\"type\":\"http\",\"url\":\"" + QITS_URL
          + "\",\"headers\":{\"Authorization\":\"Bearer t2\"}}"), script);
      assertTrue(
          script.contains("\"docs\":{\"type\":\"http\",\"url\":\"https://docs.example/mcp\"}"),
          script);
    }

    @Test
    void aVariableNameThatIsNotAnIdentifierIsRefused() {
      assertThrows(
          InvalidCommandRequestException.class, () -> delivery(Map.of("X; rm -rf /", "")));
    }
  }

  // --- the launch record ------------------------------------------------------------------------

  /**
   * What a session was launched with, recorded on the command at launch. It is what makes
   * "recreate only, and we do not surface staleness" a safe rule rather than an opaque one: the
   * store can be edited at any time and a container keeps the document it was born with, so a
   * session that behaved oddly last week is unreadable off anything else.
   */
  @Nested
  class LaunchRecord {

    @Test
    void aChatRecordsTheWholeResolvedConfiguration() {
      configurations =
          AgentSurfaceConfigurations.of(
              AgentConfigurationDocument.parse(
                  "{\"version\":1,\"surfaces\":[{\"surface\":\"project.work\",\"harness\":\"CLAUDE\","
                      + "\"permissionMode\":\"PROMPT\",\"model\":\"opus\",\"effort\":\"xhigh\","
                      + "\"remoteControl\":true,\"activityTracking\":false}]}",
                  "test"));

      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      JsonObject record = new JsonObject(command.agentLaunchRecord());

      assertEquals("project.work", record.getString("surface"));
      assertEquals("CLAUDE", record.getString("harness"));
      assertEquals("opus", record.getString("model"));
      assertEquals("xhigh", record.getString("effort"));
      assertEquals("PROMPT", record.getString("permissionMode"));
      assertTrue(record.getBoolean("remoteControl"));
      assertEquals(
          AgentRemoteControl.FRONT_DESK_NAME,
          record.getString("remoteControlName"),
          "the desk runs for no entity, and is called what a person recognises it as");
      assertFalse(record.getBoolean("activityTracking"));
      assertTrue(record.getBoolean("configured"), "this container was born with a document");
      assertEquals(
          "qits", record.getJsonArray("mcpServers").getJsonObject(0).getString("server"));
      assertFalse(record.getJsonArray("mcpServers").getJsonObject(0).getBoolean("readOnly"));
      assertEquals(0, record.getJsonArray("externalMcpServers").size());
    }

    /** The name a ticket container renders for qits-614 while it is IMPLEMENTED. */
    private static final String IMPLEMENTED_NAME = "\uD83D\uDFE6 qits-614 Session names";

    private static final String BLOCKED_IMPLEMENTED_NAME =
        "\u2757\uD83D\uDFE6 qits-614 Session names";

    private void forTicket() {
      entityId = Optional.of("qits-614");
      entityTitle = Optional.of("Session names");
      entityStatus = Optional.of("IMPLEMENTED");
    }

    @Test
    void theRemoteControlNameReadsSquareIdAndTitleWhenTheContainerKnowsItsEntity() {
      // A container created for a ticket answers entityId(), title and status, and the recorded
      // name is the board's reading of it, not the surface key and not the branch.
      forTicket();
      configurations =
          AgentSurfaceConfigurations.of(
              AgentConfigurationDocument.parse(
                  "{\"version\":1,\"surfaces\":[{\"surface\":\"project.work\",\"harness\":\"CLAUDE\","
                      + "\"permissionMode\":\"PROMPT\",\"remoteControl\":true}]}",
                  "test"));

      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      JsonObject record = new JsonObject(command.agentLaunchRecord());

      assertEquals(IMPLEMENTED_NAME, record.getString("remoteControlName"));
    }

    @Test
    void aContainerThatKnowsOnlyItsIdIsNamedByTheIdAlone() {
      // A container created before the host injected title and status still names its entity.
      entityId = Optional.of("qits-614");
      configurations = remoteControlOn();

      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      assertEquals(
          "qits-614", new JsonObject(command.agentLaunchRecord()).getString("remoteControlName"));
    }

    @Test
    void aLaunchWhileTheEntityIsBlockedRecordsTheMarkedName() {
      // The container booted for a ticket that was already blocked: the very first launch carries
      // the marker, with nobody having called setEntity.
      forTicket();
      entityBlocked = true;
      configurations = remoteControlOn();

      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      JsonObject record = new JsonObject(command.agentLaunchRecord());

      assertEquals(BLOCKED_IMPLEMENTED_NAME, record.getString("remoteControlName"));
    }

    @Test
    void setEntityRenamesALiveRemoteControlChatAndForgetsAnEndedOne() {
      forTicket();
      configurations = remoteControlOn();
      commands.spawnTransports = true;
      AgentLaunchService service = service();
      Command command = service.launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      assertEquals(
          IMPLEMENTED_NAME,
          new JsonObject(command.agentLaunchRecord()).getString("remoteControlName"));

      assertEquals(1, service.setEntity(new EntityFacts("Session names, renamed", "VERIFIED", false)));
      assertEquals(
          List.of(command.id() + " ✅ qits-614 Session names, renamed"), commands.renames);
      assertEquals(
          "✅ qits-614 Session names, renamed",
          new JsonObject(
                  service.launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK))
                      .agentLaunchRecord())
              .getString("remoteControlName"),
          "the next launch renders the new facts too");

      commands.liveChats.clear();
      commands.renames.clear();
      assertEquals(0, service.setEntity(EntityFacts.NONE), "both chats have ended; neither answers");
      commands.liveChats.add(command.id());
      assertEquals(
          0, service.setEntity(EntityFacts.NONE), "and an ended chat was forgotten, not retried");
      assertTrue(commands.renames.isEmpty());
    }

    @Test
    void setBlockedChangesOnlyTheBlockedFlag() {
      forTicket();
      configurations = remoteControlOn();
      commands.spawnTransports = true;
      AgentLaunchService service = service();
      Command command = service.launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      assertEquals(1, service.setBlocked(true));
      assertEquals(List.of(command.id() + " " + BLOCKED_IMPLEMENTED_NAME), commands.renames);
      assertEquals(new EntityFacts("Session names", "IMPLEMENTED", true), service.entity());
      assertTrue(service.blocked());
    }

    @Test
    void setBlockedWithAnAgentWaitingSourceRenamesWithTheQuestionGlyph() {
      forTicket();
      configurations = remoteControlOn();
      commands.spawnTransports = true;
      AgentLaunchService service = service();
      Command command = service.launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      assertEquals(1, service.setBlocked(true, EntityFacts.AGENT_WAITING));
      assertEquals(
          List.of(command.id() + " \u2049\uFE0F\uD83D\uDFE6 qits-614 Session names"),
          commands.renames);
      assertEquals(
          new EntityFacts("Session names", "IMPLEMENTED", true, EntityFacts.AGENT_WAITING),
          service.entity());

      commands.renames.clear();
      assertEquals(1, service.setBlocked(true));
      assertEquals(
          List.of(command.id() + " " + BLOCKED_IMPLEMENTED_NAME),
          commands.renames,
          "the old overload is an explicit block, and clears the derived source");
    }

    @Test
    void setEntityLeavesAChatWithRemoteControlOffAlone() {
      forTicket();
      configurations =
          AgentSurfaceConfigurations.of(
              AgentConfigurationDocument.parse(
                  "{\"version\":1,\"surfaces\":[{\"surface\":\"project.work\","
                      + "\"harness\":\"CLAUDE\",\"permissionMode\":\"PROMPT\","
                      + "\"remoteControl\":false}]}",
                  "test"));
      commands.spawnTransports = true;
      AgentLaunchService service = service();
      Command command = service.launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      commands.liveChats.add(command.id());

      assertEquals(
          0, service.setEntity(new EntityFacts("x", "DONE", true)), "no bridge, nothing to rename");
      assertTrue(commands.renames.isEmpty());
    }

    // --- live rename of interactive sessions (qits-617) ---

    private static final String VERIFIED_NAME = "✅ qits-614 Session names";

    private static final EntityFacts VERIFIED = new EntityFacts("Session names", "VERIFIED", false);

    private Command interactiveSession(AgentLaunchService service) {
      Command command =
          service.launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.INTERACTIVE,
                  null,
                  null,
                  false,
                  false,
                  null));
      assertTrue(
          commands.last().script().contains("--remote-control '" + IMPLEMENTED_NAME + "'"),
          commands.last().script());
      return command;
    }

    private String renameOf(Command command, String name) {
      return command.id() + " /rename " + name;
    }

    @Test
    void anInteractiveRenameWaitsForTheNextIdleAndIsTypedThen() {
      forTicket();
      configurations = remoteControlOn();
      AgentLaunchService service = service();
      Command command = interactiveSession(service);
      service.onActivity(command.id(), "BUSY");

      assertEquals(1, service.setEntity(VERIFIED), "queued counts: it will carry the name");
      assertTrue(commands.keystrokesTo.isEmpty(), "nothing is typed into a running turn");

      service.onActivity(command.id(), "IDLE");
      assertEquals(List.of(renameOf(command, VERIFIED_NAME)), commands.keystrokesTo);

      service.onActivity(command.id(), "IDLE");
      assertEquals(1, commands.keystrokesTo.size(), "delivered once, not on every idle");
    }

    @Test
    void anIdleSessionIsRenamedAtOnce() {
      forTicket();
      configurations = remoteControlOn();
      AgentLaunchService service = service();
      Command command = interactiveSession(service);
      service.onActivity(command.id(), "idle");

      assertEquals(1, service.setEntity(VERIFIED));
      assertEquals(List.of(renameOf(command, VERIFIED_NAME)), commands.keystrokesTo);
    }

    @Test
    void anInteractiveRenameNeverTypesIntoAPermissionPrompt() {
      forTicket();
      configurations = remoteControlOn();
      AgentLaunchService service = service();
      Command command = interactiveSession(service);
      service.onActivity(command.id(), "WAITING");

      service.setEntity(VERIFIED);
      assertTrue(commands.keystrokesTo.isEmpty(), "the first keystroke would answer the dialog");

      service.onActivity(command.id(), "BUSY");
      service.onActivity(command.id(), "IDLE");
      assertEquals(List.of(renameOf(command, VERIFIED_NAME)), commands.keystrokesTo);
    }

    @Test
    void anInteractiveSessionWithNoHookStateIsNeverRenamedLive() {
      // Activity tracking off: no hook ever fires, so nothing is known about the screen.
      forTicket();
      configurations = remoteControlOn();
      AgentLaunchService service = service();
      interactiveSession(service);

      assertEquals(1, service.setEntity(VERIFIED));
      assertEquals(1, service.setEntity(new EntityFacts("Session names", "DONE", true)));
      assertTrue(commands.keystrokesTo.isEmpty());
    }

    @Test
    void aDraftHoldsTheRenameUntilAnIdleWithACleanLine() {
      forTicket();
      configurations = remoteControlOn();
      AgentLaunchService service = service();
      Command command = interactiveSession(service);
      service.onActivity(command.id(), "IDLE");
      commands.drafts.add(command.id());

      service.setEntity(VERIFIED);
      service.onActivity(command.id(), "IDLE");
      assertTrue(commands.keystrokesTo.isEmpty(), "never glued onto a half-typed prompt");

      // The person submits: the draft clears, but nothing is sent until that turn's Stop.
      commands.drafts.clear();
      assertTrue(commands.keystrokesTo.isEmpty());
      service.onActivity(command.id(), "BUSY");
      assertTrue(commands.keystrokesTo.isEmpty());
      service.onActivity(command.id(), "IDLE");
      assertEquals(List.of(renameOf(command, VERIFIED_NAME)), commands.keystrokesTo);
    }

    @Test
    void onlyTheLatestNameIsTypedAndAnUnchangedOneIsNot() {
      forTicket();
      configurations = remoteControlOn();
      AgentLaunchService service = service();
      Command command = interactiveSession(service);
      service.onActivity(command.id(), "BUSY");

      service.setEntity(VERIFIED);
      service.setEntity(new EntityFacts("Session names", "DONE", true));
      service.onActivity(command.id(), "IDLE");
      assertEquals(
          List.of(renameOf(command, "\u2757\uD83D\uDFE9 qits-614 Session names")),
          commands.keystrokesTo,
          "two changes during one turn type one rename");

      commands.keystrokesTo.clear();
      service.onActivity(command.id(), "BUSY");
      service.setEntity(VERIFIED);
      service.setEntity(new EntityFacts("Session names", "DONE", true));
      service.onActivity(command.id(), "IDLE");
      assertTrue(
          commands.keystrokesTo.isEmpty(), "back to what it already carries: nothing to type");
    }

    @Test
    void anEndedInteractiveSessionIsForgotten() {
      forTicket();
      configurations = remoteControlOn();
      AgentLaunchService service = service();
      Command command = interactiveSession(service);
      service.onActivity(command.id(), "ENDED");

      assertEquals(0, service.setEntity(VERIFIED));
      service.onActivity(command.id(), "IDLE");
      assertTrue(commands.keystrokesTo.isEmpty(), "an ENDED session is not brought back by a hook");
    }

    @Test
    void anInteractiveSessionWhoseTerminalIsGoneIsForgotten() {
      forTicket();
      configurations = remoteControlOn();
      AgentLaunchService service = service();
      Command command = interactiveSession(service);
      service.onActivity(command.id(), "IDLE");
      commands.endedTerminals.add(command.id());

      assertEquals(0, service.setEntity(VERIFIED));
      commands.endedTerminals.clear();
      assertEquals(0, service.setEntity(new EntityFacts("x", "DONE", false)), "not retried");
      assertTrue(commands.keystrokesTo.isEmpty());
    }

    @Test
    void anInteractiveSessionWithRemoteControlOffIsNotTracked() {
      forTicket();
      configurations =
          AgentSurfaceConfigurations.of(
              AgentConfigurationDocument.parse(
                  "{\"version\":1,\"surfaces\":[{\"surface\":\"project.work\","
                      + "\"harness\":\"CLAUDE\",\"permissionMode\":\"PROMPT\","
                      + "\"remoteControl\":false}]}",
                  "test"));
      AgentLaunchService service = service();
      Command command =
          service.launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_WORK,
                  AgentLaunchMode.INTERACTIVE,
                  null,
                  null,
                  false,
                  false,
                  null));
      service.onActivity(command.id(), "IDLE");

      assertEquals(0, service.setEntity(VERIFIED), "no bridge, no list entry to rename");
      assertTrue(commands.keystrokesTo.isEmpty());
    }

    // --- per-session-key entities (qits-1309): a multiplexing host's sessions, each its own ---

    private static final EntityFacts REFINING_A =
        new EntityFacts("Desk refinement", "REPORTED", false);

    private static final EntityFacts REFINING_B = new EntityFacts("Other ticket", "REFINED", false);

    private static String keyedName(String id, EntityFacts facts) {
      return AgentRemoteControl.sessionName(id, facts, AgentSurface.PROJECT_WORK.key(), null);
    }

    private Command keyedChat(
        AgentLaunchService service, String key, String id, EntityFacts facts) {
      return service.launchChat(
          chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK).withSessionKey(key, id, facts));
    }

    @Test
    void aKeyedLaunchIsNamedFromItsKeysEntityAndNotTheContainers() {
      forTicket();
      configurations = remoteControlOn();
      AgentLaunchService service = service();

      Command command = keyedChat(service, "uuid-a", "qits-1234", REFINING_A);

      String name = new JsonObject(command.agentLaunchRecord()).getString("remoteControlName");
      assertEquals(keyedName("qits-1234", REFINING_A), name);
      assertTrue(name.contains("qits-1234 Desk refinement"), name);
      assertFalse(name.contains("qits-614"), name);
      assertEquals(REFINING_A, service.entity("uuid-a"));
      assertEquals(
          new EntityFacts("Session names", "IMPLEMENTED", false),
          service.entity(),
          "the container's own entity is untouched");
    }

    @Test
    void aKeyWithoutAnEntityIsNamedWithoutOne() {
      // The front desk's own session: a key, and nothing to name it after.
      forTicket();
      configurations = remoteControlOn();

      Command command = keyedChat(service(), "front-desk", null, null);

      String name = new JsonObject(command.agentLaunchRecord()).getString("remoteControlName");
      assertEquals(keyedName(null, EntityFacts.NONE), name);
      assertFalse(name.contains("qits-614"), name);
    }

    @Test
    void setEntityForAKeyRenamesOnlyThatKeysSessions() {
      forTicket();
      configurations = remoteControlOn();
      commands.spawnTransports = true;
      AgentLaunchService service = service();
      Command a = keyedChat(service, "uuid-a", "qits-1234", REFINING_A);
      Command b = keyedChat(service, "uuid-b", "qits-1235", REFINING_B);
      Command unkeyed =
          service.launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      EntityFacts refined = new EntityFacts("Desk refinement", "REFINED", true);
      assertEquals(1, service.setEntity("uuid-a", "qits-1234", refined));
      assertEquals(List.of(a.id() + " " + keyedName("qits-1234", refined)), commands.renames);
      assertEquals(refined, service.entity("uuid-a"));
      assertEquals(REFINING_B, service.entity("uuid-b"));

      commands.renames.clear();
      assertEquals(1, service.setEntity("uuid-b", null, EntityFacts.NONE), "null id keeps the id");
      assertEquals(
          List.of(b.id() + " " + keyedName("qits-1235", EntityFacts.NONE)), commands.renames);

      commands.renames.clear();
      assertEquals(1, service.setEntity(VERIFIED), "the single-entity form: the unkeyed chat only");
      assertEquals(List.of(unkeyed.id() + " " + VERIFIED_NAME), commands.renames);
      assertEquals(refined, service.entity("uuid-a"), "and no key's entity moved with it");
    }

    @Test
    void aKeyedInteractiveSessionIsRetargetedOnlyByItsKey() {
      forTicket();
      configurations = remoteControlOn();
      AgentLaunchService service = service();
      Command keyed =
          service.launch(
              new AgentLaunchRequest(
                      AgentMcpScope.REPOSITORY,
                      AgentSurface.PROJECT_WORK,
                      AgentLaunchMode.INTERACTIVE,
                      null,
                      null,
                      false,
                      false,
                      null)
                  .withSessionKey("uuid-a", "qits-1234", REFINING_A));
      assertTrue(
          commands
              .last()
              .script()
              .contains("--remote-control '" + keyedName("qits-1234", REFINING_A)),
          commands.last().script());
      service.onActivity(keyed.id(), "IDLE");

      assertEquals(0, service.setEntity(VERIFIED), "the container's entity is not this session's");
      assertTrue(commands.keystrokesTo.isEmpty());

      EntityFacts refined = new EntityFacts("Desk refinement", "REFINED", false);
      assertEquals(1, service.setEntity("uuid-a", "qits-1234", refined));
      assertEquals(
          List.of(renameOf(keyed, keyedName("qits-1234", refined))), commands.keystrokesTo);
    }

    @Test
    void aForgottenKeyIsNeitherKnownNorRenamed() {
      forTicket();
      configurations = remoteControlOn();
      commands.spawnTransports = true;
      AgentLaunchService service = service();
      keyedChat(service, "uuid-a", "qits-1234", REFINING_A);

      assertTrue(service.forgetEntity("uuid-a"));
      assertEquals(EntityFacts.NONE, service.entity("uuid-a"));
      assertEquals(0, service.setEntity("uuid-a", "qits-1234", VERIFIED));
      assertTrue(commands.renames.isEmpty());
      assertFalse(service.forgetEntity(null), "the container's own entity is never forgotten");
      assertEquals(new EntityFacts("Session names", "IMPLEMENTED", false), service.entity());
    }

    private AgentSurfaceConfigurations remoteControlOn() {
      return AgentSurfaceConfigurations.of(
          AgentConfigurationDocument.parse(
              "{\"version\":1,\"surfaces\":[{\"surface\":\"project.work\",\"harness\":\"CLAUDE\","
                  + "\"permissionMode\":\"PROMPT\",\"remoteControl\":true}]}",
              "test"));
    }

    @Test
    void aContainerWithNoDocumentSaysItWasNotConfigured() {
      // The difference between "configured this way" and "nobody had configured it", which a reader
      // of the record cannot otherwise tell — and which is exactly the rollout's own question.
      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      assertFalse(new JsonObject(command.agentLaunchRecord()).getBoolean("configured"));
    }

    @Test
    void anUnattendedRunRecordsThatItsServersWereFenced() {
      Command command = service().launchAutonomous("Composed run");
      JsonObject record = new JsonObject(command.agentLaunchRecord());

      assertEquals("epic.autonomous", record.getString("surface"));
      assertEquals("qits", record.getJsonArray("mcpServers").getJsonObject(0).getString("server"));
      assertTrue(
          record.getJsonArray("mcpServers").getJsonObject(0).getBoolean("readOnly"),
          "the fence is part of what it ran with");
    }

    @Test
    void aKimiSessionRecordsTheKnobsItsHarnessCouldNotHonour() {
      // The record says what the harness DID, not what the row held, and the notes say what the
      // difference was — a surface reading as configured while its sessions ran as the default one
      // is the failure this closes.
      defaultType = AgentType.KIMI;
      configurations =
          AgentSurfaceConfigurations.of(
              AgentConfigurationDocument.parse(
                  "{\"version\":1,\"surfaces\":[{\"surface\":\"project.work\",\"harness\":\"KIMI\","
                      + "\"permissionMode\":\"SKIP_PERMISSIONS\",\"effort\":\"high\","
                      + "\"systemPrompt\":\"You are the desk.\"}]}",
                  "test"));

      Command command =
          service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      JsonObject record = new JsonObject(command.agentLaunchRecord());

      assertEquals("", record.getString("effort"), "nothing was rendered, so nothing is recorded");
      assertEquals(2, record.getJsonArray("notes").size(), record.getJsonArray("notes").encode());
      assertTrue(record.getJsonArray("notes").encode().contains("no effort concept"));
      assertTrue(record.getJsonArray("notes").encode().contains("system-prompt appendix"));
    }

    @Test
    void anInteractiveLaunchRecordsTheSameWay() {
      Command command =
          service()
              .launch(
                  new AgentLaunchRequest(
                      AgentMcpScope.PROJECT,
                      AgentSurface.PROJECT_WORK,
                      AgentLaunchMode.INTERACTIVE,
                      null,
                      null,
                      false,
                      false,
                      null));

      assertEquals(
          "project.work", new JsonObject(command.agentLaunchRecord()).getString("surface"));
    }

    @Test
    void theSignInTerminalRecordsNothing() {
      // It renders nobody's configuration, so there is nothing to record — and a record here would
      // be a configuration a reader could believe applied to it.
      assertNull(service().launchLogin(AgentType.CLAUDE).agentLaunchRecord());
    }

    @Test
    void noCredentialCanTravelInIt() {
      // Attached external servers are recorded BY KEY. Not by url with a header stripped, not by a
      // redacted value — by key, so there is no shape here a credential could ride in.
      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));

      assertFalse(command.agentLaunchRecord().contains("header"), command.agentLaunchRecord());
      assertFalse(command.agentLaunchRecord().contains("Authorization"));
    }
  }

  // --- the ACP session config -------------------------------------------------------------------

  @Nested
  class AcpConfig {

    @Test
    void scopedServersRideSessionNewWithBareToolNames() {
      // Kimi gets qits only with fixed headers (qits-625), so this host carries one.
      AgentLaunchService service =
          serviceWith(
              new ProjectHostMcpServers(
                  Optional.of(ProjectHostMcpServers.PLATFORM_URL),
                  Map.of("Authorization", "Bearer t")));
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      AcpSessionConfig config = service.buildAcpSessionConfig(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned);

      assertEquals("/workspace", config.cwd());
      assertEquals(1, config.mcpServers().size());
      AcpSessionConfig.AcpMcpServer server = config.mcpServers().get(0);
      assertEquals("qits", server.name());
      assertFalse(
          server.enabledTools().stream().anyMatch(tool -> tool.startsWith("mcp__")),
          "kimi takes bare names, not the mcp__server__ form: " + server.enabledTools());
    }

    @Test
    void aResumedSessionIsCarriedAndAFreshOneIsNot() {
      commands.ownedSessions.put(KIMI_SESSION, "KIMI");
      AgentLaunchService service = service();

      assertEquals(
          KIMI_SESSION,
          service
              .buildAcpSessionConfig(
                  AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, service.pinSession(KIMI_SESSION, false, AgentType.KIMI))
              .resumeSessionId());
      assertNull(
          service
              .buildAcpSessionConfig(
                  AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, service.pinSession(null, false, AgentType.KIMI))
              .resumeSessionId());
    }
  }

  // --- rendering from the configuration -----------------------------------------------------

  /**
   * The equivalence the whole configuration epic rests on: a launch rendered from the seeded
   * document is the launch rendered from the constants, byte for byte, per surface, per mode, for
   * both harnesses. Everything downstream of here assumes "configured" and "hardcoded" are the same
   * thing on day one.
   */
  @Nested
  class ConfiguredRendering {

    /** This host's two surfaces, seeded from its own serversFor and its own tool list. */
    private AgentSurfaceConfigurations seeded() {
      return new SeededConfigurationDocument()
          .surface(
              AgentSurface.PROJECT_WORK,
              true,
              "",
              SeededConfigurationDocument.server(
                  "qits", false, false, false, false, List.of("mcp__qits__*")))
          // The composed run: the same server, read-only marked, steered to delegate.
          .surface(
              AgentSurface.EPIC_AUTONOMOUS,
              true,
              AgentLaunchService.COMPOSED_RUN_PROMPT,
              SeededConfigurationDocument.server(
                  "qits", false, false, false, true, List.of("mcp__qits__*")))
          .configurations();
    }

    @Test
    void everySurfaceRendersWhatTheConstantsRendered() {
      AgentLaunchService service = service();
      List<AgentSurface> surfaces = List.of(AgentSurface.PROJECT_WORK);

      for (AgentSurface surface : surfaces) {
        for (AgentType type : List.of(AgentType.CLAUDE, AgentType.KIMI)) {
          AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, type);
          for (AgentMcpScope scope : List.of(AgentMcpScope.PROJECT, AgentMcpScope.REPOSITORY)) {
            configurations = AgentSurfaceConfigurations.shipped();
            LaunchSpec constants = service.renderChat(scope, surface, pinned, type);
            LaunchSpec interactiveConstants =
                service.renderInteractive(scope, surface, "seed", pinned, type);

            configurations = seeded();

            assertEquals(
                constants.script(),
                service.renderChat(scope, surface, pinned, type).script(),
                surface + " chat on " + type + " at " + scope);
            assertEquals(
                constants.environment(),
                service.renderChat(scope, surface, pinned, type).environment());
            assertEquals(
                interactiveConstants.script(),
                service.renderInteractive(scope, surface, "seed", pinned, type).script(),
                surface + " interactive on " + type + " at " + scope);
          }
        }
      }
    }

    @Test
    void theComposedRunRendersItsFenceFromEitherSide() {
      // The autonomous shape marks every url read-only, and the seeded epic.autonomous row carries
      // readOnly too. The two must agree rather than one double-marking the url.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations = AgentSurfaceConfigurations.shipped();
      String constants =
          service
              .renderAutonomousChat(
                  AgentMcpScope.REPOSITORY, AgentSurface.EPIC_AUTONOMOUS, pinned, AgentType.CLAUDE)
              .script();

      configurations = seeded();
      String configured =
          service
              .renderAutonomousChat(
                  AgentMcpScope.REPOSITORY, AgentSurface.EPIC_AUTONOMOUS, pinned, AgentType.CLAUDE)
              .script();

      assertEquals(constants, configured);
      assertEquals(1, configured.split("agentReadOnly=true", -1).length - 1, configured);
    }

    @Test
    void theProjectDeskPromptComesFromTheDocumentRatherThanTheSwitch() {
      // The switch over the desk is gone: an edited prompt renders, and the constant is what a
      // container born without a document falls back to.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations =
          new SeededConfigurationDocument()
              .surface(AgentSurface.PROJECT_WORK, true, "Answer only in haiku.")
              .configurations();

      String script =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(script.contains("--append-system-prompt 'Answer only in haiku.'"), script);
    }

    @Test
    void anEmptySystemPromptIsAValueAndNotAnAbsence() {
      // project.work is seeded with "" on purpose. If empty rendered as "the shipped default"
      // instead, an operator could never clear a prompt.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations =
          new SeededConfigurationDocument()
              .surface(AgentSurface.PROJECT_WORK, true, "")
              .configurations();

      assertFalse(
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script()
              .contains("--append-system-prompt"));
    }

    @Test
    void thePermissionModeStopsBeingAnInvariantNobodyChose() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations = seeded();
      assertTrue(
          service
              .renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script()
              .contains("--dangerously-skip-permissions"),
          "seeded as what every launch renders today");

      configurations =
          AgentSurfaceConfigurations.of(
              AgentConfigurationDocument.parse(
                  "{\"version\":1,\"surfaces\":[{\"surface\":\"project.work\","
                      + "\"harness\":\"CLAUDE\",\"permissionMode\":\"PROMPT\"}]}",
                  "test"));

      assertFalse(
          service
              .renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script()
              .contains("--dangerously-skip-permissions"),
          "the agent will start asking, which is the point of the knob");
    }

    @Test
    void activityTrackingComesPerSurfaceRatherThanPerDaemon() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      // The daemon-wide setting still answers for a container with no document.
      activityTracking = false;
      configurations = AgentSurfaceConfigurations.shipped();
      assertFalse(
          service
              .renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script()
              .contains("\"Stop\""));

      // With a document, the surface's own value wins over it.
      configurations = seeded();
      assertTrue(
          service
              .renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script()
              .contains("\"Stop\""));
    }

    @Test
    void everySurfaceCanSetAModelAndAnEffortNow() {
      // Before this, exactly one flow could set a model (prompt refinement) and nothing could set an
      // effort level at all. Both are now per surface, and both render on both shapes.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations = knobs("\"model\":\"haiku\",\"effort\":\"low\"");

      String chat =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();
      String interactive =
          service
              .renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, null, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(chat.contains("--model 'haiku'"), chat);
      assertTrue(chat.contains("--effort 'low'"), chat);
      assertTrue(interactive.contains("--model 'haiku'"), interactive);
      assertTrue(interactive.contains("--effort 'low'"), interactive);
    }

    @Test
    void anInteractiveLaunchTakesTheRemoteControlFlagNamedForTheDesk() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations = knobs("\"remoteControl\":true");
      assertTrue(
          service
              .renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, null, pinned, AgentType.CLAUDE)
              .script()
              .contains("--remote-control '" + AgentRemoteControl.FRONT_DESK_NAME + "'"));

      configurations = knobs("\"remoteControl\":false");
      assertFalse(
          service
              .renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, null, pinned, AgentType.CLAUDE)
              .script()
              .contains("--remote-control"),
          "the knob is what drives it, and it is off");
    }

    @Test
    void aChatRendersNoFlagBecauseItsBridgeRidesTheControlChannel() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations = knobs("\"remoteControl\":true");

      assertFalse(
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script()
              .contains("--remote-control"),
          "the flag parses under --print and is dropped on the headless branch");
    }

    @Test
    void aChatsBridgeIsRaisedOnlyWhenTheKnobIsOn() throws Exception {
      // The one assertion that reaches the mechanism rather than the render: the transport asks the
      // harness for a bridge over stdin when the session announces itself. It used to be
      // unconditional and named after the branch; it is now the surface's knob.
      configurations = knobs("\"remoteControl\":true");
      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      assertEquals(
          AgentRemoteControl.FRONT_DESK_NAME,
          remoteControlNameAskedFor(commands.last().protocolFactory()),
          "the desk is named as a person knows it, not by the container's hostname");

      configurations = knobs("\"remoteControl\":false");
      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK));
      assertNull(
          remoteControlNameAskedFor(commands.last().protocolFactory()),
          "nothing is asked for, so no bridge is raised");
    }

    @Test
    void aKimiSurfaceReportsTheKnobsItCannotRenderRatherThanFailing() {
      // Kimi has no effort concept and no Remote Control. A configuration that sets either renders
      // nothing — passing an unknown flag would turn a configuration mistake into a failed launch.
      defaultType = AgentType.KIMI;
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      configurations = knobs("\"model\":\"k2\",\"effort\":\"high\",\"remoteControl\":true");

      String script =
          service
              .renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, null, pinned, AgentType.KIMI)
              .script();

      assertTrue(script.contains("-m 'k2'"), script);
      assertFalse(script.contains("effort"), script);
      assertFalse(script.contains("remote-control"), script);
    }

    @Test
    void nothingInALaunchsEnvironmentDisablesTheFeatureFlagRemoteControlNeeds() {
      // Remote Control rides a feature-flag evaluation four environment variables switch off. The
      // image sets none of them; asserting it here is what stops a later "turn off telemetry"
      // change from taking every bridge down silently.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      for (LaunchSpec spec :
          List.of(
              service.renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE),
              service.renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, null, pinned, AgentType.CLAUDE),
              service.renderLogin(AgentType.CLAUDE))) {
        assertEquals(List.of(), AgentRemoteControl.disabledBy(spec.environment()), spec.script());
      }
    }

    /** One epics-desk row carrying {@code extraFields}, everything else as it ships. */
    private AgentSurfaceConfigurations knobs(String extraFields) {
      return AgentSurfaceConfigurations.of(
          AgentConfigurationDocument.parse(
              "{\"version\":1,\"surfaces\":[{\"surface\":\"project.work\",\"harness\":\"CLAUDE\","
                  + "\"permissionMode\":\"SKIP_PERMISSIONS\","
                  + extraFields
                  + "}]}",
              "test"));
    }

    /**
     * Drives {@code factory}'s protocol against a process that announces {@code system/init} and
     * echoes its stdin, and answers the session name it asked a bridge for — or null when it asked
     * for none.
     */
    private String remoteControlNameAskedFor(ChatProtocolFactory factory) throws Exception {
      Process process =
          new ProcessBuilder(
                  "bash",
                  "-c",
                  "printf '%s\\n' '{\"type\":\"system\",\"subtype\":\"init\"}' ; exec cat")
              .start();
      BlockingQueue<String> lines = new LinkedBlockingQueue<>();
      ChatProtocol protocol = factory.create(process);
      protocol.start(lines::add, () -> {});
      try {
        assertNotNull(lines.poll(10, TimeUnit.SECONDS), "the init line");
        protocol.sendUser("ping");
        for (int i = 0; i < 3; i++) {
          String line = lines.poll(3, TimeUnit.SECONDS);
          if (line == null) {
            return null;
          }
          if (line.contains("\"remote_control\"")) {
            return new JsonObject(line).getJsonObject("request").getString("name");
          }
        }
        return null;
      } finally {
        protocol.close();
        process.destroy();
      }
    }

    @Test
    void aServerThisDaemonDoesNotServeAtThisScopeRefusesTheLaunch() {
      // Dropping it silently is the green-while-dead shape: the session looks entirely normal and
      // simply cannot do half its job. Retired servers are the one exception, and this is not one.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations =
          new SeededConfigurationDocument()
              .surface(
                  AgentSurface.PROJECT_WORK,
                  true,
                  "",
                  SeededConfigurationDocument.server(
                      "telemetry", false, false, false, false, List.of()))
              .configurations();

      InvalidCommandRequestException refusal =
          assertThrows(
              InvalidCommandRequestException.class,
              () ->
                  service.renderChat(
                      AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE));

      assertTrue(refusal.getMessage().contains("telemetry"), refusal.getMessage());
      assertTrue(refusal.getMessage().contains("project.work"), refusal.getMessage());
    }

    @Test
    void detachingAServerIsAConfigurationAndNotAnOmission() {
      // An empty attachment list is a decision; a surface that says nothing about MCP takes the
      // host's whole mapping. Both must be renderable, or "attach nothing" would be unreachable.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations =
          new SeededConfigurationDocument()
              .surface(AgentSurface.PROJECT_WORK, true, "")
              .configurations();

      String script =
          service
              .renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE)
              .script();

      assertFalse(script.contains("\"repository\":"), script);
      assertFalse(
          script.contains("--mcp-config"),
          "no servers means no --mcp-config at all, not an empty one");
    }
  }
}
