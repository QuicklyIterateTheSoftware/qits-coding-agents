package eu.wohlben.qits.commands;

/**
 * What is checked out here, for the commands that run in it — the ambient half of a launch.
 *
 * <p><b>This is the seam that used to be the whole difference between two copies of this module.</b>
 * The workspace daemon's copy called it {@code WorkspaceContext} and answered four questions about a
 * workspace (repository id, workspace id, branch, commit); the projects daemon's copy called it
 * {@code ProjectContext} and answered four about a project (project id, wrapper repository, branch,
 * commit). Two of the four were the same question in both, and they are the only two this library
 * ever asked: a command records the branch and commit it ran at, and the chat transport names a
 * remote-control session after the branch. The other two were never read here — they were read by
 * each daemon's own response bodies and by its own MCP narrowing.
 *
 * <p>So the identity a host has beyond this is the host's own business. A daemon declares its own
 * interface extending this one ({@code ProjectContext extends CheckoutContext} adding {@code
 * projectId()}/{@code repoName()}; {@code WorkspaceContext extends CheckoutContext} adding {@code
 * repoId()}/{@code workspaceId()}) and hands it in wherever this type is asked for. That is what
 * lets the library stop knowing which product it is inside, which is the point: nothing here should
 * be able to tell a project agent container from a workspace one.
 *
 * <p>None of these are lookups. The daemon is told what it serves at container creation, the
 * checkout is its own working directory, and it already watches HEAD — so the commit comes from
 * what the daemon knows rather than from a git process per launch.
 *
 * <p>Implemented by the host daemon; every method is read at launch time, so a checkout that changes
 * branch mid-session is reflected on the next command rather than being snapshotted here.
 */
public interface CheckoutContext {

  /** The branch currently checked out. */
  String branch();

  /**
   * The commit currently checked out, or null if it is not known yet (a checkout whose first git
   * read has not landed). Null is recorded as-is — the host did the same when its {@code rev-parse}
   * failed.
   */
  String commitHash();
}
