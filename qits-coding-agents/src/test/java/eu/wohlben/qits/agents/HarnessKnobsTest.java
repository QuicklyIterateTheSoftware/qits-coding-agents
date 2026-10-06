package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The three harness knobs a surface's configuration can set — model, effort and remote control —
 * rendered honestly per harness.
 *
 * <p>Honestly is the whole content of this suite. Claude Code has all three; Kimi Code has a model
 * flag, <em>no effort concept at all</em> and no remote-control mechanism, and the two things it
 * cannot render must show up as a reported drop rather than as an unknown flag (which would fail the
 * launch) or as silence (which would leave a surface reading as configured while its sessions ran as
 * the default one).
 */
class HarnessKnobsTest {

  @Nested
  class Claude {

    @Test
    void theModelAndTheEffortRideEveryShape() {
      // The model was already supported and only one refinement flow ever set it; the effort flag is
      // new and no launch passed it before this. Both belong on every shape a surface can be.
      assertTrue(claude().model("opus").effort("xhigh").chat().script().contains(" --model 'opus'"));
      assertTrue(claude().model("opus").effort("xhigh").chat().script().contains(" --effort 'xhigh'"));
      assertTrue(claude().model("opus").effort("xhigh").start().script().contains(" --effort 'xhigh'"));
      assertTrue(
          claude().effort("max").run("go").script().contains(" --effort 'max'"),
          "a one-off run is a session too");
    }

    @Test
    void anEffortTheHarnessNeverEnumeratedStillRenders() {
      // The capability report says which values it enumerated, not which are legal: the levels
      // depend on the model and belong to the binary in the image. A launch that checked the
      // configuration against a shipped list would be this platform hardcoding a catalogue again.
      assertTrue(claude().effort("ludicrous").chat().script().contains(" --effort 'ludicrous'"));
      assertTrue(
          claude().model("claude-fable-5").chat().script().contains(" --model 'claude-fable-5'"),
          "pinning a full model id is exactly what somebody comes to that field for");
    }

    @Test
    void blanksAreTheHarnessesOwnChoiceRatherThanEmptyFlags() {
      String script = claude().model("").effort("   ").chat().script();

      assertFalse(script.contains("--model"), script);
      assertFalse(script.contains("--effort"), script);
    }

    @Test
    void remoteControlIsAnInteractiveFlagAndNothingOnAChat() {
      // --remote-control parses under --print and is dropped on the headless branch, so rendering it
      // onto a chat would be a flag that does nothing. The chat's enable rides the control channel
      // instead (StreamJsonChatProtocol) — the same knob, the other mechanism.
      assertTrue(
          claude().remoteControl("qits epic.agent refining/x").start().script()
              .contains(" --remote-control 'qits epic.agent refining/x'"));
      assertFalse(claude().remoteControl("qits epic.chat main").chat().script().contains("--remote-control"));
      assertFalse(claude().remoteControl("  ").start().script().contains("--remote-control"));
    }

    @Test
    void theRenderedNameIsShellQuotedLikeEverythingElse() {
      assertTrue(
          claude().remoteControl("it's a name").start().script()
              .contains("--remote-control 'it'\\''s a name'"));
    }

    @Test
    void nothingIsDropped() {
      assertEquals(List.of(), claude().model("opus").effort("high").remoteControl("n").renderNotes());
    }
  }

  @Nested
  class Kimi {

    @Test
    void theModelRendersAndTheEffortIsReportedRatherThanPassed() {
      CodingAgent agent = kimi().model("k2").effort("high");
      String script = agent.start().script();

      assertTrue(script.contains(" -m 'k2'"), script);
      assertFalse(script.contains("effort"), "an unknown flag would fail the launch, not configure it");
      assertEquals(1, agent.renderNotes().size(), agent.renderNotes().toString());
      assertTrue(agent.renderNotes().get(0).contains("no effort concept"), agent.renderNotes().get(0));
      assertTrue(agent.renderNotes().get(0).contains("high"), "the note names the level that was set");
    }

