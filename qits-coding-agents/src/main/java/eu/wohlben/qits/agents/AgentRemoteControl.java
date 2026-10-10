package eu.wohlben.qits.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * session list — a list whose whole job is telling sessions apart. When the container was created
 * for a ticket or epic the name reads like the board does: the entity's status square, its
 * qualified id and its title — {@code 🟦 qits-555 Comments on every work entity}. The square says
 * which phase the work is in at a glance, in the palette the UI's badges use ({@link
 * EntityStatusSquare}); the id is the handle a person already uses for the same work everywhere
 * else — MCP tools, comments, {@code qits work} — and is how they get from the session to its
 * ticket; the title is what they recognise it by. The branch and the entity type used to be in the
 * name and are not any more: the branch slug was a mangled copy of the title that the list
 * truncated anyway, and the type is visible on the ticket the id leads to.
 *
 * <p>Two sessions of the same entity — its refine session and its implement session — now carry the
 * same name. That is accepted (qits-617): the owner chose to drop the branch, and the square tells
 * the phases apart once the entity has moved on.
 *
 * <p>The project desk, which runs for no entity, is named {@link #FRONT_DESK_NAME} rather than by
 * its internal surface key; every other entity-less session keeps the {@code qits <surface>
 * <branch>} shape, because an ad-hoc workspace or an editor has nothing better to be called.
 *
 * <p><b>The name is typed, not only passed.</b> A live rename of an interactive session is {@code
 * /rename <name>} followed by Enter, written into its PTY, so a newline inside a name would submit
 * half of it as a prompt. That is why the title — the one part a person writes freely — is
 * sanitised here rather than trusted, and why the whole name is stripped of control characters on
 * its way out.
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
   * What a blocked entity's sessions are listed with in front of their name: {@code ❗}, U+2757.
   *
   * <p>In front because the claude.ai list truncates the tail of a name, and a person scanning it
   * for "which of these needs me" reads from the left. A symbol rather than a word because a word
   * would push the id and title — the parts the rest of the name exists to show — further into the
   * part that gets cut. Red, and the only red in the name: the status palette leaves red to blocked
   * exactly as the UI's badges do (see {@link EntityStatusSquare}).
   *
   * <p>It sits directly against the status square, with no space — {@code ❗🟦 qits-555 …} — so the
   * two read as one marker; where there is no square it is followed by one space, {@link
   * #BLOCKED_MARKER}.
   */
  public static final String BLOCKED_GLYPH = "\u2757";

  /**
   * {@link #BLOCKED_GLYPH} and one space: the marker in front of a name that has no status square —
   * a status the container does not know, or a session for no entity at all. qits-614's shape,
   * kept where nothing stands beside it.
   */
  public static final String BLOCKED_MARKER = BLOCKED_GLYPH + " ";

  /**
   * What stands in for {@link #BLOCKED_GLYPH} when the block is only derived — the entity's agent
   * session went idle waiting on a person ({@link EntityFacts#waitingOnAPerson}): {@code ⁉️},
   * U+2049 with the emoji presentation selector U+FE0F. Still red and still in front, because it
   * still asks for a person; a different glyph because what it asks for is an answer to the agent,
   * not an unblocking. A block that is explicit, or explicit and derived at once, keeps {@code ❗}.
   * It takes the same place as {@link #BLOCKED_GLYPH}: against the square, or followed by one space
   * where there is none ({@link #AGENT_WAITING_MARKER}).
   */
  public static final String AGENT_WAITING_GLYPH = "\u2049\uFE0F";

  /** {@link #AGENT_WAITING_GLYPH} and one space, the twin of {@link #BLOCKED_MARKER}. */
  public static final String AGENT_WAITING_MARKER = AGENT_WAITING_GLYPH + " ";

  /**
   * The project desk's name: {@link AgentSurface#PROJECT_WORK} runs for no entity, so the
   * entity-less fallback would call it {@code qits project.work} — an internal key no person
   * recognises. A bug icon stands in the slot the status square takes, so the desk lines up with the
   * entity sessions beside it in the list.
   */
  public static final String FRONT_DESK_NAME = "\uD83D\uDC1E qits front desk";

  /**
   * How many code points of a title a name keeps. Long enough for every title a person writes as a
   * title; short enough that a pasted paragraph does not become a session name. A cut title ends in
   * {@code …}, which counts towards the limit.
   */
  public static final int TITLE_LIMIT = 120;

  /**
   * The name a session is listed under.
   *
   * <p>With an entity — a non-blank {@code entityId}, trimmed — the name is {@code
   * [❗]<square> <entityId> <title>}: {@code 🟦 qits-555 Comments on every work entity}, or {@code
   * ❗🟦 qits-555 Comments on every work entity} while it is blocked — {@code ⁉️🟦 …} instead when
   * the block is only its agent waiting on a person ({@link #AGENT_WAITING_GLYPH}). It degrades one
   * fact at a time rather than falling back wholesale, because each fact is independently useful:
   *
   * <ul>
   *   <li>a status this library cannot square (absent, or a word {@link EntityStatusSquare} does
   *       not know) drops the square, and a blocked marker keeps its space: {@code ❗ qits-555
   *       Comments on every work entity};
   *   <li>an absent title — or one that sanitises to nothing — drops the title and keeps the id:
   *       {@code 🟦 qits-555}.
   * </ul>
   *
   * <p>Without an entity, {@link AgentSurface#PROJECT_WORK} is {@link #FRONT_DESK_NAME}, and every
   * other surface keeps the old shape: {@code qits} leads because that list is not this platform's
   * — it holds whatever else the operator's account is running — and the surface key and the branch
   * are the two facts that tell one platform session from another; a container that does not know
   * its branch is named by its surface alone. A blocked flag still puts {@link #BLOCKED_MARKER} in
   * front of either: a container that cannot name its entity can still have been told it is
   * blocked, and the marker is the part of the name that asks for a person.
   *
   * <p>{@code branch} is read only on that last shape. Null {@code facts} is {@link
   * EntityFacts#NONE}; blank counts as absent for every string.
   */
  public static String sessionName(
      String entityId, EntityFacts facts, String surfaceKey, String branch) {
    EntityFacts known = facts == null ? EntityFacts.NONE : facts;
    String glyph = blockGlyph(known);
    String id = entityId == null ? "" : entityId.trim();
    String name;
    if (!id.isEmpty()) {
      Optional<EntityStatusSquare> square = EntityStatusSquare.of(known.status());
      String lead =
          square
              .map(s -> glyph + s.square() + " ")
              .orElse(glyph.isEmpty() ? "" : glyph + " ");
      String title = sanitisedTitle(known.title());
      name = title.isEmpty() ? lead + id : lead + id + " " + title;
    } else {
      String unmarked = entityLessName(surfaceKey, branch);
      name = glyph.isEmpty() ? unmarked : glyph + " " + unmarked;
    }
    return withoutControls(name);
  }

  /** {@link #sessionName(String, EntityFacts, String, String)} for a surface value. */
  public static String sessionName(
      String entityId, EntityFacts facts, AgentSurface surface, String branch) {
    return sessionName(entityId, facts, surface == null ? null : surface.key(), branch);
  }

  /**
   * The glyph a block puts in front of a name: none when not blocked, {@link #AGENT_WAITING_GLYPH}
   * when the block is only an agent waiting on a person, {@link #BLOCKED_GLYPH} otherwise.
   */
  private static String blockGlyph(EntityFacts facts) {
    if (!facts.blocked()) {
      return "";
    }
    return facts.waitingOnAPerson() ? AGENT_WAITING_GLYPH : BLOCKED_GLYPH;
  }

  private static String entityLessName(String surfaceKey, String branch) {
    if (AgentSurface.PROJECT_WORK.key().equals(surfaceKey)) {
      return FRONT_DESK_NAME;
    }
    String key = surfaceKey == null || surfaceKey.isBlank() ? "session" : surfaceKey;
    return branch == null || branch.isBlank() ? "qits " + key : "qits " + key + " " + branch;
  }

  /**
   * A title made safe to be one line of a name typed into a terminal: every control character —
   * newline, carriage return and tab included — becomes a space, runs of whitespace collapse to one
   * space, the ends are trimmed, and anything past {@link #TITLE_LIMIT} code points is cut and ended
   * with {@code …}. Empty for a null or blank title.
   *
   * <p>Counted in code points, not chars, and cut on a code point boundary, so an emoji in a title
   * is never split into half a surrogate pair — which a terminal would render as a replacement
   * character, and the harness might refuse.
   */
  public static String sanitisedTitle(String title) {
    if (title == null) {
      return "";
    }
    StringBuilder collapsed = new StringBuilder(title.length());
    boolean pendingSpace = false;
    for (int i = 0; i < title.length(); ) {
      int cp = title.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
        pendingSpace = true;
        continue;
      }
      if (pendingSpace && !collapsed.isEmpty()) {
        collapsed.append(' ');
      }
      pendingSpace = false;
      collapsed.appendCodePoint(cp);
    }
    String clean = collapsed.toString();
    if (clean.codePointCount(0, clean.length()) <= TITLE_LIMIT) {
      return clean;
    }
    String cut = clean.substring(0, clean.offsetByCodePoints(0, TITLE_LIMIT - 1)).stripTrailing();
    return cut + "\u2026";
  }

  /** Every control character in a finished name as a space — the PTY-safety net for all of it. */
  private static String withoutControls(String name) {
    StringBuilder out = new StringBuilder(name.length());
    name.codePoints().forEach(cp -> out.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp));
    return out.toString();
  }
}
