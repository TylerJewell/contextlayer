package io.akka.contextlayer.contract;

import io.akka.nexus.governance.contract.oci.OciDescriptor;
import io.akka.nexus.governance.contract.oci.OciManifest;
import io.akka.nexus.governance.contract.oci.RegistryClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A registry kept as an OCI image layout in one directory: {@code oci-layout}, {@code index.json} and
 * {@code blobs/sha256/}. Every manifest is listed in the index, a tag as its {@code ref.name} annotation, so the same
 * bytes can be copied to a real registry unchanged ({@code oras copy --from-oci-layout}). One directory holds one
 * system's contracts; the repository name is not used.
 */
public final class LayoutRegistry implements RegistryClient {

  static final String REF_NAME = "org.opencontainers.image.ref.name";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path root;

  public LayoutRegistry(Path root) {
    this.root = root;
    try {
      Files.createDirectories(root.resolve("blobs/sha256"));
      Files.writeString(root.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}");
      if (!Files.exists(index())) writeIndex(new ArrayList<>());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public OciDescriptor pushBlob(String repository, byte[] content, String mediaType) {
    return OciDescriptor.of(mediaType, store(content), content.length);
  }

  @Override
  public byte[] getBlob(String repository, String digest) {
    try {
      return Files.readAllBytes(blob(digest));
    } catch (NoSuchFileException e) {
      throw new IllegalArgumentException("no blob " + digest);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public String pushManifest(String repository, String reference, byte[] manifestBytes) {
    var digest = store(manifestBytes);
    list(digest, manifestBytes.length, reference.startsWith("sha256:") ? null : reference);
    return digest;
  }

  @Override
  public byte[] getManifest(String repository, String reference) {
    var digest = reference.startsWith("sha256:") ? reference
        : resolveTag(repository, reference).orElseThrow(() -> new IllegalArgumentException("no tag " + reference));
    return getBlob(repository, digest);
  }

  @Override
  public Optional<String> resolveTag(String repository, String tag) {
    return entries().stream().filter(e -> tag.equals(e.path("annotations").path(REF_NAME).asString(null)))
        .map(e -> e.path("digest").asString()).findFirst();
  }

  @Override
  public void writeTag(String repository, String tag, byte[] manifestBytes) {
    var digest = store(manifestBytes);
    list(digest, manifestBytes.length, tag);
  }

  @Override
  public List<OciDescriptor> referrers(String repository, String subjectDigest, Optional<String> artifactTypeFilter) {
    var found = new LinkedHashMap<String, OciDescriptor>();
    for (var entry : entries()) {
      var digest = entry.path("digest").asString();
      var manifest = MAPPER.readTree(getBlob(repository, digest));
      if (!subjectDigest.equals(manifest.path("subject").path("digest").asString(null))) continue;
      var type = manifest.path("artifactType").asString(null);
      if (artifactTypeFilter.isPresent() && !artifactTypeFilter.get().equals(type)) continue;
      var annotations = new LinkedHashMap<String, String>();
      manifest.path("annotations").properties().forEach(p -> annotations.put(p.getKey(), p.getValue().asString()));
      found.putIfAbsent(digest, new OciDescriptor(OciManifest.MEDIA_TYPE, digest, entry.path("size").asLong(),
          annotations, Optional.ofNullable(type)));
    }
    return List.copyOf(found.values());
  }

  @Override
  public boolean supportsReferrers(String repository) {
    return true;
  }

  // ---- the layout

  private Path index() {
    return root.resolve("index.json");
  }

  private Path blob(String digest) {
    if (!digest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("not a sha256 digest: " + digest);
    return root.resolve("blobs/sha256").resolve(digest.substring("sha256:".length()));
  }

  private String store(byte[] content) {
    var digest = "sha256:" + sha256(content);
    try {
      var path = blob(digest);
      if (!Files.exists(path)) Files.write(path, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return digest;
  }

  private List<JsonNode> entries() {
    try {
      var manifests = MAPPER.readTree(Files.readString(index(), StandardCharsets.UTF_8)).path("manifests");
      var out = new ArrayList<JsonNode>();
      manifests.forEach(out::add);
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Lists a manifest in the index. A tag moves: it is taken off any other manifest first. */
  private synchronized void list(String digest, long size, String tag) {
    var entries = new ArrayList<ObjectNode>();
    boolean present = false;
    for (var e : entries()) {
      var entry = (ObjectNode) e;
      if (tag != null && tag.equals(entry.path("annotations").path(REF_NAME).asString(null))) {
        entry.remove("annotations");
      }
      if (digest.equals(entry.path("digest").asString()) && !entry.has("annotations")) {
        if (present) continue;
        present = true;
        if (tag != null) entry.set("annotations", MAPPER.createObjectNode().put(REF_NAME, tag));
      }
      entries.add(entry);
    }
    if (!present) {
      var entry = MAPPER.createObjectNode().put("mediaType", OciManifest.MEDIA_TYPE).put("digest", digest).put("size", size);
      if (tag != null) entry.set("annotations", MAPPER.createObjectNode().put(REF_NAME, tag));
      entries.add(entry);
    }
    writeIndex(entries);
  }

  private void writeIndex(List<ObjectNode> entries) {
    var index = MAPPER.createObjectNode().put("schemaVersion", 2);
    ArrayNode manifests = index.putArray("manifests");
    entries.forEach(manifests::add);
    try {
      Files.writeString(index(), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(index) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
