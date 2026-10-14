package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** The platform's project slugs, cached and refreshed in the background (qits-1204). */
class WorkLinkProjectsTest {

  private static final String LISTING =
      "{\"entries\":["
          + "{\"project\":{\"id\":\"1\",\"slug\":\"qits\"}},"
          + "{\"project\":{\"id\":\"2\",\"slug\":\"acme\"}},"
          + "{\"project\":{\"id\":\"3\",\"slug\":\" \"}},"
          + "{\"project\":{\"id\":\"4\"}},"
          + "{\"project\":{\"id\":\"5\",\"slug\":\"qits\"}},"
          + "{\"other\":true}]}";

  /** A clock the test moves by hand. */
  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-14T10:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Records each fetch and answers with whatever {@link #next} holds. */
  private static final class Fetch implements BiFunction<URI, String, CompletableFuture<String>> {
    final List<String> calls = new ArrayList<>();
    CompletableFuture<String> next = CompletableFuture.completedFuture(LISTING);

    @Override
    public CompletableFuture<String> apply(URI uri, String token) {
      calls.add(uri + " " + token);
      return next;
    }
  }

  private final MutableClock clock = new MutableClock();
  private final Fetch fetch = new Fetch();

  private WorkLinkProjects projects(String domain, Optional<String> token) {
    return new WorkLinkProjects(domain, () -> token, fetch, clock);
  }

  @Test
  void theListingIsReadIntoSortedDistinctSlugs() {
    assertEquals(List.of("acme", "qits"), WorkLinkProjects.parse(LISTING));
    assertEquals(List.of(), WorkLinkProjects.parse("{}"));
  }

  @Test
  void theFirstCallFetchesTheListingWithTheBearer() {
    WorkLinkProjects projects = projects("example.test", Optional.of("t0k"));

    // The injected fetch completes synchronously, so the first call already sees the answer.
    assertEquals(List.of("acme", "qits"), projects.slugs());
    assertEquals(
        List.of("https://projects.qits.example.test/projects/api/projects t0k"), fetch.calls);
  }

  @Test
  void aSlowFetchNeverBlocksAndIsNotStartedTwice() {
    CompletableFuture<String> pending = new CompletableFuture<>();
    fetch.next = pending;
    WorkLinkProjects projects = projects("example.test", Optional.of("t0k"));

    assertEquals(List.of(), projects.slugs());
    assertEquals(List.of(), projects.slugs());
    assertEquals(1, fetch.calls.size(), "one refresh in flight at a time");

    pending.complete(LISTING);
    assertEquals(List.of("acme", "qits"), projects.slugs());
  }

  @Test
  void theAnswerIsCachedForTheHourAndRefreshedAfterIt() {
    WorkLinkProjects projects = projects("example.test", Optional.of("t0k"));
    projects.slugs();

    clock.now = clock.now.plus(Duration.ofMinutes(59));
    projects.slugs();
    assertEquals(1, fetch.calls.size());

    fetch.next = CompletableFuture.completedFuture("{\"entries\":[{\"project\":{\"slug\":\"new\"}}]}");
    clock.now = clock.now.plus(Duration.ofMinutes(1));
    assertEquals(List.of("new"), projects.slugs());
    assertEquals(2, fetch.calls.size());
  }

  @Test
  void aFailedRefreshKeepsTheLastAnswer() {
    WorkLinkProjects projects = projects("example.test", Optional.of("t0k"));
    projects.slugs();
    clock.now = clock.now.plus(WorkLinkProjects.TTL);

    fetch.next = CompletableFuture.failedFuture(new IllegalStateException("answered 503"));
    assertEquals(List.of("acme", "qits"), projects.slugs());
    fetch.next = CompletableFuture.completedFuture("not json");
    clock.now = clock.now.plus(WorkLinkProjects.RETRY_AFTER_FAILURE);
    assertEquals(List.of("acme", "qits"), projects.slugs());
    assertEquals(3, fetch.calls.size());
  }

  @Test
  void aFailureIsNotRetriedOnEveryCall() {
    fetch.next = CompletableFuture.failedFuture(new IllegalStateException("down"));
    WorkLinkProjects projects = projects("example.test", Optional.of("t0k"));

    assertEquals(List.of(), projects.slugs());
    assertEquals(List.of(), projects.slugs());
    assertEquals(1, fetch.calls.size());
  }

  @Test
  void aFetchThatThrowsIsSwallowed() {
    WorkLinkProjects projects =
        new WorkLinkProjects(
            "example.test",
            () -> Optional.of("t0k"),
            (uri, token) -> {
              throw new IllegalStateException("boom");
            },
            clock);

    assertEquals(List.of(), projects.slugs());
  }

  @Test
  void noDomainAnswersNothingWithoutFetching() {
    assertEquals(List.of(), projects(null, Optional.of("t0k")).slugs());
    assertEquals(List.of(), projects("  ", Optional.of("t0k")).start().slugs());
    assertEquals(List.of(), fetch.calls);
  }

  @Test
  void noTokenAnswersNothingWithoutFetching() {
    assertEquals(List.of(), projects("example.test", Optional.empty()).slugs());
    assertEquals(List.of(), fetch.calls);
  }
}
