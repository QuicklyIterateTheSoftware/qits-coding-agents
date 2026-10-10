package eu.wohlben.qits.agents;

/**
 * What a container knows about the ticket or epic it was created for, beyond its id: the three
 * facts a session name is rendered from — see {@link AgentRemoteControl#sessionName(String,
 * EntityFacts, String, String)}.
 *
 * <p>Seeded from {@link AgentDefaults} at boot and moved by {@code AgentLaunchService.setEntity}
 * while the container lives, because all three change under a running session: a title is edited,
 * a status is transitioned, a block is raised and lifted. Held as one value rather than three
 * fields so a change is one volatile write, and a session is never named from a title of one
 * moment and a status of another.
 *
 * @param title the entity's title as the host stores it, or null/blank when unknown. Raw: the name
 *     sanitises it, so a host passes what it has rather than guessing what a PTY can take
 * @param status the entity's status word — {@code REPORTED}, {@code REFINED}, {@code
 *     READY_FOR_DEV}, {@code IMPLEMENTED}, {@code VERIFIED}, {@code DONE}, {@code DROPPED} — or
 *     null/blank when unknown. A word this
 *     library does not know renders no square rather than failing; see {@link EntityStatusSquare}
 * @param blocked whether the entity is BLOCKED
 * @param blockSource why it is, as the host words it — {@code EXPLICIT} (a person or agent blocked
 *     it), {@link #AGENT_WAITING} (only derived: its agent session is idle, waiting on a person) or
 *     {@code BOTH} — or null, which means explicit. Read only while {@code blocked}; a word this
 *     library does not know reads as explicit, so a new source never hides the block
 */
public record EntityFacts(String title, String status, boolean blocked, String blockSource) {

  /**
   * The one block source that changes the name: the block is derived from an agent waiting on a
   * person and from nothing else, so the session wears {@link
   * AgentRemoteControl#AGENT_WAITING_GLYPH} rather than {@link AgentRemoteControl#BLOCKED_GLYPH}.
   */
  public static final String AGENT_WAITING = "AGENT_WAITING";

  /** Nothing known: no title, no status, not blocked. */
  public static final EntityFacts NONE = new EntityFacts(null, null, false);

  /** Facts with no block source, which is an explicit block — the shape before qits-895. */
  public EntityFacts(String title, String status, boolean blocked) {
    this(title, status, blocked, null);
  }

  /** Whether the block is only an agent waiting on a person: blocked, with {@link #AGENT_WAITING}. */
  public boolean waitingOnAPerson() {
    return blocked && AGENT_WAITING.equals(blockSource);
  }

  /**
   * These facts with only the blocked flag changed and the block source cleared — what {@code
   * setBlocked} is now.
   */
  public EntityFacts withBlocked(boolean blocked) {
    return withBlocked(blocked, null);
  }

  /** These facts with only the blocked flag and its source changed; null source is explicit. */
  public EntityFacts withBlocked(boolean blocked, String blockSource) {
    return new EntityFacts(title, status, blocked, blockSource);
  }

  /**
   * The facts a container booted with, read off its defaults; {@link #NONE} for a host that passes
   * no defaults at all.
   */
  public static EntityFacts of(AgentDefaults defaults) {
    if (defaults == null) {
      return NONE;
    }
    return new EntityFacts(
        defaults.entityTitle().orElse(null),
        defaults.entityStatus().orElse(null),
        defaults.entityBlocked());
  }
}
