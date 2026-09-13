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

  @BeforeEach
  void setUp() {
    commands = new Commands();
    loggedIn = true;
    defaultType = AgentType.CLAUDE;
    activityTracking = true;
    configurations = AgentSurfaceConfigurations.shipped();
    ambientFacts = Map.of("project", "qits", "repository", REPO);
  }

  // --- fakes ------------------------------------------------------------------------------------

  /** Records every launch instead of spawning one. */
  private static final class Commands implements AgentCommands {
    private final List<Launch> launches = new ArrayList<>();
    private final Map<String, String> ownedSessions = new HashMap<>();
    private final List<String> chatSends = new ArrayList<>();
    private final List<String> keystrokes = new ArrayList<>();

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
      keystrokes.add(text);
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

  /** The one server, on its owning service's segment, as {@code DaemonMcpEndpoints} resolves it. */
  private static final McpEndpoints ENDPOINTS =
      new McpEndpoints() {
        @Override
        public String mcpUrl(String server) {
          if ("repository".equals(server)) {
            return "http://qits:8080/projects/mcp";
          }
          throw new IllegalArgumentException("Unknown MCP server: " + server);
        }

        @Override
        public String projectId() {
          return PROJECT;
        }
      };

  /** This daemon's mapping, narrowed to the wrapper repository the container checked out. */
  private static final AgentMcpServers MCP_SERVERS = serversWithRepo(REPO);

  private static AgentMcpServers serversWithRepo(String repoName) {
    return new ProjectHostMcpServers(ENDPOINTS, repoName);
  }

  private AgentLaunchService serviceWithRepo(String repoName) {
    return serviceWith(serversWithRepo(repoName));
  }

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
        };
    CommandStore store = new CommandStore();
    AgentTranscriptService transcripts =
        new AgentTranscriptService(
            store,
            new CommandLogService(store, null),
            new AgentSessionStore(),
            workspaceRoot.toString(),
            null);
    return new AgentLaunchService(
        commands,
        new AgentAuthStatus(probe, CLAUDE_MOUNT, workspaceRoot),
        transcripts,
        new AgentTranscriptTailService(transcripts, new CommandLogService(store, null)),
        defaults,
        mcpServers,
        CHECKOUT_CONTEXT,
        CLAUDE_MOUNT,
        HOOKS_PORT,
        PROJECT_TASK_PROMPT_BOOTSTRAP);
  }

  private static AgentLaunchRequest chat(AgentMcpScope scope) {
    // The surface is required now — there is no shape-implied guess left to lean on — so the
    // scope-only helper names this host's default desk explicitly. It steers with nothing, which is
    // why every launch through here still renders what it rendered before the axis existed.
    return chat(scope, AgentSurface.PROJECT_EPICS);
  }

  private static AgentLaunchRequest chat(AgentMcpScope scope, AgentSurface surface) {
    return new AgentLaunchRequest(
        scope, surface, AgentLaunchMode.CHAT, null, null, false, false, null);
  }

  // --- MCP scoping ------------------------------------------------------------------------------

  @Nested
  class McpScoping {

    @Test
    void repositoryScopeNarrowsByProjectAndRepository() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.REPOSITORY);

      assertEquals(1, servers.size(), "a project agent addresses qits-projects and nothing else");
      assertEquals("repository", servers.get(0).key());
      assertEquals(
          "http://qits:8080/projects/mcp?projectId=" + PROJECT + "&repositoryId=" + REPO,
          servers.get(0).url());
    }

    @Test
    void projectScopeDropsTheRepositoryNarrowing() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.PROJECT);

      assertEquals(1, servers.size());
      assertEquals(
          "http://qits:8080/projects/mcp?projectId=" + PROJECT,
          servers.get(0).url(),
          "project scope sees every repository, so it must not carry repositoryId");
    }

    @Test
    void thePlatformsSlugRepositoryIdsAreValidScopeIds() {
      // Repository ids on this platform are directory-name slugs (qits-stt) — the join key
      // everywhere. Validating them as strict UUIDs 400'd every launch on every real repository.
      // UUIDs still pass as a subset of the slug grammar.
      List<ScopedMcp> servers =
          serversWithRepo("qits-stt").serversFor(AgentMcpScope.REPOSITORY);

      assertTrue(servers.get(0).url().contains("repositoryId=qits-stt"));
    }

    @Test
    void aScopeIdOutsideTheSlugAlphabetIsStillRefused() {
      // The relaxation is exactly to the platform grammar, no further: these values are
      // interpolated into single-quoted launch args, so a quote, a space, or a leading dash must
      // still refuse the launch.
      assertThrows(
          InvalidCommandRequestException.class,
          () -> serversWithRepo("qits'stt").serversFor(AgentMcpScope.REPOSITORY));
      assertThrows(
          InvalidCommandRequestException.class,
          () -> serversWithRepo("-leading").serversFor(AgentMcpScope.REPOSITORY));
      assertThrows(
          InvalidCommandRequestException.class,
          () -> serversWithRepo("").serversFor(AgentMcpScope.REPOSITORY));
    }

    @Test
    void onlyReadOnlyToolsArePreApproved() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.PROJECT);

      assertTrue(servers.get(0).allowedTools().contains("mcp__repository__taskPrompt"));
      assertFalse(
          servers.get(0).allowedTools().stream().anyMatch(t -> t.contains("create")),
          "a mutating tool must still prompt");
      assertFalse(
          servers.get(0).allowedTools().stream().anyMatch(t -> t.contains("transition")),
          "moving a ticket's state is a write like any other");
      assertFalse(
          servers.get(0).allowedTools().stream().anyMatch(t -> t.contains("integrateBranch")));
    }

    @Test
    void theEpicSurveyIsPreApprovedAndTheEpicWritesAreNot() {
      // The refinement agent has to read the plan before it can tell "extend this draft" from
      // "propose a new epic"; drafting one is a change to the project and still prompts.
      List<String> allowed = MCP_SERVERS.serversFor(AgentMcpScope.PROJECT).get(0).allowedTools();

      assertTrue(allowed.contains("mcp__repository__list_epics"), allowed.toString());
      assertTrue(allowed.contains("mcp__repository__get_epic"), allowed.toString());
      assertFalse(allowed.contains("mcp__repository__propose_epic"));
      assertFalse(allowed.stream().anyMatch(t -> t.startsWith("mcp__repository__add_")));
      assertFalse(allowed.stream().anyMatch(t -> t.startsWith("mcp__repository__update_")));
      assertFalse(allowed.stream().anyMatch(t -> t.startsWith("mcp__repository__remove_")));
    }

    @Test
    void theTicketSurveyIsPreApprovedAndTheTicketWritesAreNot() {
      // The exact parallel to the epic survey one surface down: the tickets desk has to read what
      // is already filed before it can tell an intake from a duplicate, and get_ticket brings the
      // comment thread with it. Filing, assigning, commenting and resolving all change the
      // project's record of work, so all four still prompt.
      List<String> allowed = MCP_SERVERS.serversFor(AgentMcpScope.PROJECT).get(0).allowedTools();

      assertTrue(allowed.contains("mcp__repository__list_tickets"), allowed.toString());
      assertTrue(allowed.contains("mcp__repository__get_ticket"), allowed.toString());
      assertFalse(allowed.contains("mcp__repository__create_ticket"));
      assertFalse(allowed.contains("mcp__repository__update_ticket"));
      assertFalse(allowed.contains("mcp__repository__transition_ticket"));
      assertFalse(allowed.contains("mcp__repository__add_ticket_comment"));
      assertFalse(allowed.contains("mcp__repository__update_ticket_comment"));
    }

    @Test
    void theWorkspaceWorldServersAreNotWiredIntoALaunch() {
      // The workspace daemon attaches actions/repository/observability. A refinement agent's job is
      // the project's plan, so only the epic-carrying server is attached — and --strict-mcp-config
      // is what stops the shared /claude-home volume putting the others back.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(script.contains("--strict-mcp-config"), script);
      assertEquals(
          1,
          script.split("\"mcpServers\"", -1).length - 1,
          "one --mcp-config, and it is the whole set the session may use");
      assertEquals(
          1,
          script.split("\"repository\":", -1).length - 1,
          "exactly one server, keyed 'repository' — the one carrying the epic tools");
      assertFalse(script.contains("\"actions\":"), script);
      assertFalse(script.contains("\"observability\":"), script);
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
          service.renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE);

      assertTrue(spec.script().contains("--input-format stream-json"));
      assertTrue(spec.script().contains("repositoryId=" + REPO));
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
              AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE);

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
              AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, "do the thing", pinned, AgentType.CLAUDE);

      assertTrue(spec.script().startsWith("exec claude 'do the thing'"), spec.script());
      assertTrue(spec.interactive());
    }

    @Test
    void kimiTakesNoHomeOverlay() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      LaunchSpec spec =
          service.renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, pinned, AgentType.KIMI);

      assertFalse(
          spec.environment().containsKey("HOME"), "Kimi reads KIMI_CODE_HOME, set container-wide");
      assertEquals("exec kimi acp", spec.script());
    }

    @Test
    void activityTrackingOffStillWiresTheLineageHook() {
      activityTracking = false;
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
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

    /** The appendix as the shell sees it — the desk prompt's apostrophes escaped in place. */
    private static final String QUOTED_TICKETS_PROMPT =
        "--append-system-prompt 'You are this project'\\''s tickets front desk:";

    @Test
    void theEpicsDeskAppendsNothingAtAll() {
      // The equivalence the steering axis was added on, and re-asserted now that AgentSurface has
      // replaced AgentDesk: project.epics renders what EPICS rendered, which is what the launch
      // rendered before either existed.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      assertNull(AgentLaunchService.systemPromptFor(AgentSurface.PROJECT_EPICS));
      assertFalse(
          service
              .renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
              .script()
              .contains("--append-system-prompt"));
      assertFalse(
          service
              .renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, null, pinned, AgentType.CLAUDE)
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
    void theEpicsDeskRendersTheNameItAlwaysHad() {
      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));

      assertEquals("Claude Code (project MCP)", commands.last().name(), "and it is named as before");
    }

    @Test
    void aTicketsChatCarriesTheDeskPromptShellQuoted() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_TICKETS, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(script.contains(QUOTED_TICKETS_PROMPT), script);
      assertTrue(
          script.contains("list_tickets and get_ticket before anything else"),
          "the survey instruction is what makes the pre-approved reads worth having");
      assertTrue(script.contains("point at the epics desk. Do not file an epic from here."), script);
    }

    @Test
    void aTicketsInteractiveLaunchCarriesTheSameAppendix() {
      // The TUI is the same desk, so the steering cannot be a chat-only affordance.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderInteractive(
                  AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_TICKETS, "triage this", pinned, AgentType.CLAUDE)
              .script();

      assertTrue(script.startsWith("exec claude 'triage this'"), script);
      assertTrue(script.contains(QUOTED_TICKETS_PROMPT), script);
    }

    @Test
    void aTicketsLaunchNamesItselfAfterTheDeskRatherThanTheScope() {
      // Still the frontend's contract for one more release. The command now carries the surface as
      // a field, but the name must not move until the string match is deleted on the other side —
      // renaming it in the same release would move every ticket session into the epics list.
      service().launchChat(chat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_TICKETS));
      assertEquals("Claude Code (tickets desk)", commands.last().name());

      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_TICKETS));
      assertEquals(
          "Claude Code (tickets desk)",
          commands.last().name(),
          "the scope narrows the URL; it does not name the desk");

      service()
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_TICKETS,
                  AgentLaunchMode.INTERACTIVE,
                  null,
                  null,
                  false,
                  false,
                  null));
      assertEquals("Claude Code terminal (tickets desk)", commands.last().name());
    }

    @Test
    void kimiOpensTheDeskWithoutTheAppendixBecauseItHasNowhereToPutIt() {
      // The documented asymmetry: Kimi has no --append-system-prompt and no ACP field for one, so
      // its tickets desk is steered by its tools and its name alone. It must still be that desk.
      defaultType = AgentType.KIMI;
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      assertEquals(
          "exec kimi acp",
          service
              .renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_TICKETS, pinned, AgentType.KIMI)
              .script());

      service().launchChat(chat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_TICKETS));

      assertEquals("Kimi Code (tickets desk)", commands.last().name());
    }

    @Test
    void theSurfaceComesBackOnTheCommand() {
      // What lets a frontend stop matching " (tickets desk)" in a display name: the command says
      // which surface it is, as a field, in the launch's own answer.
      Command tickets = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_TICKETS));
      assertEquals("project.tickets", tickets.agentSurface());

      Command epics = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));
      assertEquals("project.epics", epics.agentSurface());

      Command terminal =
          service()
              .launch(
                  new AgentLaunchRequest(
                      AgentMcpScope.PROJECT,
                      AgentSurface.PROJECT_TICKETS,
                      AgentLaunchMode.INTERACTIVE,
                      null,
                      null,
                      false,
                      false,
                      null));
      assertEquals("project.tickets", terminal.agentSurface());
    }

    @Test
    void theSignInTerminalCarriesNoSurface() {
      // Nobody starts a sign-in terminal from anywhere in the product, and keying it to a surface
      // would render that surface's configuration into a REPL that must render nothing.
      assertNull(service().launchLogin(AgentType.CLAUDE).agentSurface());
    }

    @Test
    void anAutonomousRunNamesItsOwnSurfaceRatherThanBorrowingTheEpicsDesk() {
      // A different session from a human's epics chat — read-only marked servers, a bootstrap seed,
      // nobody watching. It borrowed AgentDesk.EPICS only because there was nothing else to name.
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
          AgentSurface.PROJECT_TICKETS,
          chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_TICKETS).requiredSurface(),
          "a named surface passes through untouched");
    }

    @Test
    void theTwoDeskSurfacesRenderWhatTheDeskEnumRendered() {
      // The equivalence AgentSurface replaces AgentDesk under, asserted rather than assumed: for
      // both harnesses, in both modes, the rendered script and the command's name are what the
      // enum's two values produced. The literals are what the desk suite asserted before the swap.
      AgentLaunchService service = service();

      assertNull(AgentLaunchService.systemPromptFor(AgentSurface.PROJECT_EPICS));
      assertEquals(
          AgentLaunchService.TICKETS_DESK_PROMPT,
          AgentLaunchService.systemPromptFor(AgentSurface.PROJECT_TICKETS));

      for (AgentType type : List.of(AgentType.CLAUDE, AgentType.KIMI)) {
        // Kimi cannot pin a fresh session id, so each harness pins its own.
        AgentLaunchService.PinnedSession session = service.pinSession(null, false, type);
        assertFalse(
            service
                .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, session, type)
                .script()
                .contains("--append-system-prompt"));
        assertEquals(
            type == AgentType.CLAUDE,
            service
                .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_TICKETS, session, type)
                .script()
                .contains(QUOTED_TICKETS_PROMPT),
            "kimi has nowhere to put an appendix, and that asymmetry is unchanged");
      }

      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));
      assertEquals("Claude Code (project MCP)", commands.last().name());
      service().launchChat(chat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS));
      assertEquals("Claude Code (repository MCP)", commands.last().name());
      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_TICKETS));
      assertEquals("Claude Code (tickets desk)", commands.last().name());
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
    void resumeOfASessionFromAPreviousContainerIsRefused() {
      // THE behaviour change of this move. CommandStore does not outlive the container, so it
      // cannot vouch for a session an earlier one drove. It fails closed: refused, not allowed.
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
                      AgentSurface.PROJECT_EPICS,
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

      assertEquals("Claude Code (project MCP)", commands.last().name());
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
                  AgentSurface.PROJECT_EPICS,
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
                  AgentSurface.PROJECT_EPICS,
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
                  AgentSurface.PROJECT_EPICS,
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
                          AgentSurface.PROJECT_EPICS,
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
                  AgentSurface.PROJECT_EPICS,
                  AgentLaunchMode.INTERACTIVE,
                  null,
                  null,
                  false,
                  false,
                  null));

      assertEquals(CommandKind.TERMINAL, commands.last().kind());
      assertEquals("Claude Code terminal (repository MCP)", commands.last().name());
    }

    @Test
    void launchDefaultsToChatWhenNoModeIsGiven() {
      service()
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.PROJECT_EPICS,
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
                          AgentSurface.PROJECT_EPICS,
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
                  AgentSurface.PROJECT_EPICS,
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

      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));

      assertEquals(
          List.of("You are in " + REPO + " on main for qits."),
          commands.chatSends,
          "the checkout answers branch; the host answers the rest");
    }

    @Test
    void aFactNobodyCanAnswerStaysAsItWasWritten() {
      configurations = withInitialPrompt("Fix {{ticket}}.");

      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));

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
                  AgentSurface.PROJECT_EPICS,
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
                  AgentSurface.PROJECT_EPICS,
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
                  AgentSurface.PROJECT_EPICS,
                  AgentLaunchMode.CHAT,
                  "ignored",
                  null,
                  false,
                  true,
                  null));

      assertEquals(List.of("Read the plan first."), commands.chatSends);
    }

    private AgentSurfaceConfigurations withInitialPrompt(String prompt) {
      return withInitialPrompt(AgentSurface.PROJECT_EPICS, prompt);
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

  // --- the narrowing seam -----------------------------------------------------------------------

  /**
   * {@code narrowProject}/{@code narrowRepository}/{@code narrowWorkspace} were read, validated and
   * then not rendered: the library cannot build a narrowed url without ids it must not know. They
   * are now the host's to honour, through {@link AgentMcpServers#serverFor}, and a host that has not
   * adopted the seam says so on every launch that attaches servers rather than looking as if it had.
   */
  @Nested
  class NarrowingSeam {

    /** A host that implements the seam — what both daemons must now do. */
    private final class NarrowingHost implements AgentMcpServers {
      @Override
      public List<ScopedMcp> serversFor(AgentMcpScope scope) {
        return MCP_SERVERS.serversFor(scope);
      }

      @Override
      public Optional<ScopedMcp> serverFor(
          String key, AgentMcpScope scope, AgentMcpNarrowing narrowing) {
        if (!"repository".equals(key)) {
          return Optional.empty();
        }
        StringBuilder url = new StringBuilder("http://qits:8080/projects/mcp");
        // The canonical order — projectId, repositoryId, workspaceId — because the rendered command
        // line is asserted as a literal on both harnesses.
        if (narrowing.project()) {
          url.append("?projectId=").append(AgentMcpIds.requireId(PROJECT, "projectId"));
        }
        if (narrowing.repository()) {
          url.append(url.indexOf("?") < 0 ? "?" : "&")
              .append("repositoryId=")
              .append(AgentMcpIds.requireId(REPO, "repositoryId"));
        }
        if (narrowing.workspace()) {
          throw new InvalidCommandRequestException(
              "This container serves no workspace, so the repository server cannot be narrowed to"
                  + " one");
        }
        return Optional.of(
            new ScopedMcp(key, url.toString(), ProjectHostMcpServers.READ_ONLY_REPOSITORY_TOOLS));
      }

      @Override
      public boolean honoursNarrowing() {
        return true;
      }
    }

    @Test
    void anAdoptedHostBuildsTheUrlTheDocumentAsksFor() {
      AgentLaunchService service = serviceWith(new NarrowingHost());
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);
      configurations = attaching(true, false, false);

      assertTrue(
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
              .script()
              .contains("mcp?projectId=" + PROJECT + "\"}"),
          "project only, because that is what the attachment asks for");

      configurations = attaching(true, true, false);
      assertTrue(
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
              .script()
              .contains("mcp?projectId=" + PROJECT + "&repositoryId=" + REPO + "\"}"),
          "and the scope no longer decides it");
    }

    @Test
    void aNarrowingTheHostCannotSatisfyRefusesRatherThanDroppingIt() {
      // Dropping the parameter would answer for the whole project where the document asked for one
      // workspace — a session that looks normal and is addressed wider than it was configured.
      AgentLaunchService service = serviceWith(new NarrowingHost());
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);
      configurations = attaching(true, false, true);

      assertThrows(
          InvalidCommandRequestException.class,
          () ->
              service.renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE));
    }

    @Test
    void anUnadoptedHostRendersWhatItAlwaysDidAndTheRecordSaysSo() {
      // The dated half: a daemon picks up a new library at its next release, so a host that has not
      // implemented serverFor keeps rendering its scope mapping. Visible rather than silent.
      configurations = attaching(true, true, true);

      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));

      assertTrue(
          commands.last().script().contains("mcp?projectId=" + PROJECT + "\"}"),
          "the PROJECT scope's own url, whatever the attachment asked for");
      assertTrue(
          new JsonObject(command.agentLaunchRecord())
              .getJsonArray("notes")
              .encode()
              .contains("does not yet honour per-attachment MCP narrowing"),
          command.agentLaunchRecord());
    }

    @Test
    void aSurfaceThatConfiguresNoServersIsNotNagged() {
      // The note belongs to a launch whose document asked for a narrowing. A container with no
      // document asked for nothing and takes the host's mapping by definition.
      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));

      assertEquals(0, new JsonObject(command.agentLaunchRecord()).getJsonArray("notes").size());
    }

    private AgentSurfaceConfigurations attaching(
        boolean project, boolean repository, boolean workspace) {
      return new SeededConfigurationDocument()
          .surface(
              AgentSurface.PROJECT_EPICS,
              false,
              "",
              SeededConfigurationDocument.server(
                  "repository", project, repository, workspace, false, List.of()))
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
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
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
              .surface(AgentSurface.PROJECT_EPICS, false, "")
              .attaching(
                  SeededConfigurationDocument.external(
                      "docs", "https://docs.example/mcp", "", "", List.of()))
              .configurations();

      String script =
          service
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
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
              AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned);

      assertEquals(2, config.mcpServers().size());
      AcpSessionConfig.AcpMcpServer stripe = config.mcpServers().get(1);
      assertEquals("stripe", stripe.name());
      assertEquals(Map.of("Authorization", STRIPE_TOKEN), stripe.headers());
      assertEquals(
          List.of("listCustomers"),
          stripe.enabledTools(),
          "kimi takes bare names, and an operator's list is honoured in either form");
      assertEquals(Map.of(), config.mcpServers().get(0).headers(), "a platform server has none");
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
                      AgentSurface.PROJECT_EPICS.key(),
                      new AgentSurfaceConfiguration(
                          AgentSurface.PROJECT_EPICS.key(),
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
                      AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE));

      assertTrue(refused.getMessage().contains("displace it silently"), refused.getMessage());
      assertTrue(refused.getMessage().contains("project.epics"), refused.getMessage());
    }

    @Test
    void theHeaderValueIsHandedOverForRedactionRatherThanStored() {
      // The rendered command line is kept on the command, answered by the API and shown on a command
      // page — which was right for every launch this platform had until a credential started riding
      // in one. The process is spawned with the script as rendered; what is stored is this.
      configurations = withStripe();

      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));
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

      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));
      JsonObject record = new JsonObject(command.agentLaunchRecord());

      assertEquals(List.of("stripe"), record.getJsonArray("externalMcpServers").getList());
      assertFalse(command.agentLaunchRecord().contains("sk-live"), command.agentLaunchRecord());
      assertFalse(command.agentLaunchRecord().contains("mcp.stripe.com"));
    }

    /** The epics desk with the platform's repository server and one catalog entry attached. */
    private AgentSurfaceConfigurations withStripe() {
      return new SeededConfigurationDocument()
          .surface(
              AgentSurface.PROJECT_EPICS,
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
                  "{\"version\":1,\"surfaces\":[{\"surface\":\"project.epics\",\"harness\":\"CLAUDE\","
                      + "\"permissionMode\":\"PROMPT\",\"model\":\"opus\",\"effort\":\"xhigh\","
                      + "\"remoteControl\":true,\"activityTracking\":false}]}",
                  "test"));

      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));
      JsonObject record = new JsonObject(command.agentLaunchRecord());

      assertEquals("project.epics", record.getString("surface"));
      assertEquals("CLAUDE", record.getString("harness"));
      assertEquals("opus", record.getString("model"));
      assertEquals("xhigh", record.getString("effort"));
      assertEquals("PROMPT", record.getString("permissionMode"));
      assertTrue(record.getBoolean("remoteControl"));
      assertEquals("qits project.epics main", record.getString("remoteControlName"));
      assertFalse(record.getBoolean("activityTracking"));
      assertTrue(record.getBoolean("configured"), "this container was born with a document");
      assertEquals(
          "repository", record.getJsonArray("mcpServers").getJsonObject(0).getString("server"));
      assertFalse(record.getJsonArray("mcpServers").getJsonObject(0).getBoolean("readOnly"));
      assertEquals(0, record.getJsonArray("externalMcpServers").size());
    }

    @Test
    void aContainerWithNoDocumentSaysItWasNotConfigured() {
      // The difference between "configured this way" and "nobody had configured it", which a reader
      // of the record cannot otherwise tell — and which is exactly the rollout's own question.
      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));

      assertFalse(new JsonObject(command.agentLaunchRecord()).getBoolean("configured"));
    }

    @Test
    void anUnattendedRunRecordsThatItsServersWereFenced() {
      Command command = service().launchAutonomous("Composed run");
      JsonObject record = new JsonObject(command.agentLaunchRecord());

      assertEquals("epic.autonomous", record.getString("surface"));
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
                  "{\"version\":1,\"surfaces\":[{\"surface\":\"project.tickets\",\"harness\":\"KIMI\","
                      + "\"permissionMode\":\"SKIP_PERMISSIONS\",\"effort\":\"high\","
                      + "\"systemPrompt\":\"You are the desk.\"}]}",
                  "test"));

      Command command =
          service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_TICKETS));
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
                      AgentSurface.PROJECT_TICKETS,
                      AgentLaunchMode.INTERACTIVE,
                      null,
                      null,
                      false,
                      false,
                      null));

      assertEquals(
          "project.tickets", new JsonObject(command.agentLaunchRecord()).getString("surface"));
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
      Command command = service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));

      assertFalse(command.agentLaunchRecord().contains("header"), command.agentLaunchRecord());
      assertFalse(command.agentLaunchRecord().contains("Authorization"));
    }
  }

  // --- the ACP session config -------------------------------------------------------------------

  @Nested
  class AcpConfig {

    @Test
    void scopedServersRideSessionNewWithBareToolNames() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      AcpSessionConfig config = service.buildAcpSessionConfig(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, pinned);

      assertEquals("/workspace", config.cwd());
      assertEquals(1, config.mcpServers().size());
      AcpSessionConfig.AcpMcpServer server = config.mcpServers().get(0);
      assertEquals("repository", server.name());
      assertTrue(
          server.enabledTools().contains("taskPrompt"),
          "kimi takes bare names, not the mcp__server__ form: " + server.enabledTools());
      assertFalse(server.enabledTools().contains("mcp__repository__taskPrompt"));
    }

    @Test
    void aResumedSessionIsCarriedAndAFreshOneIsNot() {
      commands.ownedSessions.put(KIMI_SESSION, "KIMI");
      AgentLaunchService service = service();

      assertEquals(
          KIMI_SESSION,
          service
              .buildAcpSessionConfig(
                  AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, service.pinSession(KIMI_SESSION, false, AgentType.KIMI))
              .resumeSessionId());
      assertNull(
          service
              .buildAcpSessionConfig(
                  AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, service.pinSession(null, false, AgentType.KIMI))
              .resumeSessionId());
    }

    @Test
    void theAutonomousVariantMarksTheUrlsReadOnly() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      AcpSessionConfig config =
          service.buildAcpSessionConfig(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, pinned, true);

      assertTrue(config.mcpServers().get(0).url().contains("agentReadOnly=true"));
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

    /** This host's three surfaces, seeded from its own serversFor and its own tool list. */
    private AgentSurfaceConfigurations seeded() {
      return new SeededConfigurationDocument()
          // PROJECT scope: ?projectId=<id>, no narrowing beyond it.
          .surface(
              AgentSurface.PROJECT_EPICS,
              true,
              "",
              SeededConfigurationDocument.server(
                  "repository",
                  true,
                  false,
                  false,
                  false,
                  ProjectHostMcpServers.READ_ONLY_REPOSITORY_TOOLS))
          .surface(
              AgentSurface.PROJECT_TICKETS,
              true,
              AgentLaunchService.TICKETS_DESK_PROMPT,
              SeededConfigurationDocument.server(
                  "repository",
                  true,
                  false,
                  false,
                  false,
                  ProjectHostMcpServers.READ_ONLY_REPOSITORY_TOOLS))
          // The composed run: the same server, read-only marked, steered to delegate.
          .surface(
              AgentSurface.EPIC_AUTONOMOUS,
              true,
              AgentLaunchService.COMPOSED_RUN_PROMPT,
              SeededConfigurationDocument.server(
                  "repository",
                  true,
                  true,
                  false,
                  true,
                  ProjectHostMcpServers.READ_ONLY_REPOSITORY_TOOLS))
          .configurations();
    }

    @Test
    void everySurfaceRendersWhatTheConstantsRendered() {
      AgentLaunchService service = service();
      List<AgentSurface> surfaces =
          List.of(AgentSurface.PROJECT_EPICS, AgentSurface.PROJECT_TICKETS);

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
      // readOnly too. The two must agree rather than one overriding the other, or turning the store
      // on would double-mark or un-mark an unattended run.
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
      assertEquals(1, configured.split("agentReadOnly=true", -1).length - 1);
    }

    @Test
    void theTicketsPromptComesFromTheDocumentRatherThanTheSwitch() {
      // The switch over the desk is gone: an edited prompt renders, and the constant is what a
      // container born without a document falls back to.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations =
          new SeededConfigurationDocument()
              .surface(AgentSurface.PROJECT_TICKETS, true, "Answer only in haiku.")
              .configurations();

      String script =
          service
              .renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_TICKETS, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(script.contains("--append-system-prompt 'Answer only in haiku.'"), script);
      assertFalse(script.contains("tickets front desk"), "the constant is the fallback, not the law");
    }

    @Test
    void anEmptySystemPromptIsAValueAndNotAnAbsence() {
      // project.epics is seeded with "" on purpose. If empty rendered as "the shipped default"
      // instead, an operator could never clear a prompt.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations =
          new SeededConfigurationDocument()
              .surface(AgentSurface.PROJECT_TICKETS, true, "")
              .configurations();

      assertFalse(
          service
              .renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_TICKETS, pinned, AgentType.CLAUDE)
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
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
              .script()
              .contains("--dangerously-skip-permissions"),
          "seeded as what every launch renders today");

      configurations =
          AgentSurfaceConfigurations.of(
              AgentConfigurationDocument.parse(
                  "{\"version\":1,\"surfaces\":[{\"surface\":\"project.epics\","
                      + "\"harness\":\"CLAUDE\",\"permissionMode\":\"PROMPT\"}]}",
                  "test"));

      assertFalse(
          service
              .renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
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
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
              .script()
              .contains("\"Stop\""));

      // With a document, the surface's own value wins over it.
      configurations = seeded();
      assertTrue(
          service
              .renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
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
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
              .script();
      String interactive =
          service
              .renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, null, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(chat.contains("--model 'haiku'"), chat);
      assertTrue(chat.contains("--effort 'low'"), chat);
      assertTrue(interactive.contains("--model 'haiku'"), interactive);
      assertTrue(interactive.contains("--effort 'low'"), interactive);
    }

    @Test
    void anInteractiveLaunchTakesTheRemoteControlFlagNamedAfterTheSurfaceAndTheBranch() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations = knobs("\"remoteControl\":true");
      assertTrue(
          service
              .renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, null, pinned, AgentType.CLAUDE)
              .script()
              .contains("--remote-control 'qits project.epics main'"));

      configurations = knobs("\"remoteControl\":false");
      assertFalse(
          service
              .renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, null, pinned, AgentType.CLAUDE)
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
              .renderChat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
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
      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));
      assertEquals(
          "qits project.epics main",
          remoteControlNameAskedFor(commands.last().protocolFactory()),
          "named by surface and branch, not by the container's hostname");

      configurations = knobs("\"remoteControl\":false");
      service().launchChat(chat(AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS));
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
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, null, pinned, AgentType.KIMI)
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
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE),
              service.renderInteractive(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, null, pinned, AgentType.CLAUDE),
              service.renderLogin(AgentType.CLAUDE))) {
        assertEquals(List.of(), AgentRemoteControl.disabledBy(spec.environment()), spec.script());
      }
    }

    /** One epics-desk row carrying {@code extraFields}, everything else as it ships. */
    private AgentSurfaceConfigurations knobs(String extraFields) {
      return AgentSurfaceConfigurations.of(
          AgentConfigurationDocument.parse(
              "{\"version\":1,\"surfaces\":[{\"surface\":\"project.epics\",\"harness\":\"CLAUDE\","
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
      // simply cannot do half its job. This host serves one server; observability is the workspace
      // daemon's.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations =
          new SeededConfigurationDocument()
              .surface(
                  AgentSurface.PROJECT_EPICS,
                  true,
                  "",
                  SeededConfigurationDocument.server(
                      "observability", false, true, true, false, List.of()))
              .configurations();

      InvalidCommandRequestException refusal =
          assertThrows(
              InvalidCommandRequestException.class,
              () ->
                  service.renderChat(
                      AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE));

      assertTrue(refusal.getMessage().contains("observability"), refusal.getMessage());
      assertTrue(refusal.getMessage().contains("project.epics"), refusal.getMessage());
    }

    @Test
    void detachingAServerIsAConfigurationAndNotAnOmission() {
      // An empty attachment list is a decision; a surface that says nothing about MCP takes the
      // host's whole mapping. Both must be renderable, or "attach nothing" would be unreachable.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations =
          new SeededConfigurationDocument()
              .surface(AgentSurface.PROJECT_EPICS, true, "")
              .configurations();

      String script =
          service
              .renderChat(
                  AgentMcpScope.PROJECT, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE)
              .script();

      assertFalse(script.contains("\"repository\":"), script);
      assertFalse(
          script.contains("--mcp-config"),
          "no servers means no --mcp-config at all, not an empty one");
    }
  }
}
