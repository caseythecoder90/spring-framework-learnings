package com.caseythecoder.spring.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import org.springframework.core.annotation.AliasFor;
import org.springframework.core.annotation.AnnotationConfigurationException;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @AliasFor} does two different jobs that happen to share a name.
 *
 * <p>Within one annotation it makes two attributes interchangeable, which is how
 * {@code @RequestMapping("/x")} and {@code @RequestMapping(path = "/x")} mean the same thing.
 * Pointing at a meta-annotation it overrides an attribute further up the graph, which is how
 * {@code @GetMapping("/x")} sets {@code @RequestMapping}'s path.
 *
 * <p>Notes: docs/annotations.md, "@AliasFor".
 */
class AliasForTest {

    @Test
    void twoAliasedAttributesInTheSameAnnotationAreInterchangeable() {
        MergedAnnotation<Endpoint> viaValue =
                MergedAnnotations.from(DeclaredWithValue.class).get(Endpoint.class);
        MergedAnnotation<Endpoint> viaPath =
                MergedAnnotations.from(DeclaredWithPath.class).get(Endpoint.class);

        assertThat(viaValue.getString("value")).isEqualTo("/orders");
        assertThat(viaValue.getString("path")).isEqualTo("/orders");
        assertThat(viaPath.getString("value")).isEqualTo("/orders");
        assertThat(viaPath.getString("path")).isEqualTo("/orders");
    }

    @Test
    void theSynthesizedAnnotationMirrorsTheAliasToo() {
        // Synthesizing gives back something that implements the annotation interface, so ordinary
        // annotation calls see the merged values. This is what Spring hands to your code.
        Endpoint synthesized = MergedAnnotations.from(DeclaredWithPath.class)
                .get(Endpoint.class)
                .synthesize();

        assertThat(synthesized.value()).isEqualTo("/orders");
        assertThat(synthesized.path()).isEqualTo("/orders");
        assertThat(synthesized.annotationType()).isEqualTo(Endpoint.class);
    }

    @Test
    void anAliasCanOverrideAnAttributeOnAMetaAnnotation() {
        // ReadOnlyEndpoint declares no path of its own; its "route" attribute is an alias for
        // Endpoint's "path". This is exactly the @GetMapping to @RequestMapping relationship.
        MergedAnnotation<Endpoint> merged =
                MergedAnnotations.from(ReportsEndpoint.class).get(Endpoint.class);

        assertThat(merged.isPresent()).isTrue();
        assertThat(merged.getString("path")).isEqualTo("/reports");
        assertThat(merged.getString("value")).isEqualTo("/reports");
        assertThat(merged.getDistance()).as("reached through the meta-annotation").isEqualTo(1);
    }

    @Test
    void settingBothHalvesOfAnAliasToDifferentValuesIsAnError() {
        // The failure is at read time, not compile time, which is why it tends to show up as a
        // startup failure in an unrelated-looking place.
        assertThatThrownBy(() -> MergedAnnotations.from(Contradictory.class).get(Endpoint.class).synthesize())
                .isInstanceOf(AnnotationConfigurationException.class)
                .hasMessageContaining("Different @AliasFor mirror values")
                .hasMessageContaining("are declared with values of");
    }

    @Test
    void settingBothHalvesToTheSameValueIsFine() {
        MergedAnnotation<Endpoint> merged = MergedAnnotations.from(Consistent.class).get(Endpoint.class);

        assertThat(merged.getString("path")).isEqualTo("/same");
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Endpoint {

        @AliasFor("path")
        String value() default "";

        @AliasFor("value")
        String path() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Endpoint
    @interface ReadOnlyEndpoint {

        @AliasFor(annotation = Endpoint.class, attribute = "path")
        String route() default "";
    }

    @Endpoint("/orders")
    static class DeclaredWithValue {
    }

    @Endpoint(path = "/orders")
    static class DeclaredWithPath {
    }

    @ReadOnlyEndpoint(route = "/reports")
    static class ReportsEndpoint {
    }

    @Endpoint(value = "/one", path = "/other")
    static class Contradictory {
    }

    @Endpoint(value = "/same", path = "/same")
    static class Consistent {
    }
}
