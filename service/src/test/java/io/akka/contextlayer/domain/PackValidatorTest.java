package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PackValidatorTest {

  private PackDefinition withCredential(String credentialRef) {
    var p = PackFixtures.validPack("claims");
    return new PackDefinition(p.name(), p.version(), p.source(), credentialRef, p.entities(), p.actions());
  }

  @Test
  void aCompletePackIsAccepted() {
    assertThat(PackValidator.validate(PackFixtures.validPack("claims"))).isEmpty();
  }

  @Test
  void aCredentialPastedIntoThePackIsRefused() {
    assertThat(PackValidator.validate(withCredential("sk-live-123456789")))
        .anyMatch(e -> e.contains("credentialRef"));
    assertThat(PackValidator.validate(withCredential(null))).anyMatch(e -> e.contains("credentialRef"));
    assertThat(PackValidator.validate(withCredential("vault://team/claims"))).isEmpty();
  }

  @Test
  void aSourceWithNoDeclaredRateIsRefusedBecauseItCouldBeCalledWithoutLimit() {
    var p = PackFixtures.validPack("claims");
    var e = p.entities().get(0);
    var unbounded = new PackDefinition.EntityMapping(e.type(), e.operation(), e.recordsPath(), e.idField(),
        e.identityAttributes(), e.filters(), e.freshness(), e.paging(), null);
    var pack = new PackDefinition(p.name(), p.version(), p.source(), p.credentialRef(), List.of(unbounded), p.actions());

    assertThat(PackValidator.validate(pack)).anyMatch(m -> m.contains("rate.callsPerMinute"));
  }

  @Test
  void aCachedEntityNeedsAPositiveTtl() {
    var p = PackFixtures.validPack("claims");
    var e = p.entities().get(0);
    var bad = new PackDefinition.EntityMapping(e.type(), e.operation(), e.recordsPath(), e.idField(), e.identityAttributes(),
        e.filters(), new PackDefinition.Freshness("CACHED", 0), e.paging(), e.rate());
    var pack = new PackDefinition(p.name(), p.version(), p.source(), p.credentialRef(), List.of(bad), p.actions());

    assertThat(PackValidator.validate(pack)).anyMatch(m -> m.contains("ttlMinutes"));
  }

  @Test
  void everyActionMustStateItsRiskClassSoThereIsNoPermissiveDefault() {
    var p = PackFixtures.validPack("claims");
    var pack = new PackDefinition(p.name(), p.version(), p.source(), p.credentialRef(), p.entities(),
        List.of(new PackDefinition.ActionDef("addNote", "addNote", null)));

    assertThat(PackValidator.validate(pack)).anyMatch(m -> m.contains("risk is required"));
  }

  @Test
  void duplicateNamesAreReported() {
    var p = PackFixtures.validPack("claims");
    var dup = new PackDefinition(p.name(), p.version(), p.source(), p.credentialRef(),
        List.of(p.entities().get(0), p.entities().get(0)),
        List.of(p.actions().get(0), p.actions().get(0)));

    assertThat(PackValidator.validate(dup)).anyMatch(m -> m.contains("entity Claim is declared twice"))
        .anyMatch(m -> m.contains("action attachDocument is declared twice"));
  }

  @Test
  void badNamesAndVersionsAreReportedTogether() {
    var p = PackFixtures.validPack("Claims_System");
    var pack = new PackDefinition(p.name(), "one", p.source(), p.credentialRef(), p.entities(), p.actions());

    assertThat(PackValidator.validate(pack)).hasSize(2);
  }

  private static PackDefinition streamed(String kind, String credential, String operation, int minutes) {
    return new PackDefinition("feed", "1.0.0", new PackDefinition.Source(kind, "akka-service://claims-system#entity_changes"), credential,
        List.of(new PackDefinition.EntityMapping("Claim", operation, null, "claimId", List.of(), List.of(),
            new PackDefinition.Freshness("STREAMED", minutes), null, null)),
        List.of());
  }

  @Test
  void aStreamedTypeWithNoOperationIsFedOnlyByPushAndNeedsNoPagingOrRate() {
    assertThat(PackValidator.validate(streamed("stream", null, "", 60))).isEmpty();
  }

  @Test
  void aStreamedTypeSaysHowLongASilenceMayLast() {
    assertThat(PackValidator.validate(streamed("stream", null, "", 0))).anyMatch(e -> e.contains("STREAMED") && e.contains("ttlMinutes"));
  }

  @Test
  void aStreamedTypeThatAlsoHasAnOperationIsHeldToTheRulesForReading() {
    assertThat(PackValidator.validate(streamed("stream", null, "listClaims", 60))).anyMatch(e -> e.contains("paging"));
  }

  @Test
  void onlyAStreamSourceMayOmitTheCredential() {
    assertThat(PackValidator.validate(streamed("openapi", null, "", 60))).anyMatch(e -> e.contains("credentialRef"));
  }

  @Test
  void aMaintainedTypeSaysHowOftenItIsRead() {
    var p = PackFixtures.validPack("claims");
    var e = p.entities().get(0);
    var maintained = new PackDefinition(p.name(), p.version(), p.source(), p.credentialRef(), List.of(new PackDefinition.EntityMapping(
        e.type(), e.operation(), e.recordsPath(), e.idField(), e.identityAttributes(), e.filters(),
        new PackDefinition.Freshness("MAINTAINED", 0), e.paging(), e.rate())), p.actions());

    assertThat(PackValidator.validate(maintained)).anyMatch(m -> m.contains("MAINTAINED") && m.contains("ttlMinutes"));
  }

  @Test
  void aMissingPackOrEmptyPackIsReportedNotThrown() {
    assertThat(PackValidator.validate(null)).containsExactly("pack is required");
    var p = PackFixtures.validPack("claims");
    var empty = new PackDefinition(p.name(), p.version(), p.source(), p.credentialRef(), null, null);
    assertThat(PackValidator.validate(empty)).anyMatch(m -> m.contains("at least one"));
  }

  @Test
  void theSameContentHasTheSameHashAndAnyEditChangesIt() {
    var a = PackFixtures.validPack("claims");
    var b = PackFixtures.validPack("claims");
    assertThat(PackHash.of(a)).isEqualTo(PackHash.of(b));

    var edited = new PackDefinition(a.name(), "1.0.1", a.source(), a.credentialRef(), a.entities(), a.actions());
    assertThat(PackHash.of(edited)).isNotEqualTo(PackHash.of(a));
  }

  private static PackDefinition withRelations(PackDefinition.Relation... relations) {
    var p = PackFixtures.validPack("claims");
    var e = p.entities().get(0);
    return new PackDefinition(p.name(), p.version(), p.source(), p.credentialRef(), List.of(new PackDefinition.EntityMapping(
        e.type(), e.operation(), e.recordsPath(), e.idField(), e.identityAttributes(), e.filters(), e.freshness(), e.paging(),
        e.rate(), List.of(relations))), p.actions());
  }

  @Test
  void aTypeCanDeclareRelationshipsToOtherTypesByTheFieldThatHoldsTheId() {
    assertThat(PackValidator.validate(withRelations(new PackDefinition.Relation("HAS_ACCOUNT", "accountId", "Account")))).isEmpty();
  }

  @Test
  void aRelationshipNeedsANameAFieldAndATargetTypeAndNoneIsDeclaredTwice() {
    var errors = PackValidator.validate(withRelations(
        new PackDefinition.Relation("HAS ACCOUNT", "accountId", "Account"),
        new PackDefinition.Relation("OWNER", "", "Account"),
        new PackDefinition.Relation("BRANCH", "branchId", "branch"),
        new PackDefinition.Relation("OWNER", "ownerId", "Person")));

    assertThat(errors).anyMatch(e -> e.contains("relationship name"));
    assertThat(errors).anyMatch(e -> e.contains("OWNER needs the field"));
    assertThat(errors).anyMatch(e -> e.contains("BRANCH needs a target type"));
    assertThat(errors).anyMatch(e -> e.contains("OWNER is declared twice"));
  }
}
