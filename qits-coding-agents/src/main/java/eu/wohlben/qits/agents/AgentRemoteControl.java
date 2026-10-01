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
 * session list — a list whose whole job is telling sessions apart. The qualified entity id leads
 * when one is known, because it is the handle a person already uses for the same work everywhere
 * else — MCP tools, comments, {@code qits work} — and because the claude.ai list truncates the tail
 * of a long name, where a surface key is identical across every session this platform ever
 * dispatches. The surface-and-branch shape stays as the fallback for a container that cannot answer
 * which ticket or epic it is for.
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
   * The name a session is listed under: the qualified entity id it runs for, when one is known,
   * else what it is for and which piece of work it is in.
   *
   * <p>Blank counts as absent for every argument, including a trimmed {@code entityId}. With an id
   * and a branch the name is {@code "<entityId>: <branch>"} — {@code qits-614: ticket/some-slug} —
   * because the id is the handle a person already uses for this work, and the branch after the colon
   * says which piece of it this session is in. With an id and no branch the name is the id alone. With
   * no id this falls back to today's shape: {@code qits} leads because that list is not this
   * platform's — it holds whatever else the operator's account is running — and the surface key and
   * the branch are the two facts that tell one platform session from another. A container that does
   * not know its branch yet is named by its surface alone rather than by nothing.
   */
  public static String sessionName(String entityId, String surfaceKey, String branch) {
    String id = entityId == null ? "" : entityId.trim();
    if (!id.isBlank()) {
      return branch == null || branch.isBlank() ? id : id + ": " + branch;
    }
    String key = surfaceKey == null || surfaceKey.isBlank() ? "session" : surfaceKey;
    return branch == null || branch.isBlank() ? "qits " + key : "qits " + key + " " + branch;
  }

  /** {@link #sessionName(String, String, String)} for a surface value. */
  public static String sessionName(String entityId, AgentSurface surface, String branch) {
    return sessionName(entityId, surface == null ? null : surface.key(), branch);
  }
}
