package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.Integrity;

/**
 * Chains each evidence record as it is recorded. A record that arrives twice is chained once, and a
 * failure part way is retried until the record is in the chain and its link is written, so no record
 * is left out.
 */
@Component(id = "integrity-consumer")
@Consume.FromEventSourcedEntity(EvidenceEntity.class)
public class IntegrityConsumer extends Consumer {

  private final ComponentClient componentClient;

  public IntegrityConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onRecorded(EvidenceEntity.Event.Recorded recorded) {
    var evidence = recorded.evidence();
    var link = componentClient.forEventSourcedEntity(evidence.orgId()).method(IntegrityChainEntity::append)
        .invoke(new IntegrityChainEntity.Append(evidence.evidenceId(), Integrity.recordDigest(evidence), System.currentTimeMillis()));
    componentClient.forEventSourcedEntity(linkId(evidence.orgId(), link.seq())).method(ChainLinkEntity::write).invoke(link);
    return effects().done();
  }

  public static String linkId(String orgId, long seq) {
    return ScopedId.of(orgId, "link", String.valueOf(seq));
  }
}
