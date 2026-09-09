package eu.wohlben.qits.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Remote Control: the session name a launch passes, and the four environment variables that turn the
 * feature off without saying so.
 *
 * <p><b>Two mechanisms, and the code has them the opposite way round from the intuition.</b> An
 * interactive launch takes the flag — {@code claude --remote-control "<name>"} — because that is a
 * real interactive session. A chat launch runs {@code --print --input-format stream-json}, where the
 * harness parses the flag and drops it on the headless branch, so the chat transport asks for the
 * bridge over the SDK control channel instead once the session announces itself ({@code
 * StreamJsonChatProtocol}). Both are driven by the same per-surface knob, and neither is validated
 * against the launch shape: <b>when the knob is on, the flag is set</b>. An operator who does not
 * want a bridge turns the knob off.
 *
 * <p><b>The setting is deliberately not used.</b> {@code remoteControlAtStartup} in user settings
 * would do the same job and is the wrong door: a launch points {@code HOME} at the shared credential
 * volume, so writing it would switch Remote Control on for every session on the platform at once,
 * which is the exact opposite of a per-surface knob.
 *
 * <p><b>The name is worth passing.</b> Left to itself the harness derives the session name from the
 * hostname, so every session this platform starts would be indistinguishable in the claude.ai
 * session list — a list whose whole job is telling sessions apart. The surface says what the session
 * is for and the branch says which piece of work it is in, which is the pair a reader of that list
 * needs.
 */
public final class AgentRemoteControl {

  private AgentRemoteControl() {}

  /**
   * The four environment variables that disable the feature-flag evaluation Remote Control depends
   * on. The workspace image sets none of them today — only {@code DISABLE_AUTOUPDATER=1} — and a
   * later "turn off telemetry" change would otherwise switch this feature off with nothing in any
   * answer to say why. {@link #disabledBy} is the check; a launch reports rather than refuses,
   * because a session without a bridge is still a working session.
   */
  public static final List<String> DISABLING_VARIABLES =
      List.of(
          "DISABLE_TELEMETRY",
          "DO_NOT_TRACK",
          "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
          "DISABLE_GROWTHBOOK");

  /**
   * Which of {@link #DISABLING_VARIABLES} are set — in this launch's environment overlay, or,
   * failing that, in the daemon's own environment, which is what the launched process inherits.
   *
   * <p>Only "set to something" counts: the harness reads these as presence flags, and an explicit
   * empty value is how a container unsets an inherited one.
   */
  public static List<String> disabledBy(Map<String, String> launchEnvironment) {
    List<String> found = new ArrayList<>();
    for (String key : DISABLING_VARIABLES) {
      String value =
          launchEnvironment != null && launchEnvironment.containsKey(key)
              ? launchEnvironment.get(key)
              : System.getenv(key);
      if (value != null && !value.isBlank()) {
        found.add(key);
      }
    }
    return List.copyOf(found);
  }

  /**
   * The name a session is listed under: what it is for, and which piece of work it is in.
   *
   * <p>{@code qits} leads because that list is not this platform's — it holds whatever else the
   * operator's account is running — and the surface key and the branch are the two facts that tell
   * one platform session from another. A container that does not know its branch yet is named by its
   * surface alone rather than by nothing.
   */
  public static String sessionName(String surfaceKey, String branch) {
    String key = surfaceKey == null || surfaceKey.isBlank() ? "session" : surfaceKey;
    return branch == null || branch.isBlank() ? "qits " + key : "qits " + key + " " + branch;
  }

  /** {@link #sessionName(String, String)} for a surface value. */
  public static String sessionName(AgentSurface surface, String branch) {
    return sessionName(surface == null ? null : surface.key(), branch);
  }
}
