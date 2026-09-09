package eu.wohlben.qits.agents;

/**
 * The launch you asked for cannot run, because nobody has signed this harness in on the shared
 * credential volume.
 *
 * <p><b>What this replaces is a substitution.</b> Until now an unauthenticated harness silently
 * turned the session you asked for into something else: the launch paths answered {@code
 * launchLogin(type)} — a bare REPL — and the caller redirected you to its command page. You asked
 * for a chat about an epic and got a login terminal, and nothing in the answer said so. It was the
 * wrong shape twice over: a different session than the one requested, and an invisible swap.
 *
 * <p>So the launch <b>refuses and names the reason</b>, and the caller — which knows what it was
 * trying to open — tells the user and offers the login terminal as a deliberate next step. {@link
 * AgentLaunchService#launchLogin} stays exactly where it was and is reachable on its own: somebody
 * has to complete the OAuth once per credential volume, and that is a door, not a fallback.
 *
 * <p><b>The unattended paths matter most here.</b> A composed run and a ticket dispatch have no
 * human watching, so "started an agent" that is actually a login prompt nobody will ever look at is
 * a green-while-dead shape: the dispatch reports success, the workspace sits at a sign-in screen,
 * and the work never happens. Those paths now fail loudly, which is the whole reason this refusal is
 * typed rather than a message on a command.
 *
 * <p>A distinct type rather than {@code InvalidCommandRequestException}: the request was not
 * malformed and the caller cannot fix it by asking differently. Somebody has to sign in, once, and a
 * daemon mapping this to its API can say so.
 */
public class AgentNotSignedInException extends RuntimeException {

  private final AgentType harness;

  public AgentNotSignedInException(AgentType harness) {
    super(
        "Nobody has signed "
            + label(harness)
            + " in on this platform's shared credential volume, so this session cannot start."
            + " Open the "
            + label(harness)
            + " sign-in terminal to complete it once for every container on the volume.");
    this.harness = harness;
  }

  /** Which harness has nobody signed in — what a caller offers the sign-in terminal for. */
  public AgentType harness() {
    return harness;
  }

  private static String label(AgentType harness) {
    if (harness == null) {
      return "the coding agent";
    }
    return switch (harness) {
      case CLAUDE -> "Claude Code";
      case KIMI -> "Kimi Code";
    };
  }
}
