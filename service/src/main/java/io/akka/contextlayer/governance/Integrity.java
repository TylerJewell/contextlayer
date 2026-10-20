package io.akka.contextlayer.governance;

import akka.javasdk.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * Makes the evidence ledger tamper-evident. Each evidence record has a digest of its content, and
 * the digests are chained in the order they were taken in: a link's chain digest covers the
 * previous link's, so changing, removing or reordering any record changes every digest after it.
 *
 * <p>This detects a record altered after it was written, and a link missing or altered in the
 * chain. It does not detect someone who can rewrite the whole chain and its head together. Protecting
 * against that means holding the head somewhere that person cannot write, which is why it is
 * published ({@code /governance/integrity}) for an outside party to keep.
 */
public final class Integrity {
  private Integrity() {}

  /** The scheme names are those of the runtime evidence vocabulary. */
  public static final String HASH_CHAINED = "HashChained";
  public static final String NO_INTEGRITY = "NoIntegrity";

  /**
   * One place in the chain.
   *
   * @param seq 1 for the first link
   * @param previous the chain digest of link {@code seq - 1}, empty for the first
   * @param recordDigest the digest of the evidence record named by {@code recordId}
   */
  public record Link(long seq, String recordId, String recordDigest, String previous, String chainDigest, long at) {}

  /** Digest of an evidence record's content. Equal content gives an equal digest. */
  public static String recordDigest(Evidence evidence) {
    return CanonicalJson.sha256(JsonSupport.getObjectMapper().valueToTree(evidence));
  }

  public static String chainDigest(String previous, long seq, String recordId, String recordDigest) {
    return sha256(previous + "|" + seq + "|" + recordId + "|" + recordDigest);
  }

  public static Link next(String head, long count, String recordId, String recordDigest, long at) {
    long seq = count + 1;
    return new Link(seq, recordId, recordDigest, head, chainDigest(head, seq, recordId, recordDigest), at);
  }

  /** What checking a stretch of the chain found. */
  public record Result(long checked, long lastSeq, String lastChainDigest, List<String> problems) {
    public boolean intact() {
      return problems.isEmpty();
    }
  }

  /**
   * Checks links {@code from} to {@code from + limit - 1}, or fewer if the chain ends. A stretch that does
   * not start at 1 is checked against the stored chain digest of the link before it, so it proves the
   * stretch is consistent with that link and not that nothing before it was changed.
   *
   * @param linkAt the link at a position, or empty when it is missing
   * @param evidenceAt the evidence record named by a link, or empty when it is missing
   */
  public static Result check(
      long from, long limit, long count, LongFunction<Optional<Link>> linkAt, Function<String, Optional<Evidence>> evidenceAt) {
    var problems = new ArrayList<String>();
    String previous = "";
    if (from > 1) {
      var before = linkAt.apply(from - 1);
      if (before.isEmpty()) {
        return new Result(0, from - 1, "", List.of("link " + (from - 1) + " is missing, so the chain cannot be checked from " + from));
      }
      previous = before.get().chainDigest();
    }
    long checked = 0;
    long last = from - 1;
    String lastDigest = previous;
    long end = Math.min(count, from + limit - 1);
    for (long seq = from; seq <= end; seq++) {
      var link = linkAt.apply(seq);
      if (link.isEmpty()) {
        problems.add("link " + seq + " is missing");
        break;
      }
      var l = link.get();
      if (l.seq() != seq) problems.add("link " + seq + " says it is link " + l.seq());
      if (!l.previous().equals(previous)) problems.add("link " + seq + " does not follow link " + (seq - 1));
      if (!l.chainDigest().equals(chainDigest(l.previous(), l.seq(), l.recordId(), l.recordDigest()))) {
        problems.add("link " + seq + " was altered: its digest does not match its content");
      }
      var evidence = evidenceAt.apply(l.recordId());
      if (evidence.isEmpty()) {
        problems.add("record " + l.recordId() + " (link " + seq + ") is missing");
      } else if (!recordDigest(evidence.get()).equals(l.recordDigest())) {
        problems.add("record " + l.recordId() + " (link " + seq + ") was altered after it was chained");
      }
      previous = l.chainDigest();
      checked++;
      last = seq;
      lastDigest = l.chainDigest();
    }
    return new Result(checked, last, lastDigest, List.copyOf(problems));
  }

  static String sha256(String text) {
    try {
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
