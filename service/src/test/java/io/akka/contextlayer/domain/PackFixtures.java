package io.akka.contextlayer.domain;

import java.util.List;

/** Builders for connector packs used across tests. */
public final class PackFixtures {

  private PackFixtures() {}

  public static PackDefinition validPack(String name) {
    return new PackDefinition(
        name,
        "1.0.0",
        new PackDefinition.Source("openapi", "https://claims.example.com/openapi.json"),
        "vault://integrations/" + name,
        List.of(new PackDefinition.EntityMapping(
            "Claim", "listClaims", "/items", "claimId", List.of("email"), List.of("status"),
            new PackDefinition.Freshness("CACHED", 15),
            new PackDefinition.Paging("cursor", 200, "limit", "cursor", "/next", null),
            new PackDefinition.Rate(60))),
        List.of(
            new PackDefinition.ActionDef("attachDocument", "attachDoc", RiskClass.NEEDS_APPROVAL),
            new PackDefinition.ActionDef("addNote", "addNote", RiskClass.UNATTENDED)));
  }
}
