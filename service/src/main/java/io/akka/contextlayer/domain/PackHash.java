package io.akka.contextlayer.domain;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A content hash of a pack, so equal definitions are the same version and any edit is a new one. */
public final class PackHash {

  private static final ObjectMapper CANONICAL = JsonMapper.builder()
      .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
      .build();

  private PackHash() {}

  public static String of(PackDefinition pack) {
    try {
      var json = CANONICAL.writeValueAsBytes(pack);
      var digest = MessageDigest.getInstance("SHA-256").digest(json);
      return HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
