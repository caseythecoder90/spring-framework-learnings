package com.caseythecoder.spring.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Why {@code @RestController} works everywhere {@code @Component} is expected, even though the
 * class is not annotated with {@code @Component} and Java has no idea the two are related.
 *
 * <p>Java's own reflection is no help here: {@code getAnnotation(Component.class)} on a
 * {@code @Service} class returns null. Spring walks the annotation graph itself.
 *
 * <p>Notes: docs/annotations.md, "Meta-annotations".
 */
class MetaAnnotationTest {

    @Test
    void javaReflectionSeesOnlyWhatIsWrittenOnTheClass() {
        assertThat(OrderEndpoint.class.getAnnotation(ServiceEndpoint.class)).isNotNull();
        assertThat(OrderEndpoint.class.getAnnotation(Service.class))
                .as("plain reflection does not follow meta-annotations")
                .isNull();
        assertThat(OrderEndpoint.class.getAnnotation(Component.class)).isNull();
    }

    @Test
    void springFollowsTheAnnotationGraphTransitively() {
        MergedAnnotations annotations = MergedAnnotations.from(OrderEndpoint.class);

        // @ServiceEndpoint -> @Service -> @Component, none of them written on the class itself.
        assertThat(annotations.isPresent(ServiceEndpoint.class)).isTrue();
        assertThat(annotations.isPresent(Service.class)).isTrue();
        assertThat(annotations.isPresent(Component.class)).isTrue();
    }

    @Test
    void distanceIsHowManyHopsAwayTheAnnotationIs() {
        MergedAnnotations annotations = MergedAnnotations.from(OrderEndpoint.class);

        assertThat(annotations.get(ServiceEndpoint.class).getDistance()).isEqualTo(0);
        assertThat(annotations.get(Service.class).getDistance()).isEqualTo(1);
        assertThat(annotations.get(Component.class).getDistance()).isEqualTo(2);
    }

    @Test
    void directlyPresentIsTheDistinctionThatCatchesPeopleOut() {
        MergedAnnotations annotations = MergedAnnotations.from(OrderEndpoint.class);

        assertThat(annotations.isDirectlyPresent(ServiceEndpoint.class)).isTrue();
        assertThat(annotations.isDirectlyPresent(Component.class))
                .as("present, but not written on the class")
                .isFalse();
        assertThat(annotations.get(Component.class).isMetaPresent()).isTrue();
    }

    @Test
    void aMissingAnnotationGivesAnAbsentMergedAnnotationRatherThanNull() {
        // Never null, so Spring's own code chains calls without null checks. But absent is not the
        // same as empty: reading an attribute off it throws, and getValue() is the safe accessor.
        MergedAnnotation<Deprecated> absent = MergedAnnotations.from(OrderEndpoint.class).get(Deprecated.class);

        assertThat(absent).isNotNull();
        assertThat(absent.isPresent()).isFalse();
        assertThat(absent.getValue("since", String.class)).isEmpty();
        assertThatThrownBy(() -> absent.getString("since")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getAnnotationStopsOneMetaLevelOutButFindAnnotationDoesNot() {
        // The legacy AnnotationUtils API is not one API, it is two with very different reach.
        //
        //   getAnnotation  -> INHERITED_ANNOTATIONS + synthesize(AnnotationUtils::isSingleLevelPresent)
        //   findAnnotation -> TYPE_HIERARCHY        + synthesize(MergedAnnotation::isPresent)
        //
        // @Service is one hop from @ServiceEndpoint, so both find it. @Component is two hops, and
        // only findAnnotation goes that far. Reaching for the wrong one gives a silent null.
        assertThat(AnnotationUtils.getAnnotation(OrderEndpoint.class, Service.class)).isNotNull();
        assertThat(AnnotationUtils.getAnnotation(OrderEndpoint.class, Component.class))
                .as("two meta-levels out is beyond what getAnnotation will look")
                .isNull();

        assertThat(AnnotationUtils.findAnnotation(OrderEndpoint.class, Service.class)).isNotNull();
        assertThat(AnnotationUtils.findAnnotation(OrderEndpoint.class, Component.class)).isNotNull();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Documented
    @Service
    @interface ServiceEndpoint {

        String value() default "";
    }

    @ServiceEndpoint("orders")
    static class OrderEndpoint {
    }
}
