package com.caseythecoder.spring.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How a controller method parameter gets a value, and what happens when it cannot.
 *
 * <p>Every parameter is resolved by some {@code HandlerMethodArgumentResolver}, chosen by asking
 * each one in turn whether it {@code supportsParameter}. The two facts worth carrying around are
 * that <em>required</em> is the default for {@code @RequestParam}, and that an unannotated
 * parameter is not ignored - it falls through to a default resolver whose choice depends on the
 * parameter's type.
 *
 * <p>Notes: docs/web-mvc.md, "Getting values into the method".
 */
class ArgumentBindingTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Controller()).build();

    @Test
    void requestParamIsRequiredByDefaultAndAMissingOneIsA400() throws Exception {
        mockMvc.perform(get("/search")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/search").param("q", "spring"))
                .andExpect(status().isOk())
                .andExpect(content().string("q=spring page=1"));
    }

    @Test
    void defaultValueMakesItOptionalAndSuppliesAValue() throws Exception {
        mockMvc.perform(get("/search").param("q", "spring").param("page", "7"))
                .andExpect(content().string("q=spring page=7"));
    }

    @Test
    void aValueOfTheWrongTypeIsA400NotA500() throws Exception {
        // MethodArgumentTypeMismatchException, which the default handler maps to 400. It is a
        // client error, and the default behaviour already says so.
        mockMvc.perform(get("/search").param("q", "spring").param("page", "not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pathVariablesComeFromTheUriTemplate() throws Exception {
        mockMvc.perform(get("/orders/42")).andExpect(content().string("order 42"));
    }

    @Test
    void anUnannotatedSimpleTypeIsTreatedAsARequestParam() throws Exception {
        mockMvc.perform(get("/implicit").param("term", "shoes"))
                .andExpect(content().string("term=shoes"));
    }

    @Test
    void anUnannotatedComplexTypeIsTreatedAsAModelAttributeAndBoundFromRequestParameters() throws Exception {
        // The surprise: this is not read from the request body. A parameter object with no
        // annotation is populated field by field from query and form parameters, which is why a
        // POST of JSON to a method like this silently arrives empty.
        mockMvc.perform(get("/filters").param("colour", "red").param("size", "large"))
                .andExpect(content().string("colour=red size=large"));

        mockMvc.perform(get("/filters"))
                .andExpect(status().isOk())
                .andExpect(content().string("colour=null size=null"));
    }

    @Test
    void requestBodyGoesThroughAMessageConverter() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"boots\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(content().string("boots x2"));
    }

    @Test
    void aBodyTheConverterCannotReadIsA400() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnsupportedContentTypeIsA415() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("boots"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void validOnTheBodyTurnsAConstraintViolationIntoA400() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutValidTheSameBodyIsAcceptedAndTheConstraintsDoNothing() throws Exception {
        // Constraint annotations on a class are inert until something asks for validation.
        // Forgetting @Valid is the quietest way to have validation that is not running.
        mockMvc.perform(post("/orders/unvalidated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"\",\"quantity\":0}"))
                .andExpect(status().isOk())
                .andExpect(content().string(" x0"));
    }

    // ---------------------------------------------------------------------------------------

    record NewOrder(@NotBlank String item, @Min(1) int quantity) {
    }

    static class Filters {

        private String colour;

        private String size;

        public String getColour() {
            return this.colour;
        }

        public void setColour(String colour) {
            this.colour = colour;
        }

        public String getSize() {
            return this.size;
        }

        public void setSize(String size) {
            this.size = size;
        }
    }

    @RestController
    static class Controller {

        @GetMapping("/search")
        String search(@RequestParam String q, @RequestParam(defaultValue = "1") int page) {
            return "q=" + q + " page=" + page;
        }

        @GetMapping("/orders/{id}")
        String order(@PathVariable String id) {
            return "order " + id;
        }

        @GetMapping("/implicit")
        String implicitRequestParam(String term) {
            return "term=" + term;
        }

        @GetMapping("/filters")
        String implicitModelAttribute(Filters filters) {
            return "colour=" + filters.getColour() + " size=" + filters.getSize();
        }

        @PostMapping("/orders")
        String create(@Valid @RequestBody NewOrder order) {
            return order.item() + " x" + order.quantity();
        }

        @PostMapping("/orders/unvalidated")
        String createUnvalidated(@RequestBody NewOrder order) {
            return order.item() + " x" + order.quantity();
        }
    }
}
