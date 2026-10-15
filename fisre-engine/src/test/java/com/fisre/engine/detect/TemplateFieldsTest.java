package com.fisre.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisre.engine.spec.Req;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The editor is generated from Template.fields(), so the fields must be exactly what validation accepts. */
class TemplateFieldsTest {

    private static final List<TemplateSupport> TEMPLATES = List.of(new AggregateTemplate(), new FlowThroughTemplate(), new SequenceTemplate(),
            new DormantReactivationTemplate(), new BaselineDeviationTemplate(), new NewAttributeTemplate(), new MlScoreTemplate());

    @Test
    @Req("REQ-RUI-009")
    void everyTemplateDescribesExactlyTheKeysItAccepts() {
        for (TemplateSupport t : TEMPLATES) {
            Set<String> accepted = new HashSet<>(t.allowedKeys());
            accepted.add("product_types");
            Set<String> described = t.fields().stream().map(Template.Field::key).collect(Collectors.toSet());
            assertThat(described).as("fields of " + t.code()).isEqualTo(accepted);
            assertThat(t.fields().stream().map(Template.Field::kind)).as(t.code()).isSubsetOf("INT", "DECIMAL", "TEXT", "ENUM", "FILTER", "PRODUCTS");
            t.fields().stream().filter(f -> f.kind().equals("ENUM")).forEach(f -> assertThat(f.options()).as(t.code() + "." + f.key()).isNotEmpty());
        }
    }
}
