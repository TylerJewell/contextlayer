package io.akka.contextlayer.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.application.AssistantListView;
import io.akka.contextlayer.application.ConnectorPackEntity;
import io.akka.contextlayer.application.ConnectorPackView;
import io.akka.contextlayer.application.RefreshStatusEntity;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.ProjectResolver;
import io.akka.contextlayer.domain.ConnectorPack;
import io.akka.contextlayer.domain.RefreshStatus;
import io.akka.contextlayer.domain.ScopedId;
import java.util.ArrayList;
import java.util.List;

/** Lists for screens that show everything an organization has: connectors with their health, and assistants. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN)
@HttpEndpoint("/v1/context")
public class OverviewEndpoint extends PrincipalEndpoint {

  public record ConnectorEntityStatus(
      String type,
      String policy,
      int ttlMinutes,
      String lastOutcome,
      long lastRefreshedAt,
      int stored,
      List<String> warnings) {}

  public record ConnectorAction(String name, String risk) {}

  public record ConnectorSummary(
      String name,
      String mode,
      String activeHash,
      String activeVersion,
      String importedBy,
      List<ConnectorEntityStatus> entities,
      List<ConnectorAction> actions) {}

  public record ConnectorList(List<ConnectorSummary> items) {}

  public record AssistantSummary(
      String assistantId, String purpose, String owner, String stage, int releaseCount, int approvedCount) {}

  public record AssistantList(List<AssistantSummary> items) {}

  private final ComponentClient componentClient;

  public OverviewEndpoint(ComponentClient componentClient, Authz authz, ProjectResolver projects) {
    super(authz, projects, componentClient);
    this.componentClient = componentClient;
  }

  /** Each connector with the state of its last read of every entity type it provides. */
  @Get("/connectors")
  public ConnectorList connectors() {
    authorize(Permission.READ);
    var rows = componentClient.forView().method(ConnectorPackView::forOrganization).invoke(project());
    var items = new ArrayList<ConnectorSummary>();
    for (var row : rows.items()) {
      var pack = componentClient.forEventSourcedEntity(ScopedId.of(project(), row.name()))
          .method(ConnectorPackEntity::get).invoke();
      if (pack.exists()) items.add(summary(project(), pack));
    }
    items.sort((a, b) -> a.name().compareTo(b.name()));
    return new ConnectorList(items);
  }

  @Get("/assistants")
  public AssistantList assistants() {
    authorize(Permission.READ);
    var rows = componentClient.forView().method(AssistantListView::forOrganization).invoke(project());
    var items = rows.items().stream()
        .map(r -> new AssistantSummary(r.assistantId(), r.purpose(), r.owner(), r.stage(), r.releaseCount(),
            r.approvedCount()))
        .sorted((a, b) -> a.assistantId().compareTo(b.assistantId()))
        .toList();
    return new AssistantList(items);
  }

  private ConnectorSummary summary(String orgId, ConnectorPack pack) {
    var active = pack.active();
    var definition = active.map(ConnectorPack.Version::definition).orElse(null);
    var importedBy = active.map(ConnectorPack.Version::importedBy)
        .orElse(pack.versions().isEmpty() ? "" : pack.versions().get(pack.versions().size() - 1).importedBy());

    var entities = new ArrayList<ConnectorEntityStatus>();
    var actions = new ArrayList<ConnectorAction>();
    if (definition != null) {
      if (definition.entities() != null) {
        for (var e : definition.entities()) {
          var status = componentClient.forKeyValueEntity(ScopedId.of(orgId, pack.name(), e.type()))
              .method(RefreshStatusEntity::get).invoke();
          entities.add(new ConnectorEntityStatus(e.type(), e.freshness().policy(), e.freshness().ttlMinutes(),
              status.exists() ? status.outcome().name() : RefreshStatus.Outcome.NEVER.name(),
              status.refreshedAt(), status.stored(), status.warnings()));
        }
      }
      if (definition.actions() != null) {
        definition.actions().forEach(a -> actions.add(new ConnectorAction(a.name(), a.risk().name())));
      }
    }
    return new ConnectorSummary(pack.name(), pack.mode().name(), pack.activeHash(),
        definition == null ? "" : definition.version(), importedBy, entities, actions);
  }
}
