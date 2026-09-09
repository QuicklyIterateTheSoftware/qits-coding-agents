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

    @Test
    void theSessionNameSaysWhatTheSessionIsAndWhichWorkItIsIn() {
      // The auto-generated name is hostname-derived, so every session this platform starts would be
      // indistinguishable in a session list whose whole job is telling sessions apart.
      assertEquals(
          "qits epic.agent refining/agent-configuration-system",
          AgentRemoteControl.sessionName(
              AgentSurface.EPIC_AGENT, "refining/agent-configuration-system"));
      assertEquals(
          "qits workspace.chat",
          AgentRemoteControl.sessionName(AgentSurface.WORKSPACE_CHAT, "  "),
          "a container that does not know its branch is named by its surface, not by nothing");
    }
  }

  private static CodingAgent claude() {
    return CodingAgentFactory.ofType(AgentType.CLAUDE);
  }

  private static CodingAgent kimi() {
    return CodingAgentFactory.ofType(AgentType.KIMI);
  }
}
