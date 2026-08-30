package com.caseythecoder.spring.annotations;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain Java, no Spring. These are the facts the rest of this lab builds on, and they are pinned
 * here rather than asserted in prose because the note leans on every one of them.
 *
 * <p>Nothing here is obscure, but it is a corner of Java that most professional work never opens:
 * annotations are interfaces, they are only visible at runtime if they say so, reflection does not
 * traverse them, and the instance you are handed is already a proxy before Spring gets involved.
 *
 * <p>Notes: docs/annotations.md, "Before this note".
 */
class JavaAnnotationBaselineTest {

    @Test
    void anAnnotationTypeIsAnInterface() {
        // @interface Foo {} compiles to an interface extending java.lang.annotation.Annotation.
        // Its attributes are abstract methods, which is why they are declared with () and why
        // @AliasFor can talk about them as methods at all.
        assertThat(Marker.class.isInterface()).isTrue();
        assertThat(Marker.class.isAnnotation()).isTrue();
        assertThat(Annotation.class).isAssignableFrom(Marker.class);

        assertThat(Marker.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("value");
    }

    @Test
    void onlyRuntimeRetentionSurvivesToReflection() {
        // RetentionPolicy defaults to CLASS: written into the class file, then never loaded by the
        // JVM. An annotation without @Retention(RUNTIME) is completely invisible to Spring, and
        // nothing warns you. This is the first thing to check when a custom annotation "does
        // nothing".
        assertThat(Annotated.class.getAnnotation(Marker.class))
                .as("declared RUNTIME")
                .isNotNull();
        assertThat(Annotated.class.getAnnotation(CompileTimeOnly.class))
                .as("declared CLASS, so the JVM never loads it")
                .isNull();
        assertThat(Annotated.class.getAnnotations())
                .extracting(a -> a.annotationType().getSimpleName())
                .contains("Marker")
                .doesNotContain("CompileTimeOnly");
    }

    @Test
    void reflectionReturnsOnlyWhatIsWrittenOnTheElement() {
        // Meta-annotations are invisible to plain reflection. @Stereotype is annotated @Marker,
        // and Java has no interest in that relationship. Everything Spring does in this area is
        // built to close this one gap.
        assertThat(Stereotyped.class.getAnnotation(Stereotype.class)).isNotNull();
        assertThat(Stereotyped.class.getAnnotation(Marker.class))
                .as("@Stereotype is meta-annotated @Marker, and reflection does not care")
                .isNull();
    }

    @Test
    void inheritedAppliesToClassesOnlyAndOnlyUpTheSuperclassChain() {
        assertThat(SubclassOfInherited.class.getAnnotation(InheritedMarker.class))
                .as("superclass, and the annotation is @Inherited")
                .isNotNull();
        assertThat(SubclassOfPlain.class.getAnnotation(Marker.class))
                .as("superclass, but no @Inherited")
                .isNull();
        assertThat(Implementor.class.getAnnotation(InheritedMarker.class))
                .as("interfaces never pass annotations down, @Inherited or not")
                .isNull();
    }

    @Test
    void anInheritedAnnotationIsNotDeclaredOnTheSubclass() {
        // getAnnotation and getDeclaredAnnotation differ exactly here, which is the same
        // distinction Spring spells as isPresent versus isDirectlyPresent.
        assertThat(SubclassOfInherited.class.getAnnotation(InheritedMarker.class)).isNotNull();
        assertThat(SubclassOfInherited.class.getDeclaredAnnotation(InheritedMarker.class)).isNull();
    }

    @Test
    void theAnnotationInstanceYouGetIsAlreadyAProxy() {
        // Before Spring is anywhere near this. The JVM has no concrete class implementing your
        // annotation interface, so it generates a JDK dynamic proxy backed by the attribute values
        // from the class file.
        //
        // This is why Spring's synthesize() is not exotic: it substitutes its own proxy, with
        // merged and aliased values, for the one the JVM would have made.
        Marker marker = Annotated.class.getAnnotation(Marker.class);

        assertThat(Proxy.isProxyClass(marker.getClass())).isTrue();
        assertThat(marker.getClass())
                .as("never switch on getClass() for an annotation")
                .isNotEqualTo(Marker.class);
        assertThat(marker.annotationType())
                .as("annotationType() is the accessor that tells the truth")
                .isEqualTo(Marker.class);
        assertThat(marker.value()).isEqualTo("declared");
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @interface Marker {

        String value() default "";
    }

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.TYPE)
    @interface CompileTimeOnly {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    @interface InheritedMarker {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Marker("via-meta")
    @interface Stereotype {
    }

    @Marker("declared")
    @CompileTimeOnly
    static class Annotated {
    }

    @Stereotype
    static class Stereotyped {
    }

    @Marker
    static class PlainBase {
    }

    @InheritedMarker
    static class InheritedBase {
    }

    @InheritedMarker
    interface MarkedInterface {
    }

    static class SubclassOfPlain extends PlainBase {
    }

    static class SubclassOfInherited extends InheritedBase {
    }

    static class Implementor implements MarkedInterface {
    }
}
