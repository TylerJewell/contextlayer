package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.List;

/** The trust documents of a project, each as its newest version, for listing. */
@Component(id = "trust-document-view")
public class DocumentView extends View {

  public record Row(String orgId, String name, String kind, String ref, String asOf, String digest, String by, long at, int versions) {}

  public record Rows(List<Row> items) {}

  @Consume.FromEventSourcedEntity(DocumentEntity.class)
  public static class Updater extends TableUpdater<Row> {
    public Effect<Row> onEvent(DocumentEntity.Event event) {
      return switch (event) {
        case DocumentEntity.Event.Registered e -> {
          var v = e.version();
          var held = rowState();
          // The newest version is the one last current most recently, not the one registered last.
          boolean newer = held == null || v.asOf().compareTo(held.asOf()) >= 0;
          var org = updateContext().eventSubject().map(id -> id.substring(0, id.indexOf(':'))).orElse("");
          yield effects().updateRow(new Row(org, v.name(), newer ? v.kind() : held.kind(), newer ? v.ref() : held.ref(),
              newer ? v.asOf() : held.asOf(), newer ? v.digest() : held.digest(), newer ? v.by() : held.by(), newer ? v.at() : held.at(),
              v.version()));
        }
      };
    }
  }

  @Query("SELECT * AS items FROM documents WHERE orgId = :orgId")
  public QueryEffect<Rows> forOrganization(String orgId) {
    return queryResult();
  }
}
