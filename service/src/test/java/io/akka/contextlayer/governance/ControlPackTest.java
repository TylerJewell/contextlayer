package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The control pack is Verify's form of the controls draft; it is generated from it and must not drift. */
public class ControlPackTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static JsonNode read(String name) throws IOException {
    try (var in = ControlPackTest.class.getClassLoader().getResourceAsStream("governance/pack/" + name)) {
      assertThat(in).as(name).isNotNull();
      return YAML.readTree(in);
    }
  }

  @Test
  public void everyControlIsOnePackOriginalControlWithItsMechanismAndFlavor() throws IOException {
    var catalog = ControlCatalog.load();
    var originals = new HashMap<String, JsonNode>();
    read("controls-original.yaml").path("controls").forEach(c -> originals.put(c.path("sources").get(0).asText(), c));

    assertThat(originals).hasSize(catalog.controls().size());
    for (var control : catalog.controls()) {
      var original = originals.get("contextlayer controls draft (governance/controls.yaml), row " + control.rowId());
      assertThat(original).as(control.rowId() + " is in the pack; run governance/pack/generate.py").isNotNull();
      var mechanism = original.path("akka_runtime").path("mechanisms").get(0);
      assertThat(mechanism.path("kind").asText()).isEqualTo(control.mechanism().name().toLowerCase());
      assertThat(mechanism.path("flavor").asText()).isEqualTo(control.flavor());
      assertThat(original.path("title").asText()).isEqualTo(control.name());
      if (control.hook() != null) assertThat(mechanism.path("hook").asText()).isEqualTo(control.hook());
    }
  }

  @Test
  public void theSelectionsAreTheCorpusControlsTheControlsMeet() throws IOException {
    var catalog = ControlCatalog.load();
    var expected = new TreeSet<String>();
    catalog.controls().forEach(c -> expected.addAll(c.obligations()));
    var selected = new TreeSet<String>();
    read("pack.yaml").path("selections").forEach(s -> selected.add(s.path("control_id").asText()));

    assertThat(selected).isEqualTo(expected);
    assertThat(read("pack.yaml").path("publisher_type").asText()).isEqualTo("customer");
  }

  @Test
  public void controlIdsArePackScoped() throws IOException {
    Map<String, Integer> seen = new HashMap<>();
    read("controls-original.yaml").path("controls").forEach(c -> {
      assertThat(c.path("framework").asText()).isEqualTo("pack:contextlayer-governed-context");
      assertThat(c.path("id").asText()).matches("contextlayer-governed-context[.][a-z]+[.]cl[0-9]{4}");
      seen.merge(c.path("id").asText(), 1, Integer::sum);
    });
    assertThat(seen.values()).containsOnly(1);
  }
}
