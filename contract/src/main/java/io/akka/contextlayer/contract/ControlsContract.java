package io.akka.contextlayer.contract;

import io.akka.nexus.governance.contract.model.ContractRevision;
import io.akka.nexus.governance.contract.model.ContractRow;
import io.akka.nexus.governance.contract.model.GccDigest;
import io.akka.nexus.governance.contract.model.GccRootMetadata;
import io.akka.nexus.governance.contract.format.FormatVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The service's control catalog ({@code governance/controls.yaml}) read as the parts of a Governance Control Contract.
 * Every row names the corpus control it implements, so every row is a corpus row of the matrix, pinned to the corpus
 * version the catalog declares. The payload of a row is the catalog row as written, less the two fields the contract
 * envelope carries ({@code row_id} as {@code id}, and {@code corpus_ref}).
 */
public record ControlsContract(String systemId, String corpusVersion, List<ContractRow> rows) {

  /**
   * No survey derived this catalog: its rows were chosen by hand. The contract still needs a survey digest, so it
   * carries the digest of the empty answer set, which says exactly that.
   */
  public static final GccDigest NO_SURVEY = GccDigest.of("sha256:" + LayoutRegistry.sha256("{}".getBytes(StandardCharsets.UTF_8)));

  private static final YAMLMapper YAML = new YAMLMapper();

  public static ControlsContract read(Path controlsYaml) throws IOException {
    JsonNode root = YAML.readTree(Files.readString(controlsYaml));
    var systemId = root.path("system_id").asString("");
    var corpusVersion = root.path("corpus_version").asString("");
    if (systemId.isBlank()) throw new IllegalArgumentException(controlsYaml + " names no system_id");
    if (corpusVersion.isBlank()) throw new IllegalArgumentException(controlsYaml + " names no corpus_version");
    var rows = new ArrayList<ContractRow>();
    for (var row : root.path("rows")) {
      var payload = (ObjectNode) row.deepCopy();
      var id = payload.remove("row_id").asString();
      var corpusRef = payload.remove("corpus_ref");
      if (corpusRef == null || corpusRef.asString("").isBlank()) {
        throw new IllegalArgumentException("row " + id + " names no corpus_ref");
      }
      rows.add(ContractRow.corpus(id, corpusRef.asString(), payload));
    }
    return new ControlsContract(systemId, corpusVersion, List.copyOf(rows));
  }

  public GccRootMetadata metadata(long revision, Instant at) {
    return new GccRootMetadata(FormatVersion.CURRENT, systemId, String.valueOf(revision), ContractRevision.of(revision),
        corpusVersion, NO_SURVEY, at);
  }
}
