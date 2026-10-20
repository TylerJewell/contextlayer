package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.contextlayer.domain.ScopedId;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What happens when a project's data changes: watchers connected right now are told, and each
 * monitor that runs on change and reads the changed type is scheduled to run.
 *
 * <p>A monitor run is scheduled for the next {@link #WINDOW_MILLIS} boundary, under a timer name
 * that includes the boundary. Every change inside a window therefore schedules the same timer, so a
 * burst of changes gives one run that starts after the last of them, never a run per change and
 * never a run that misses one.
 */
public final class ChangeFeed {

  static final long WINDOW_MILLIS = 5_000;
  /** How long the list of a project's monitors is reused, so a stream of changes does not query it each time. */
  private static final long MONITORS_CACHED_MILLIS = 3_000;

  private record Cached(long at, List<MonitorListView.MonitorRow> rows) {}

  private static final ConcurrentHashMap<String, Cached> MONITORS = new ConcurrentHashMap<>();

  private ChangeFeed() {}

  public static void changed(
      ComponentClient componentClient, TimerScheduler timers, String orgId, ChangeFeedEntity.ChangeNotice notice) {
    componentClient.forKeyValueEntity(orgId).method(ChangeFeedEntity::publish).invoke(notice);
    long now = System.currentTimeMillis();
    for (var monitor : monitors(componentClient, orgId, now)) {
      if (!monitor.enabled() || !monitor.onChange() || !monitor.entityTypes().contains(notice.entityType())) continue;
      long boundary = (now / WINDOW_MILLIS + 1) * WINDOW_MILLIS;
      timers.createSingleTimer(
          "monitor-change-" + ScopedId.of(orgId, monitor.monitorId()) + "-" + boundary,
          Duration.ofMillis(boundary - now),
          componentClient.forTimedAction().method(MonitorTimedAction::changed)
              .deferred(new MonitorTimedAction.Tick(orgId, monitor.monitorId(), boundary)));
    }
  }

  private static List<MonitorListView.MonitorRow> monitors(ComponentClient componentClient, String orgId, long now) {
    var cached = MONITORS.get(orgId);
    if (cached != null && now - cached.at() < MONITORS_CACHED_MILLIS) return cached.rows();
    var rows = componentClient.forView().method(MonitorListView::forOrganization).invoke(orgId).items();
    MONITORS.put(orgId, new Cached(now, rows));
    return rows;
  }
}
