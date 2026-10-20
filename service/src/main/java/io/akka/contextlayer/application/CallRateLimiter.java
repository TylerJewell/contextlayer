package io.akka.contextlayer.application;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A sliding one-minute window per key. This limiter lives in one service instance, so with
 * several instances each holds its own window; the declared rate is therefore a ceiling per
 * instance until the limiter is moved into a shared entity.
 */
public class CallRateLimiter {

  private static final long WINDOW_MILLIS = 60_000;
  private static final long POLL_MILLIS = 25;

  private final LongSupplier nowMillis;
  private final Map<String, Deque<Long>> calls = new HashMap<>();

  public CallRateLimiter() {
    this(System::currentTimeMillis);
  }

  public CallRateLimiter(LongSupplier nowMillis) {
    this.nowMillis = nowMillis;
  }

  public synchronized boolean tryAcquire(String key, int callsPerMinute) {
    long now = nowMillis.getAsLong();
    var window = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
    while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) window.pollFirst();
    if (window.size() >= callsPerMinute) return false;
    window.addLast(now);
    return true;
  }

  /** Waits up to {@code maxWaitMillis} for a free slot; returns false if none opened. */
  public boolean acquire(String key, int callsPerMinute, long maxWaitMillis) {
    long deadline = nowMillis.getAsLong() + maxWaitMillis;
    while (true) {
      if (tryAcquire(key, callsPerMinute)) return true;
      if (nowMillis.getAsLong() >= deadline) return false;
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }
}
