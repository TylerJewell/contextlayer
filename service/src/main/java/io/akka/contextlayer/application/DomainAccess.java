package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.auth.Caller;
import io.akka.contextlayer.auth.Role;
import io.akka.contextlayer.domain.DomainConfig;
import io.akka.contextlayer.domain.StructuredQuestion;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.EvaluationMode;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Decides what a caller may read, from the data domains of the project, and records the decision. */
public final class DomainAccess {

  private DomainAccess() {}

  public static DomainConfig config(ComponentClient componentClient, String project) {
    return componentClient.forEventSourcedEntity(project).method(DomainConfigEntity::get).invoke();
  }

  /** The entity types a question reads: its base type and each linked type. */
  public static Set<String> typesOf(StructuredQuestion question) {
    var types = new LinkedHashSet<String>();
    types.add(question.entityType());
    if (question.links() != null) question.links().forEach(l -> types.add(l.entityType()));
    return types;
  }

  public static Set<String> rolesOf(Caller caller) {
    return caller.roles().stream().map(Role::claimName).collect(Collectors.toSet());
  }

  /**
   * Checks and records: whether the caller is granted every domain read (control custom-cl0015) and, when more than
   * one domain is read, whether a join rule links them (custom-cl0032). A project with no data domains allows
   * everything and records nothing, because there is nothing yet for either control to decide.
   */
  public static DomainConfig.Access check(
      ComponentClient componentClient, String project, Caller caller, Collection<String> entityTypes) {
    return check(componentClient, project, config(componentClient, project), caller, entityTypes);
  }

  public static DomainConfig.Access check(
      ComponentClient componentClient, String project, DomainConfig config, Caller caller, Collection<String> entityTypes) {
    return check(componentClient, project, config, caller.subject(), rolesOf(caller), caller.authorityBasis(), entityTypes);
  }

  /** The same decision for someone who is not making a request now, such as the owner of a monitor that is running. */
  public static DomainConfig.Access check(
      ComponentClient componentClient, String project, DomainConfig config, String subject, Set<String> roles,
      String authority, Collection<String> entityTypes) {
    var access = config.check(subject, roles, entityTypes);
    if (config.governed()) {
      var recorder = new EvidenceRecorder(componentClient);
      var types = String.join(", ", entityTypes);
      var joinProblems = access.problems().stream().filter(DomainConfig::isJoinProblem).toList();
      var grantProblems = access.problems().stream().filter(p -> !DomainConfig.isJoinProblem(p)).toList();
      record(recorder, project, Controls.DOMAIN_ACCESS, grantProblems.isEmpty(),
          grantProblems.isEmpty() ? "granted" : "not-granted: " + String.join("; ", grantProblems), types, subject,
          authority);
      if (access.domains().size() > 1) {
        record(recorder, project, Controls.APPROVED_JOINS, joinProblems.isEmpty(),
            joinProblems.isEmpty() ? "join rule approved" : "no-join-rule: " + String.join("; ", joinProblems), types,
            subject, authority);
      }
    }
    return access;
  }

  private static void record(
      EvidenceRecorder recorder, String project, String control, boolean pass, String reason, String types,
      String subject, String authority) {
    recorder.record(new EvidenceRecorder.Fact(project, control, Verdicts.once(subject), EvaluationMode.ENFORCING,
        pass ? "PASS" : "BLOCK", pass ? "CONTINUED" : "BLOCKED", reason, types, subject, authority, "", false, "", 0));
  }
}
