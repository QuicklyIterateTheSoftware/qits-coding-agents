package eu.wohlben.qits.agents;

/**
 * The Claude sign-in terminal ({@link AgentLaunchService#launchLogin}) cannot run, because the
 * shared credential volume's {@code .credentials.json} already carries qits-egress's placeholder
 * rather than a real login — see the dossier page "Claude credential in qits-egress".
 *
 * <p>Once qits-egress holds this node's real login, it keeps a copy of the token in its own
 * database and injects it into every intercepted call; the placeholder on the shared volume exists
 * only so {@code claude auth status} still answers signed-in. A person completing the REPL
 * onboarding against that placeholder would type a real OAuth code back into the file, which breaks
 * two things at once: the swap qits-egress's injection depends on (it would start forwarding a
 * credential nobody is refreshing any more), and the refresh-token rotation qits-egress owns instead
 * (the volume's copy and qits-egress's held one would now disagree about which refresh token is
 * current). Refusing before rendering anything is cheaper than either.
 *
 * <p>A distinct type rather than {@link AgentNotSignedInException}: the harness is not signed out —
 * {@code claude auth status} reports {@code loggedIn: true} for the placeholder without any network
 * call — so that gate does not catch this, and the fix is not "sign in here" but "sign in through
 * the runner instead", a different door entirely.
 */
public class ClaudeLoginHeldByEgressException extends RuntimeException {

  public ClaudeLoginHeldByEgressException() {
    super("the Claude login is held by qits-egress; sign in with 'qits targets sign-in'");
  }
}
