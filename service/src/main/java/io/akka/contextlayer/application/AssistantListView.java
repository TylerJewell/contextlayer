package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.contextlayer.domain.AssistantEvent;
import io.akka.contextlayer.domain.AssistantStage;
import java.util.List;

/** The assistants of an organization with their stage and release counts, for lists. */
@Component(id = "assistant-list-view")
public class AssistantListView extends View {

  public record AssistantRow(
      String orgId, String assistantId, String purpose, String owner, String stage, int releaseCount, int approvedCount) {

    AssistantRow withReleases(int releases, int approved) {
      return new AssistantRow(orgId, assistantId, purpose, owner, stage, releases, approved);
    }

    AssistantRow withStage(AssistantStage next) {
      return new AssistantRow(orgId, assistantId, purpose, owner, next.name(), releaseCount, approvedCount);
    }
  }

  public record AssistantRows(List<AssistantRow> items) {}

  @Consume.FromEventSourcedEntity(AssistantEntity.class)
  public static class Updater extends TableUpdater<AssistantRow> {
    public Effect<AssistantRow> onEvent(AssistantEvent event) {
      return switch (event) {
        case AssistantEvent.Created e -> effects().updateRow(
            new AssistantRow(e.orgId(), e.assistantId(), e.purpose(), e.owner(), AssistantStage.DRAFT.name(), 0, 0));
        case AssistantEvent.ReleaseAdded e ->
            effects().updateRow(rowState().withReleases(rowState().releaseCount() + 1, rowState().approvedCount()));
        case AssistantEvent.ReleaseApproved e ->
            effects().updateRow(rowState().withReleases(rowState().releaseCount(), rowState().approvedCount() + 1));
        case AssistantEvent.StageChanged e -> effects().updateRow(rowState().withStage(e.stage()));
      };
    }
  }

  @Query("SELECT * AS items FROM assistants WHERE orgId = :orgId")
  public QueryEffect<AssistantRows> forOrganization(String orgId) {
    return queryResult();
  }
}
