package eu.wohlben.qits.agents;

import eu.wohlben.qits.commands.CheckoutUnavailableException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The container's answer to "what can these harnesses do right now" — every harness's models, effort
 * levels, version <em>and</em> whether anybody is signed in on the shared credential volume.
 *
 * <p><b>Authentication belongs here rather than beside the launch.</b> It is a property of the
 * harness and the credential volume, not of a surface — one sign-in serves every container on the
 * volume — so it belongs in the same answer as the rest of what a harness can do right now, and it
 * reaches the editor the same way. That fold-in is also what let the launch paths stop <em>acting</em>
 * on it: a launch against an unauthenticated harness refuses and names the reason (see {@code
 * AgentNotSignedInException}) instead of silently becoming a login terminal.
 *
 * <p><b>Run once, at container start, off the request path.</b> Each report spawns one or two
 * processes; the daemon holds the answer and serves it on {@code GET /agents/available}, where the
 * host caches it per (harness, image version) behind the editor's dropdowns. Probing per request
 * would put a process spawn on the path of every editor page load, and probing from the editor is
 * impossible anyway — the binaries live in the image, and the editor is a platform-wide route with
 * no container in front of it.
 *
 * <p>Authentication is the one value here that goes stale: an operator can sign in a minute after
 * the report was taken. That is what the sign-in terminal already handles — the next launch on the
 * same volume proceeds — and a cached "signed in: false" is a stale <em>display</em>, never a gate.
 * The gate is {@link AgentAuthStatus}, asked at launch.
 */
public final class HarnessCapabilityService {

  private final ProcessRunner processes;
  private final AgentAuthStatus authStatus;
  private final String claudeMount;
  private final Path workspaceRoot;

  public HarnessCapabilityService(
      ProcessRunner processes,
      AgentAuthStatus authStatus,
      String claudeMount,
      Path workspaceRoot) {
    this.processes = processes;
    this.authStatus = authStatus;
    this.claudeMount = claudeMount;
    this.workspaceRoot = workspaceRoot;
  }

  /** Every harness this platform knows, in {@link AgentType}'s own order. */
  public List<HarnessCapabilities> reportAll() {
    List<HarnessCapabilities> reports = new ArrayList<>();
    for (AgentType harness : AgentType.values()) {
      reports.add(report(harness));
    }
    return List.copyOf(reports);
  }

  /**
   * One harness's report. Never throws and never answers nothing: a binary that is missing, hangs or
   * has changed its output shape yields the shipped fallback flagged {@code probeFailed}, because an
   * empty dropdown is worse than a slightly stale one.
   */
  public HarnessCapabilities report(AgentType harness) {
    HarnessCapabilities capabilities;
    try {
      capabilities =
          CodingAgentFactory.ofType(harness)
              .capabilities(processes, workspaceRoot, probeEnvironment(harness));
    } catch (RuntimeException e) {
      capabilities =
          HarnessCapabilities.shipped(
              harness, harness.name() + " could not be probed: " + e.getMessage());
    }
    return capabilities.withAuth(signedIn(harness), authDetail(harness));
  }

  /**
   * The credential volume's answer, <b>fail-closed</b>: anything that stops the probe from answering
   * reads as not signed in. Claiming a harness is signed in on the strength of a constant is
   * precisely the invention this epic removes, and the cost of being wrong the other way is one
   * honest refusal an operator can act on.
   */
  private boolean signedIn(AgentType harness) {
    try {
      return authStatus != null && authStatus.isLoggedIn(harness);
    } catch (RuntimeException e) {
      // Including CheckoutUnavailableException: a container whose self-provision did not complete
      // cannot answer, and "cannot answer" is not "signed in".
      return false;
    }
  }

  private String authDetail(AgentType harness) {
    try {
      if (authStatus == null) {
        return "Nobody has checked whether this harness is signed in.";
      }
      return authStatus.isLoggedIn(harness)
          ? "Signed in on the shared credential volume."
          : "Nobody has signed in on the shared credential volume. Open the "
              + harness.name()
              + " sign-in terminal to complete it once for every container on this volume.";
    } catch (CheckoutUnavailableException e) {
      // The checkout, not the credential, is what is missing — and saying "signed out" here would
      // send an operator to a login terminal for a fault that is a failed self-provision.
      return "Could not be checked: " + e.getMessage();
    } catch (RuntimeException e) {
      return "Could not be checked: " + e.getMessage();
    }
  }

  /**
   * Where a probe reads its credentials from — the same overlay a launch of that harness uses, so
   * the report describes the binary a session will actually run under rather than one reading a
   * different home.
   */
  private Map<String, String> probeEnvironment(AgentType harness) {
    if (claudeMount == null || claudeMount.isBlank()) {
      return Map.of();
    }
    return switch (harness) {
      case CLAUDE -> Map.of("HOME", claudeMount);
      case KIMI -> Map.of("KIMI_CODE_HOME", claudeMount + "/.kimi-code");
    };
  }
}
