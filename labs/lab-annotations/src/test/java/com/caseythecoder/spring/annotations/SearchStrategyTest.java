package com.caseythecoder.spring.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java's own annotation inheritance is much weaker than people assume: {@code @Inherited} works
 * only for class-level annotations on superclasses. Interfaces never pass annotations down, and
 * neither do overridden methods. Spring's search strategies are the workaround, and picking the
 * wrong one is a common source of "why is my annotation being ignored".
 *
 * <p>Notes: docs/annotations.md, "Search strategies".
 */
class SearchStrategyTest {

    @Test
    void javaInheritsClassLevelAnnotationsOnlyWhenTheyAreMarkedInherited() {
        assertThat(SubclassOfInherited.class.getAnnotation(InheritedMarker.class))
                .as("@Inherited works, for superclasses")
                .isNotNull();
        assertThat(SubclassOfPlain.class.getAnnotation(PlainMarker.class))
                .as("without @Inherited, nothing is passed down")
                .isNull();
        assertThat(ImplementsMarked.class.getAnnotation(InheritedMarker.class))
                .as("@Inherited never applies across an interface, even so marked")
                .isNull();
    }

    @Test
    void directSeesOnlyWhatIsOnTheElementItself() {
        MergedAnnotations direct = MergedAnnotations.from(SubclassOfPlain.class, SearchStrategy.DIRECT);

        assertThat(direct.isPresent(PlainMarker.class)).isFalse();
    }

    @Test
    void inheritedAnnotationsMatchesWhatJavaWouldDo() {
        assertThat(MergedAnnotations.from(SubclassOfInherited.class, SearchStrategy.INHERITED_ANNOTATIONS)
                .isPresent(InheritedMarker.class)).isTrue();
        assertThat(MergedAnnotations.from(SubclassOfPlain.class, SearchStrategy.INHERITED_ANNOTATIONS)
                .isPresent(PlainMarker.class))
                .as("still honours the absence of @Inherited")
                .isFalse();
    }

    @Test
    void superclassIgnoresTheInheritedFlagEntirely() {
        // This is the one that surprises people: Spring will happily find an annotation the JVM
        // considers non-inheritable.
        assertThat(MergedAnnotations.from(SubclassOfPlain.class, SearchStrategy.SUPERCLASS)
                .isPresent(PlainMarker.class)).isTrue();

        assertThat(MergedAnnotations.from(ImplementsMarked.class, SearchStrategy.SUPERCLASS)
                .isPresent(PlainMarker.class))
                .as("interfaces are not superclasses")
                .isFalse();
    }

    @Test
    void onlyTypeHierarchyLooksAtInterfaces() {
        assertThat(MergedAnnotations.from(ImplementsMarked.class, SearchStrategy.TYPE_HIERARCHY)
                .isPresent(PlainMarker.class)).isTrue();
    }

    @Test
    void anAnnotationOnAnInterfaceMethodNeedsTheHierarchySearchToo() throws Exception {
        var implementation = ImplementsMarked.class.getDeclaredMethod("run");

        assertThat(implementation.getAnnotation(PlainMarker.class))
                .as("an override does not inherit the annotation")
                .isNull();
        assertThat(MergedAnnotations.from(implementation, SearchStrategy.DIRECT)
                .isPresent(PlainMarker.class)).isFalse();
        assertThat(MergedAnnotations.from(implementation, SearchStrategy.TYPE_HIERARCHY)
                .isPresent(PlainMarker.class))
                .as("Spring walks up to the interface method")
                .isTrue();
    }

    @Test
    void getVersusFindIsTheSameDistinctionWithShorterNames() {
        // AnnotatedElementUtils spells the two common strategies as get* and find*:
        //   get*  -> INHERITED_ANNOTATIONS, roughly "what Java sees"
        //   find* -> TYPE_HIERARCHY, "look everywhere"
        assertThat(AnnotatedElementUtils.hasAnnotation(ImplementsMarked.class, PlainMarker.class))
                .as("hasAnnotation searches the full type hierarchy")
                .isTrue();
        assertThat(AnnotatedElementUtils.isAnnotated(ImplementsMarked.class, PlainMarker.class))
                .as("isAnnotated does not")
                .isFalse();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @interface PlainMarker {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    @interface InheritedMarker {
    }

    @PlainMarker
    interface Marked {

        @PlainMarker
        void run();
    }

    @PlainMarker
    static class PlainBase {
    }

    @InheritedMarker
    static class InheritedBase {
    }

    static class SubclassOfPlain extends PlainBase {
    }

    static class SubclassOfInherited extends InheritedBase {
    }

    static class ImplementsMarked implements Marked {

        @Override
        public void run() {
        }
    }
}
