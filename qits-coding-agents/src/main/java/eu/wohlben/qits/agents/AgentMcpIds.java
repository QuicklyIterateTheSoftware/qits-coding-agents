package eu.wohlben.qits.agents;

import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.util.regex.Pattern;

/**
 * The grammar every id put into an MCP server url had to pass.
 *
 * @deprecated since qits-1174. No platform server url carries an id any more: {@code qits} is scoped
 *     by the caller's bearer. Kept only so hosts built against the older library still compile.
 */
@Deprecated
public final class AgentMcpIds {

  /** Alphanumerics and dashes, no leading dash, at most 64 characters. */
  public static final Pattern PLATFORM_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");

  private AgentMcpIds() {}

  /** Returns {@code value} if it is a platform id, else refuses the launch naming {@code label}. */
  public static String requireId(String value, String label) {
    if (value == null || !PLATFORM_ID_PATTERN.matcher(value).matches()) {
      throw new InvalidCommandRequestException("Invalid " + label + ": " + value);
    }
    return value;
  }
}
