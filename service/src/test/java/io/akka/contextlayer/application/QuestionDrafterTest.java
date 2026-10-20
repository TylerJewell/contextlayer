package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.application.EntityStore.FieldSchema;
import io.akka.contextlayer.application.EntityStore.TypeSchema;
import io.akka.contextlayer.domain.StructuredQuestion.Op;
import java.util.List;
import org.junit.jupiter.api.Test;

public class QuestionDrafterTest {

  private static FieldSchema field(String name, String kind, long distinct, long sampled, String... values) {
    return new FieldSchema(name, kind, distinct, sampled, List.of(values));
  }

  @Test
  public void aFewCategoriesAcrossManyRecordsAreShownAndEverythingElseIsOnlyNamed() {
    var schema = List.of(new TypeSchema("Claim", 500, List.of(
        field("claimId", "string", 200, 200, "CL-1", "CL-2"),
        field("status", "string", 2, 200, "OPEN", "CLOSED"),
        field("daysOpen", "number", 60, 200),
        field("urgent", "boolean", 2, 200))));

    var shown = QuestionDrafter.describe(schema);

    assertThat(shown).contains("Claim (500 records)").contains("status: string, one of OPEN, CLOSED")
        .contains("daysOpen: number").contains("urgent: boolean").contains("claimId: string")
        .doesNotContain("CL-1").doesNotContain("one of CL");
  }

  @Test
  public void categoriesAreWithheldFromSmallSamplesLongValuesAndAnythingMaskingWouldChange() {
    assertThat(QuestionDrafter.categories(field("status", "string", 3, 4, "A", "B", "C"))).isEmpty();
    assertThat(QuestionDrafter.categories(field("note", "string", 2, 100, "x".repeat(31), "short"))).isEmpty();
    assertThat(QuestionDrafter.categories(field("contact", "string", 2, 100, "pat@example.com", "other"))).isEmpty();
    assertThat(QuestionDrafter.categories(field("owner", "string", 9, 500, "a", "b"))).isEmpty();
    assertThat(QuestionDrafter.categories(field("tier", "string", 2, 100, "gold", "silver"))).containsExactly("gold", "silver");
  }

  @Test
  public void operatorsAreReadInWordsOrSymbols() {
    assertThat(QuestionDrafter.operator("gt")).contains(Op.GT);
    assertThat(QuestionDrafter.operator(">=")).contains(Op.GTE);
    assertThat(QuestionDrafter.operator("=")).contains(Op.EQ);
    assertThat(QuestionDrafter.operator("!=")).contains(Op.NEQ);
    assertThat(QuestionDrafter.operator("LIKE")).isEmpty();
    assertThat(QuestionDrafter.operator(null)).isEmpty();
  }

  @Test
  public void valuesAreMadeTheKindTheFieldHolds() {
    assertThat(QuestionDrafter.coerce("number", "30")).contains(30L);
    assertThat(QuestionDrafter.coerce("number", "2.5")).contains(2.5);
    assertThat(QuestionDrafter.coerce("number", "thirty")).isEmpty();
    assertThat(QuestionDrafter.coerce("boolean", "TRUE")).contains(true);
    assertThat(QuestionDrafter.coerce("boolean", "yes")).isEmpty();
    assertThat(QuestionDrafter.coerce("string", " OPEN ")).contains(" OPEN ");
    assertThat(QuestionDrafter.coerce("string", null)).isEmpty();
  }

  @Test
  void relationshipsAreShownToTheModelByNameAndTarget() {
    var schema = List.of(new TypeSchema("Claim", 5, List.of(field("claimId", "string", 5, 5)),
        List.of(new EntityStore.Relation("HAS_ACCOUNT", "Account"))));

    assertThat(QuestionDrafter.describe(schema)).contains("relationship HAS_ACCOUNT -> Account");
  }

  @Test
  void aRelationshipFieldHoldsOneIdOrAListOfThem() {
    assertThat(EntityRefresher.idsOf("w1")).containsExactly("w1");
    assertThat(EntityRefresher.idsOf(List.of("w1", " w2 ", "", "w3"))).containsExactly("w1", "w2", "w3");
    assertThat(EntityRefresher.idsOf(42)).containsExactly("42");
    assertThat(EntityRefresher.idsOf(null)).isEmpty();
  }
}
