package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordFlattenerTest {

  @Test
  void nestedObjectsBecomeUnderscoreJoinedProperties() {
    var flat = RecordFlattener.flatten(Map.of("client", Map.of("email", "a@b.c", "tier", 2)));
    assertThat(flat).containsEntry("client_email", "a@b.c").containsEntry("client_tier", 2);
  }

  @Test
  void propertyNamesCannotCarryQueryText() {
    var flat = RecordFlattener.flatten(Map.of("a`) DETACH DELETE n //", "x", "9lives", "y"));
    assertThat(flat.keySet()).allMatch(k -> k.matches("[A-Za-z_][A-Za-z0-9_]*"));
  }

  @Test
  void fieldsThatClashWithStoreProperties_areRenamed() {
    var flat = RecordFlattener.flatten(Map.of("orgId", "evil", "type", "t", "id", "7", "status", "OPEN"));
    assertThat(flat).doesNotContainKeys("orgId", "type", "id").containsKeys("src_orgId", "src_type", "src_id");
    assertThat(flat).containsEntry("status", "OPEN");
    assertThat(RecordFlattener.propertyName("orgId")).isEqualTo("src_orgId");
    assertThat(RecordFlattener.propertyName("status")).isEqualTo("status");
  }

  @Test
  void nullsAreDroppedAndSimpleListsKept() {
    var record = new LinkedHashMap<String, Object>();
    record.put("gone", null);
    record.put("tags", List.of("a", "b"));
    record.put("scores", List.of(1, 2));
    var flat = RecordFlattener.flatten(record);
    assertThat(flat).doesNotContainKey("gone").containsEntry("tags", List.of("a", "b"));
    assertThat(flat.get("scores")).isEqualTo(List.of(1L, 2L));
  }

  @Test
  void listsOfObjectsAreStoredAsText() {
    var flat = RecordFlattener.flatten(Map.of("lines", List.of(Map.of("n", 1))));
    assertThat(flat.get("lines")).isInstanceOf(String.class);
  }

  @Test
  void idsAreTextAndMissingIdsAreNull() {
    assertThat(RecordFlattener.idOf(Map.of("claimId", 42), "claimId")).isEqualTo("42");
    assertThat(RecordFlattener.idOf(Map.of("claimId", "  "), "claimId")).isNull();
    assertThat(RecordFlattener.idOf(Map.of(), "claimId")).isNull();
  }
}