    @Test
    void remoteControlIsReportedTheSameWay() {
      CodingAgent agent = kimi().remoteControl("qits workspace.agent main");

      assertFalse(agent.start().script().contains("remote-control"));
      assertTrue(agent.renderNotes().get(0).contains("Remote Control"), agent.renderNotes().toString());
    }

    @Test
    void theSystemPromptDropIsReportedToo() {
      // Long-documented and long-silent: a Kimi tickets desk is steered by its tools and its name
      // alone. Silence is what made it read as configured; the note is what makes it readable.
      CodingAgent agent = kimi().appendSystemPrompt(AgentLaunchService.TICKETS_DESK_PROMPT);

      assertTrue(agent.renderNotes().get(0).contains("system-prompt appendix"), agent.renderNotes().toString());
    }

    @Test
    void anUnsetKnobIsNotADrop() {
      assertEquals(List.of(), kimi().effort("").remoteControl(null).appendSystemPrompt("").renderNotes());
    }
  }

  @Nested
  class TheFeatureFlagVariables {

    @Test
    void aLaunchEnvironmentSetsNoneOfThem() {
      // Remote Control depends on a feature-flag evaluation these four switch off. The workspace
      // image sets none of them today — it sets DISABLE_AUTOUPDATER=1 and nothing else — and a later
      // "turn off telemetry" change would otherwise take the bridge down with nothing saying why.
      // A rendered launch's overlay is asserted here; the daemon's own environment is checked at
      // launch time, and reported rather than refused.
      assertEquals(
          List.of("DISABLE_TELEMETRY", "DO_NOT_TRACK", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
              "DISABLE_GROWTHBOOK"),
          AgentRemoteControl.DISABLING_VARIABLES);
      assertEquals(List.of(), AgentRemoteControl.disabledBy(Map.of("HOME", "/claude-home")));
    }

    @Test
    void oneThatIsSetIsNamed() {
      assertEquals(
          List.of("DO_NOT_TRACK"),
          AgentRemoteControl.disabledBy(Map.of("HOME", "/claude-home", "DO_NOT_TRACK", "1")));
    }

    @Test
    void anExplicitlyEmptyValueIsHowAContainerUnsetsAnInheritedOne() {
      assertEquals(List.of(), AgentRemoteControl.disabledBy(Map.of("DISABLE_TELEMETRY", "")));
    }

    private static final String TITLE = "Comments on every work entity";

    private static EntityFacts facts(String title, String status, boolean blocked) {
      return new EntityFacts(title, status, blocked);
    }

    @Test
    void anEntityLessSessionSaysWhatItIsAndWhichWorkItIsIn() {
      // The auto-generated name is hostname-derived, so every session this platform starts would be
      // indistinguishable in a session list whose whole job is telling sessions apart. No entity id
      // known here, so this is the surface-and-branch shape.
      assertEquals(
          "qits epic.agent refining/agent-configuration-system",
          AgentRemoteControl.sessionName(
              null, EntityFacts.NONE, AgentSurface.EPIC_AGENT, "refining/agent-configuration-system"));
      assertEquals(
          "qits workspace.chat",
          AgentRemoteControl.sessionName(null, null, AgentSurface.WORKSPACE_CHAT, "  "),
          "a container that does not know its branch is named by its surface, not by nothing");
      assertEquals(
          "qits epic.agent refining/x",
          AgentRemoteControl.sessionName(
              "   ", facts(TITLE, "IMPLEMENTED", false), AgentSurface.EPIC_AGENT, "refining/x"),
          "a blank id is absent, so title and status have nothing to name and are not used");
    }

