package com.caseythecoder.spring.web;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which {@code @ExceptionHandler} runs, and the four ways an exception can become a status code.
 *
 * <p>Resolution has a defined order and it is not "the first one written". The controller's own
 * handlers are searched before any {@code @ControllerAdvice}, and within a set of handlers the one
 * closest to the thrown type in the class hierarchy wins - the same depth-scoring idea as
 * {@code rollbackFor} in docs/transactions.md.
 *
 * <p>Notes: docs/web-mvc.md, "Turning an exception into a response".
 */
class ExceptionHandlingTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Controller())
            .setControllerAdvice(new GlobalAdvice())
            .build();

    /** The same advice, plus a controller whose own handler catches every RuntimeException. */
    private final MockMvc withCatchAll = MockMvcBuilders.standaloneSetup(new CatchAllController())
            .setControllerAdvice(new GlobalAdvice())
            .build();

    @Test
    void theHandlerClosestToTheThrownTypeWins() {
        perform(mockMvc, "/specific", status().isNotFound(), "handled: NotFoundException");
    }

    @Test
    void aSupertypeHandlerCatchesAnythingWithNoCloserMatch() {
        perform(withCatchAll, "/general", status().isInternalServerError(), "handled: RuntimeException");
    }

    @Test
    void aBroadLocalHandlerShadowsTheAdviceAndResponseStatusAndEverythingElse() {
        // @ExceptionHandler(RuntimeException.class) on a controller is a catch-all, and it is
        // searched before any @ControllerAdvice. GoneException carries @ResponseStatus(410) and
        // still comes back as the handler's 500, because the resolver never gets that far.
        // Worth knowing before writing "one handler to log everything" on a controller.
        perform(withCatchAll, "/shadowed", status().isInternalServerError(), "handled: RuntimeException");
        perform(withCatchAll, "/shadowed-advice", status().isInternalServerError(), "handled: RuntimeException");
    }

    @Test
    void aControllerLocalHandlerBeatsAControllerAdvice() {
        // Both can handle IllegalArgumentException. The controller's own wins, which is what makes
        // a local override possible without touching the global advice.
        perform(mockMvc, "/local", status().isBadRequest(), "local: IllegalArgumentException");
    }

    @Test
    void aControllerAdviceCatchesWhatTheControllerDoesNot() {
        perform(mockMvc, "/global", status().isServiceUnavailable(), "global: IllegalStateException");
    }

    @Test
    void responseStatusOnTheExceptionClassNeedsNoHandlerAtAll() {
        perform(mockMvc, "/annotated", status().isGone(), null);
    }

    @Test
    void responseStatusExceptionCarriesTheStatusOnTheInstance() {
        // The one to reach for when the status is a runtime decision rather than a property of the
        // exception type. No new class, no handler.
        perform(mockMvc, "/thrown-status", status().isTooManyRequests(), null);
    }

    @Test
    void aHandlerIsAlsoMatchedAgainstTheCauseWhenNothingMatchesTheExceptionItself() {
        // ExceptionHandlerMethodResolver tries the exception, then its cause. Worth knowing before
        // writing a handler for every wrapper type a framework might introduce.
        perform(mockMvc, "/wrapped", status().isNotFound(), "handled: NotFoundException");
    }

    private void perform(MockMvc target, String path,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus, String expectedBody) {
        try {
            var actions = target.perform(get(path)).andExpect(expectedStatus);
            if (expectedBody != null) {
                actions.andExpect(content().string(expectedBody));
            }
        }
        catch (Exception ex) {
            throw new AssertionError("request to " + path + " failed", ex);
        }
    }

    // ---------------------------------------------------------------------------------------

    static class NotFoundException extends RuntimeException {
    }

    @ResponseStatus(HttpStatus.GONE)
    static class GoneException extends RuntimeException {
    }

    static class WrapperException extends RuntimeException {

        WrapperException(Throwable cause) {
            super(cause);
        }
    }

    @RestController
    static class Controller {

        @GetMapping("/specific")
        String specific() {
            throw new NotFoundException();
        }

        @GetMapping("/local")
        String local() {
            throw new IllegalArgumentException();
        }

        @GetMapping("/global")
        String global() {
            throw new IllegalStateException();
        }

        @GetMapping("/annotated")
        String annotated() {
            throw new GoneException();
        }

        @GetMapping("/thrown-status")
        String thrownStatus() {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow down");
        }

        @GetMapping("/wrapped")
        String wrapped() {
            throw new WrapperException(new NotFoundException());
        }

        @ExceptionHandler(NotFoundException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        @ResponseBody
        String onNotFound() {
            return "handled: NotFoundException";
        }

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        @ResponseBody
        String onIllegalArgumentLocally() {
            return "local: IllegalArgumentException";
        }
    }

    /** Kept apart from {@link Controller} so its catch-all does not shadow every other test. */
    @RestController
    static class CatchAllController {

        @GetMapping("/general")
        String general() {
            throw new UnsupportedOperationException("no closer handler than RuntimeException");
        }

        @GetMapping("/shadowed")
        String shadowed() {
            throw new GoneException();
        }

        @GetMapping("/shadowed-advice")
        String shadowedAdvice() {
            throw new IllegalStateException();
        }

        @ExceptionHandler(RuntimeException.class)
        @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        @ResponseBody
        String onRuntime() {
            return "handled: RuntimeException";
        }
    }

    @ControllerAdvice
    static class GlobalAdvice {

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.I_AM_A_TEAPOT)
        @ResponseBody
        String onIllegalArgumentGlobally() {
            return "global: IllegalArgumentException";
        }

        @ExceptionHandler(IllegalStateException.class)
        @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
        @ResponseBody
        String onIllegalState() {
            return "global: IllegalStateException";
        }
    }
}
