package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.governance.TrustDocument;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The versions of one trust document of a project. Id: {@code ScopedId.of(orgId, "doc", ScopedId.safe(name))}. A version
 * is added and never changed, so the journal is the record of what was registered and when.
 */
@Component(id = "trust-document")
public class DocumentEntity extends EventSourcedEntity<TrustDocument.Document, DocumentEntity.Event> {

  public sealed interface Event {
    @TypeName("document-registered")
    record Registered(TrustDocument.Version version) implements Event {}
  }

  /** @param content optional; only its digest is kept */
  public record Add(String name, String kind, String ref, String asOf, String content, String by, long at) {}

  @Override
  public TrustDocument.Document emptyState() {
    return new TrustDocument.Document("", List.of());
  }

  public ReadOnlyEffect<TrustDocument.Document> get() {
    return effects().reply(currentState());
  }

  public Effect<TrustDocument.Document> add(Add command) {
    if (command.name() == null || command.name().isBlank() || command.name().length() > 80) return effects().error("a document needs a name of at most 80 characters");
    if (command.kind() == null || !TrustDocument.KINDS.contains(command.kind())) {
      return effects().error("kind must be one of " + TrustDocument.KINDS.stream().sorted().toList());
    }
    if (command.ref() == null || command.ref().isBlank()) return effects().error("say where the document is");
    try {
      if (LocalDate.parse(command.asOf()).isAfter(LocalDate.now().plusDays(1))) return effects().error("asOf cannot be in the future");
    } catch (DateTimeException | NullPointerException e) {
      return effects().error("asOf must be a date, yyyy-mm-dd");
    }
    var digest = "";
    if (command.content() != null && !command.content().isEmpty()) {
      if (command.content().getBytes(StandardCharsets.UTF_8).length > TrustDocument.MAX_CONTENT_BYTES) {
        return effects().error("content is at most " + TrustDocument.MAX_CONTENT_BYTES + " bytes");
      }
      digest = TrustDocument.digestOf(command.content());
    }
    var version = new TrustDocument.Version(currentState().versions().size() + 1, command.name().trim(), command.kind(),
        command.ref().trim(), command.asOf(), digest, command.by(), command.at());
    return effects().persist(new Event.Registered(version)).thenReply(s -> s);
  }

  @Override
  public TrustDocument.Document applyEvent(Event event) {
    return switch (event) {
      case Event.Registered e -> {
        var versions = new ArrayList<>(currentState().versions());
        versions.add(e.version());
        yield new TrustDocument.Document(e.version().name(), versions);
      }
    };
  }
}
