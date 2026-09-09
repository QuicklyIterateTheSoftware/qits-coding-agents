package eu.wohlben.qits.agents;

import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.util.regex.Pattern;

/**
 * The one grammar every id interpolated into an MCP server URL must pass.
 *
 * <p>It lives in the library rather than in each host's {@link AgentMcpServers} because the reason
 * for it is the library's: a scoped URL is embedded in a single-quoted launch argument and {@link
 * CodingAgent} does no escaping of its own, so nothing outside the slug alphabet may reach it. Two
 * hosts building URLs to two different services is exactly the situation in which one of them would
 * otherwise forget.
 */
public final class AgentMcpIds {

  /**
   * The platform's id grammar: the same slug the git host accepts for a repo id — alphanumerics and
   * dashes, no leading dash, bounded (qits-ci's {@code CiIdentifiers.REPO_ID}, mirrored). Repository
   * ids on this platform ARE directory-name slugs ({@code qits-stt}) — the join key everywhere — and
   * project ids are UUIDs, which this grammar accepts as a subset. Validating either as a strict
   * UUID rejected every real repository id (D2: 400 "Invalid repository id").
   */
  public static final Pattern PLATFORM_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");

  private AgentMcpIds() {}

  /**
   * Returns {@code value} if it is a platform id, else refuses the launch naming {@code label}. The
   * message shape is load-bearing: it is what the API reports as a 400 instead of "Internal error".
   */
  public static String requireId(String value, String label) {
    if (value == null || !PLATFORM_ID_PATTERN.matcher(value).matches()) {
      throw new InvalidCommandRequestException("Invalid " + label + ": " + value);
    }
    return value;
  }
}
