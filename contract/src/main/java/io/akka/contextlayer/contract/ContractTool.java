package io.akka.contextlayer.contract;

import io.akka.nexus.governance.contract.Gcc;
import io.akka.nexus.governance.contract.attest.ProvenanceAttestation;
import io.akka.nexus.governance.contract.config.GccConfiguration;
import io.akka.nexus.governance.contract.config.RegistryConfig;
import io.akka.nexus.governance.contract.model.DerivationPart;
import io.akka.nexus.governance.contract.model.SectionRef;
import io.akka.nexus.governance.contract.model.SignerRole;
import io.akka.nexus.governance.contract.publish.Publisher;
import io.akka.nexus.governance.contract.signing.ManagedKeySigner;
import io.akka.nexus.governance.contract.verify.TrustPolicy;
import io.akka.nexus.governance.contract.verify.VerificationResult;
import io.akka.nexus.governance.contract.verify.VerificationStage;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Signs the control catalog as a Governance Control Contract into an OCI layout, and verifies one.
 *
 * <pre>
 *   keys   --keys DIR --trust DIR --risk-team NAME
 *   sign   --controls FILE --layout DIR --keys DIR --trust DIR [--commit SHA] [--tsa URL]
 *   verify --layout DIR --trust DIR
 * </pre>
 *
 * Each signing makes the next revision, superseding the one tagged {@code current}, and tags the new one
 * {@code v<revision>} and {@code current}. The library signs only with a source of time: {@code --tsa} names an
 * independent RFC 3161 authority, and without it the {@link LocalTimestampAuthority} made by {@code keys} dates the
 * signature with the signer's own clock.
 */
public final class ContractTool {

  static final String CURRENT = "current";
  /** A layout holds one system's contracts, so the repository only names them in what the library records. */
  static final String REPOSITORY = "contextlayer";
  private static final String PUBLISHER_HINT = "contextlayer-publisher";
  /** The matrix of corpus controls is accepted by the risk team. */
  static final Map<SectionRef, SignerRole> REQUIRED = Map.of(SectionRef.MATRIX, SignerRole.RISK_TEAM);

  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      System.err.println("usage: keys | sign | verify (see the class comment)");
      System.exit(2);
    }
    var flags = flags(args);
    int status = switch (args[0]) {
      case "keys" -> {
        Keys.generate(Path.of(flags.get("keys")), Path.of(flags.get("trust")), flags.getOrDefault("risk-team", "risk-team"));
        System.out.println("Keys written. Keep " + flags.get("keys") + " out of the repository; " + flags.get("trust")
            + " is what a verifier trusts.");
        yield 0;
      }
      case "sign" -> {
        var signed = sign(Path.of(flags.get("controls")), Path.of(flags.get("layout")), Path.of(flags.get("keys")),
            Path.of(flags.get("trust")), flags.getOrDefault("commit", "uncommitted"), Optional.ofNullable(flags.get("tsa")).map(URI::create));
        System.out.println("Signed revision " + signed.revision() + " of " + signed.systemId() + ": " + signed.digest());
        yield 0;
      }
      case "verify" -> {
        var result = verify(Path.of(flags.get("layout")), Path.of(flags.get("trust")));
        report(result, System.out);
        yield result.verified() ? 0 : 1;
      }
      default -> {
        System.err.println("unknown command " + args[0]);
        yield 2;
      }
    };
    System.exit(status);
  }

  public record Signed(String systemId, long revision, String digest) {}

  public static Signed sign(Path controlsYaml, Path layout, Path keys, Path trust, String commit, Optional<URI> tsa) throws IOException {
    var controls = ControlsContract.read(controlsYaml);
    var registry = new LayoutRegistry(layout);
    var config = GccConfiguration.builder(RegistryConfig.of("oci-layout", "contextlayer"),
        ManagedKeySigner.ecdsaP256(Keys.privateKey(keys.resolve(Keys.PUBLISHER_KEY)), PUBLISHER_HINT));
    if (tsa.isPresent()) {
      config.timestampAuthority(tsa.get());
    } else {
      config.timestampSource(new LocalTimestampAuthority(Keys.privateKey(keys.resolve(Keys.TSA_KEY)),
          Keys.certificate(trust.resolve(Keys.TSA_CERT))));
    }
    var gcc = Gcc.create(config.build(), registry);
    var repository = REPOSITORY;

    var previous = registry.resolveTag(repository, CURRENT).map(d -> gcc.reader().byTag(repository, CURRENT).contract());
    long revision = previous.map(p -> p.revision().value() + 1).orElse(1L);
    var builder = gcc.builder()
        .metadata(controls.metadata(revision, Instant.now()))
        .matrix(controls.rows())
        .enterprise(List.of())
        .derivation(DerivationPart.empty());
    previous.ifPresent(builder::supersedes);
    var built = builder.build();

    var signOff = gcc.signOff(built.contract(), SectionRef.MATRIX, SignerRole.RISK_TEAM,
        Keys.privateKey(keys.resolve(Keys.RISK_KEY)), List.of(Keys.certificate(trust.resolve(Keys.RISK_CERT))));
    var contract = built.contract().withSignOff(signOff);
    var assembled = gcc.builder().reassemble(contract);

    var provenance = new ProvenanceAttestation(ControlsContract.NO_SURVEY.value(), controls.corpusVersion(), revision,
        "contextlayer-contract", commit);
    var published = gcc.publisher().publish(repository, contract, assembled,
        Publisher.Options.tagged("v" + revision, CURRENT).withProvenance(provenance));
    return new Signed(controls.systemId(), revision, published.digest().value());
  }

  public static VerificationResult verify(Path layout, Path trust) throws IOException {
    var registry = new LayoutRegistry(layout);
    var gcc = Gcc.create(GccConfiguration.builder(RegistryConfig.of("oci-layout", "contextlayer"),
        ManagedKeySigner.ecdsaP256(Keys.newKeyPair().getPrivate(), "verify-only")).build(), registry);
    var policy = TrustPolicy.builder("contextlayer")
        .trustKey(Keys.publicKey(trust.resolve(Keys.PUBLISHER_PUBLIC)))
        .trustKey(Keys.certificate(trust.resolve(Keys.RISK_CERT)).getPublicKey())
        .build();
    var fetched = gcc.reader().byTag(REPOSITORY, CURRENT);
    return gcc.verifier().verify(REPOSITORY, fetched, policy, REQUIRED);
  }

  static void report(VerificationResult result, PrintStream out) {
    for (var stage : VerificationStage.values()) {
      out.printf("%-10s %s  %s%n", stage, result.stage(stage).passed() ? "pass" : "FAIL", result.stage(stage).describe());
    }
    out.println(result.verified() ? "Verified." : "Not verified: " + result.describe());
    out.println("The timestamp stage checks that a time is attached, not who attached it. A contract signed without --tsa"
        + " is dated by the signer's own authority.");
  }

  private static Map<String, String> flags(String[] args) {
    var flags = new HashMap<String, String>();
    for (int i = 1; i + 1 < args.length; i += 2) {
      if (!args[i].startsWith("--")) throw new IllegalArgumentException("expected a --flag, got " + args[i]);
      flags.put(args[i].substring(2), args[i + 1]);
    }
    return flags;
  }
}