    @Test
    void theProjectDeskIsTheFrontDeskNotItsSurfaceKey() {
      assertEquals(
          "\uD83D\uDC1E qits front desk",
          AgentRemoteControl.sessionName(null, EntityFacts.NONE, AgentSurface.PROJECT_WORK, ""));
      assertEquals(
          AgentRemoteControl.FRONT_DESK_NAME,
          AgentRemoteControl.sessionName(null, null, "project.work", "main"),
          "the branch does not matter on the desk");
      assertEquals(
          "\uD83D\uDC1E qits front desk",
          AgentRemoteControl.sessionName(null, null, "project.work", null));
    }

    @Test
    void aKnownEntityReadsSquareIdAndTitle() {
      assertEquals(
          "\uD83D\uDFE6 qits-555 Comments on every work entity",
          AgentRemoteControl.sessionName(
              "qits-555", facts(TITLE, "IMPLEMENTED", false), AgentSurface.WORKSPACE_AGENT,
              "ticket/comments-on-every-work-entity"),
          "the branch and the type are not part of the name");
      assertEquals(
          "\uD83D\uDFE6 qits-555 Comments on every work entity",
          AgentRemoteControl.sessionName(
              " qits-555 ", facts(TITLE, "implemented", false), AgentSurface.PROJECT_WORK, null),
          "the id is trimmed, the status word is case-insensitive, and an entity outranks the desk");
    }

    @Test
    void thePaletteHasOneSquarePerStatus() {
      // The twin of the UI's STATUS_TONES: grey, purple, purple, blue, blue, blue, check mark,
      // green, grey.
      Map<String, String> expected =
          Map.of(
              "REPORTED", "\u2B1C",
              "REFINED", "\uD83D\uDFEA",
              "READY_FOR_DEV", "\uD83D\uDFEA",
              "IMPLEMENTING", "\uD83D\uDFE6",
              "IMPLEMENTED", "\uD83D\uDFE6",
              "VERIFYING", "\uD83D\uDFE6",
              "VERIFIED", "\u2705",
              "DONE", "\uD83D\uDFE9",
              "DROPPED", "\u2B1C");
      expected.forEach(
          (status, square) ->
              assertEquals(
                  square + " qits-1 T",
                  AgentRemoteControl.sessionName(
                      "qits-1", facts("T", status, false), "workspace.agent", "b"),
                  status));
      assertEquals(
          List.of(
              0x2B1C, 0x1F7EA, 0x1F7EA, 0x1F7E6, 0x1F7E6, 0x1F7E6, 0x2705, 0x1F7E9, 0x2B1C),
          java.util.Arrays.stream(EntityStatusSquare.values())
              .map(value -> value.square().codePointAt(0))
              .toList(),
          "the code points, spelled out, so a mistyped surrogate pair cannot hide");
      assertEquals(
          "🟪",
          EntityStatusSquare.of("READY_FOR_DEV").orElseThrow().square(),
          "READY_FOR_DEV is REFINED's twin square, not blocking");
      assertTrue(EntityStatusSquare.of("ARCHIVED").isEmpty(), "an unknown word has no square");
      assertTrue(EntityStatusSquare.of("  ").isEmpty());
      assertTrue(EntityStatusSquare.of(null).isEmpty());
    }

    @Test
    void aBlockedEntityPutsTheMarkerAgainstTheSquare() {
      // In front, because the list truncates the tail and a person scans it from the left.
      assertEquals(
          "\u2757\uD83D\uDFE6 qits-555 Comments on every work entity",
          AgentRemoteControl.sessionName(
              "qits-555", facts(TITLE, "IMPLEMENTED", true), "workspace.agent", "b"));
      assertEquals(
          "\u2757 qits epic.agent refining/some-epic",
          AgentRemoteControl.sessionName(
              null, facts(null, null, true), AgentSurface.EPIC_AGENT, "refining/some-epic"),
          "a container that cannot name its entity is still marked");
      assertEquals("\u2757 ", AgentRemoteControl.BLOCKED_MARKER, "the marker and one space");
      assertEquals(
          "\u2757\u2705 qits-1 T",
          AgentRemoteControl.sessionName(
              "qits-1", facts("T", "VERIFIED", true), "workspace.agent", "b"),
          "the blocked marker sits in front of VERIFIED's check mark like any other square");
    }

