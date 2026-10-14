package eu.wohlben.qits.agents;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The platform's project slugs, for {@link AgentDefaults#workLinkProjects()}: every project on the
 * estate, so a session's footer links the keys of projects other than the one its container was
 * created for. A host holds one for its lifetime and answers {@link #slugs()} — usually unioned with
 * the default's own entity-derived slug — from {@code workLinkProjects()}.
 *
 * <p><b>A launch never waits on this.</b> {@link #slugs()} answers what is cached — empty until the
 * first fetch succeeds — and, when that is older than {@link #TTL} or absent, starts one
 * asynchronous refresh in the background. The footer links are a convenience, and a launch that
 * stalled on the projects service, or failed because it was down, would trade a working REPL for a
 * missing link. For the same reason every failure — no domain, no token, a non-2xx answer, a body
 * that does not parse, a timeout — is swallowed and leaves the previous answer in place; a failed
 * refresh is retried no sooner than {@link #RETRY_AFTER_FAILURE}, so a service that is down is not
 * asked again on every launch.
 *
 * <p>Nothing is fetched at construction. The first {@link #slugs()} call starts the first fetch, so
 * the first launch after boot usually carries no cross-project links; a host that wants them there
 * calls {@link #start()} at boot.
 *
 * <p>The endpoint is {@code https://projects.qits.<domain>/projects/api/projects}, answered with
 * {@code {"entries":[{"project":{"slug":"…",…}},…]}}, read with the host's own bearer. The domain and
 * the token are the host's, so nothing here knows which product it is inside.
 */
public final class WorkLinkProjects {

  /** How long a successful answer is served before a refresh is started. */
  public static final Duration TTL = Duration.ofHours(1);

  /** How long after a failed refresh the next one may start. */
  static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

  /** Connect and request timeout of the default fetch. */
  static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static final Logger LOG = System.getLogger(WorkLinkProjects.class.getName());

  private final String domain;
  private final Supplier<Optional<String>> bearerToken;
  private final BiFunction<URI, String, CompletableFuture<String>> fetch;
  private final Clock clock;
  private final AtomicBoolean inFlight = new AtomicBoolean();

  private volatile List<String> cached = List.of();

  /** When the next refresh may start; null means now. */
  private volatile Instant refreshDue;

  /**
   * @param domain the platform domain ({@code QITS_DOMAIN}); null or blank and nothing is ever
   *     fetched
   * @param bearerToken the host's current bearer, asked for on every fetch so an expired one is
   *     never reused; empty and that fetch is skipped
   */
  public WorkLinkProjects(String domain, Supplier<Optional<String>> bearerToken) {
    this(domain, bearerToken, httpFetch(), Clock.systemUTC());
  }

  /**
   * @param fetch GETs the URI with the bearer and completes with the body of a 2xx answer, or
   *     exceptionally for anything else
   */
  WorkLinkProjects(
      String domain,
      Supplier<Optional<String>> bearerToken,
      BiFunction<URI, String, CompletableFuture<String>> fetch,
      Clock clock) {
    this.domain = domain == null || domain.isBlank() ? null : domain.trim();
    this.bearerToken = bearerToken;
    this.fetch = fetch;
    this.clock = clock;
  }

  /** Starts the first fetch now rather than at the first {@link #slugs()}; returns this. */
  public WorkLinkProjects start() {
    slugs();
    return this;
  }

  /**
   * The cached project slugs, sorted and distinct — empty before the first successful fetch, and
   * always empty without a domain. Never blocks and never throws; starts a background refresh when
   * the answer is due one.
   */
  public List<String> slugs() {
    if (domain == null) {
      return List.of();
    }
    Instant due = refreshDue;
    if ((due == null || !clock.instant().isBefore(due)) && inFlight.compareAndSet(false, true)) {
      refresh();
    }
    return cached;
  }

  private void refresh() {
    try {
      Optional<String> token = bearerToken == null ? Optional.empty() : bearerToken.get();
      if (token == null || token.isEmpty() || token.get().isBlank()) {
        failed(null);
        return;
      }
      URI uri = URI.create("https://projects.qits." + domain + "/projects/api/projects");
      fetch
          .apply(uri, token.get())
          .whenComplete(
              (body, error) -> {
                if (error != null) {
                  failed(error);
                  return;
                }
                try {
                  cached = parse(body);
                  refreshDue = clock.instant().plus(TTL);
                  inFlight.set(false);
                } catch (RuntimeException e) {
                  failed(e);
                }
              });
    } catch (RuntimeException e) {
      failed(e);
    }
  }

  private void failed(Throwable error) {
    if (error != null) {
      LOG.log(Level.DEBUG, "Refreshing the work-link projects failed; keeping the last answer", error);
    }
    refreshDue = clock.instant().plus(RETRY_AFTER_FAILURE);
    inFlight.set(false);
  }

  /** The non-blank {@code entries[].project.slug} of a projects listing, sorted and distinct. */
  static List<String> parse(String body) {
    JsonArray entries = new JsonObject(body).getJsonArray("entries");
    TreeSet<String> slugs = new TreeSet<>();
    if (entries != null) {
      for (Object entry : entries) {
        if (entry instanceof JsonObject object
            && object.getValue("project") instanceof JsonObject project
            && project.getValue("slug") instanceof String slug
            && !slug.isBlank()) {
          slugs.add(slug.trim());
        }
      }
    }
    return List.copyOf(slugs);
  }

  private static BiFunction<URI, String, CompletableFuture<String>> httpFetch() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    return (uri, token) ->
        client
            .sendAsync(
                HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .thenApply(
                response -> {
                  if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException(
                        "GET " + uri + " answered " + response.statusCode());
                  }
                  return response.body();
                });
  }
}
