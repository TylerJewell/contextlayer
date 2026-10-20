package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConnectorPackTest {

  private final PackDefinition definition = PackFixtures.validPack("claims");
  private final String hash = PackHash.of(definition);

  private ConnectorPack imported() {
    return ConnectorPack.empty()
        .apply(new ConnectorPackEvent.Imported("org", "claims", hash, definition, "alice"));
  }

  @Test
  void importingChangesNothingAboutWhatIsActive() {
    var pack = imported();
    assertThat(pack.mode()).isEqualTo(ConnectorPack.Mode.INACTIVE);
    assertThat(pack.active()).isEmpty();
    assertThat(pack.actionRisk("attachDocument")).isEmpty();
  }

  @Test
  void aVersionCannotBeActivatedByItsImporter() {
    assertThat(imported().validateActivate(hash, ConnectorPack.Mode.READ_ONLY, "alice")).isPresent();
    assertThat(imported().validateActivate(hash, ConnectorPack.Mode.READ_ONLY, "bob")).isEmpty();
  }

  @Test
  void activationNeedsAnExistingVersionAndARealMode() {
    assertThat(imported().validateActivate("nope", ConnectorPack.Mode.LIVE, "bob")).isPresent();
    assertThat(imported().validateActivate(hash, ConnectorPack.Mode.INACTIVE, "bob")).isPresent();
    assertThat(ConnectorPack.empty().validateActivate(hash, ConnectorPack.Mode.LIVE, "bob")).isPresent();
  }

  @Test
  void readOnlyExposesNoActionsToAssistants() {
    var pack = imported().apply(new ConnectorPackEvent.Activated(hash, ConnectorPack.Mode.READ_ONLY, "bob"));
    assertThat(pack.active()).isPresent();
    assertThat(pack.actionRisk("attachDocument")).isEmpty();
  }

  @Test
  void liveExposesDeclaredActionsWithTheirRiskAndNothingElse() {
    var pack = imported().apply(new ConnectorPackEvent.Activated(hash, ConnectorPack.Mode.LIVE, "bob"));
    assertThat(pack.actionRisk("attachDocument")).contains(RiskClass.NEEDS_APPROVAL);
    assertThat(pack.actionRisk("addNote")).contains(RiskClass.UNATTENDED);
    assertThat(pack.actionRisk("deleteEverything")).isEmpty();
  }

  @Test
  void deactivatingSwitchesActionsOffButKeepsTheVersionsForRollback() {
    var pack = imported()
        .apply(new ConnectorPackEvent.Activated(hash, ConnectorPack.Mode.LIVE, "bob"))
        .apply(new ConnectorPackEvent.Deactivated("bob"));
    assertThat(pack.actionRisk("addNote")).isEmpty();
    assertThat(pack.versions()).hasSize(1);
  }

  @Test
  void theNameInThePathMustMatchTheNameInThePack() {
    assertThat(ConnectorPack.empty().validateImport("other", definition, "alice"))
        .anyMatch(m -> m.contains("must match"));
    assertThat(ConnectorPack.empty().validateImport("claims", definition, "alice")).isEmpty();
  }

  @Test
  void importingTheSameContentTwiceKeepsOneVersion() {
    var twice = imported().apply(new ConnectorPackEvent.Imported("org", "claims", hash, definition, "alice"));
    assertThat(twice.versions()).hasSize(1);
  }
}