    @Test
    void theNameDegradesOneFactAtATime() {
      assertEquals(
          "qits-555 Comments on every work entity",
          AgentRemoteControl.sessionName("qits-555", facts(TITLE, null, false), "s", "b"),
          "no status, no square");
      assertEquals(
          "qits-555 Comments on every work entity",
          AgentRemoteControl.sessionName("qits-555", facts(TITLE, "ARCHIVED", false), "s", "b"),
          "a word the palette does not know is no square, not a wrong one");
      assertEquals(
          "\u2757 qits-555 Comments on every work entity",
          AgentRemoteControl.sessionName("qits-555", facts(TITLE, " ", true), "s", "b"),
          "blocked without a square keeps qits-614's marker and its space");
      assertEquals(
          "\uD83D\uDFEA qits-555",
          AgentRemoteControl.sessionName("qits-555", facts("  ", "REFINED", false), "s", "b"),
          "no title, the id alone");
      assertEquals(
          "\u2757\uD83D\uDFEA qits-555",
          AgentRemoteControl.sessionName("qits-555", facts(null, "REFINED", true), "s", "b"));
      assertEquals(
          "qits-555", AgentRemoteControl.sessionName("qits-555", null, "s", "b"), "nothing known");
      assertEquals(
          "\u2757 qits-555",
          AgentRemoteControl.sessionName("qits-555", facts("\n\t", null, true), "s", "b"),
          "a title that sanitises to nothing is no title");
    }

    @Test
    void aTitleIsMadeSafeToTypeIntoATerminal() {
      // A live interactive rename is typed into the PTY, where a newline would submit half of it.
      assertEquals(
          "Fix the thing now please",
          AgentRemoteControl.sanitisedTitle("  Fix\tthe\r\nthing\u0007 now \u0000  please \n"));
      assertEquals(
          "\uD83D\uDFE6 qits-555 a b",
          AgentRemoteControl.sessionName("qits-555", facts("a\nb", "IMPLEMENTED", false), "s", "b"));
      assertEquals("", AgentRemoteControl.sanitisedTitle(null));
      assertEquals("", AgentRemoteControl.sanitisedTitle(" \r\n "));
    }

    @Test
    void aLongTitleIsCutAtTheLimitInCodePoints() {
      String exact = "x".repeat(AgentRemoteControl.TITLE_LIMIT);
      assertEquals(exact, AgentRemoteControl.sanitisedTitle(exact), "at the limit, nothing is cut");

      String cut = AgentRemoteControl.sanitisedTitle("y".repeat(200));
      assertEquals(120, cut.codePointCount(0, cut.length()));
      assertEquals("y".repeat(119) + "\u2026", cut, "the ellipsis counts towards the limit");

      // Every character an astral emoji: 200 code points, 400 chars. A char-based cut would keep 60
      // emoji, or split one in half.
      String bugs = "\uD83D\uDC1E".repeat(200);
      String cutBugs = AgentRemoteControl.sanitisedTitle(bugs);
      assertEquals(120, cutBugs.codePointCount(0, cutBugs.length()));
      assertEquals("\uD83D\uDC1E".repeat(119) + "\u2026", cutBugs, "no surrogate is split");

      assertEquals(
          "a".repeat(118) + "\u2026",
          AgentRemoteControl.sanitisedTitle("a".repeat(118) + " " + "b".repeat(10)),
          "a cut that lands after a space does not leave the space before the ellipsis");
    }
  }

  private static CodingAgent claude() {
    return CodingAgentFactory.ofType(AgentType.CLAUDE);
  }

  private static CodingAgent kimi() {
    return CodingAgentFactory.ofType(AgentType.KIMI);
  }
}
