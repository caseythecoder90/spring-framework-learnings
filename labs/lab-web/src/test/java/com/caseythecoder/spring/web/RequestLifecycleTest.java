package com.caseythecoder.spring.web;

import com.caseythecoder.spring.support.Recorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One request, every hook it passes through, in the order it passes through them.
 *
 * <p>The shape to hold on to is the nesting: a servlet {@code Filter} wraps the
 * {@code DispatcherServlet}, and the {@code DispatcherServlet} wraps everything else. That single
 * fact explains why a filter cannot see your {@code @ExceptionHandler}, why an interceptor can,
 * and why security-as-a-filter behaves differently from security-as-an-interceptor.
 *
 * <p>Notes: docs/web-mvc.md, "One request, end to end".
 */
class RequestLifecycleTest {

    private final Recorder recorder = new Recorder();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new Controller(recorder))
                .addFilter(new RecordingFilter(recorder))
                .addInterceptors(new RecordingInterceptor(recorder))
                .setCustomArgumentResolvers(new RecordingArgumentResolver(recorder))
                .build();
    }

    @Test
    void aSuccessfulRequestPassesThroughTheseHooksInThisOrder() throws Exception {
        mockMvc.perform(get("/hello")).andExpect(status().isOk());

        assertThat(recorder.labels()).containsExactly(
                "filter: before chain",
                "interceptor: preHandle",
                "argument resolver",
                "controller",
                "interceptor: postHandle",
                "interceptor: afterCompletion",
                "filter: after chain");
    }

    @Test
    void anUnhandledExceptionSkipsPostHandleAndReachesTheFilterAsAnException() throws Exception {
        // Nothing resolves this one, so the DispatcherServlet rethrows and it leaves the servlet
        // the same way any exception leaves any method.
        assertThatThrownBy(() -> mockMvc.perform(get("/boom")))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(recorder.labels()).containsExactly(
                "filter: before chain",
                "interceptor: preHandle",
                "controller: throwing",
                // postHandle is for a handler that returned. It is skipped, which is why anything
                // that must always happen belongs in afterCompletion.
                "interceptor: afterCompletion with IllegalStateException",
                // Not IllegalStateException: the DispatcherServlet wraps whatever it could not
                // resolve in a ServletException, so a filter's catch block is one unwrap away
                // from the exception you actually threw.
                "filter: caught ServletException");
    }

    @Test
    void anExceptionHandlerTurnsItIntoAResponseBeforeTheFilterEverSeesIt() throws Exception {
        mockMvc.perform(get("/handled")).andExpect(status().isConflict());

        assertThat(recorder.labels()).containsExactly(
                "filter: before chain",
                "interceptor: preHandle",
                "controller: throwing handled",
                "@ExceptionHandler",
                // afterCompletion is called with a null exception: as far as the interceptor is
                // concerned this request succeeded.
                "interceptor: afterCompletion",
                "filter: after chain");
    }

    @Test
    void anInterceptorThatReturnsFalseStopsTheRequestAndTheControllerNeverRuns() throws Exception {
        mockMvc.perform(get("/hello").header("X-Block", "true")).andExpect(status().isForbidden());

        assertThat(recorder.labels())
                .as("no afterCompletion either: it only runs for interceptors whose preHandle returned true")
                .containsExactly("filter: before chain", "interceptor: preHandle", "filter: after chain");
    }

    @Test
    void anExceptionThrownInAFilterNeverReachesAnExceptionHandlerAtAll() throws Exception {
        // The one every team meets eventually, usually through an authentication filter. The
        // filter is outside the DispatcherServlet, so @ControllerAdvice cannot see it, and what
        // the client gets is the container's error page rather than your error format.
        MockMvc withThrowingFilter = MockMvcBuilders.standaloneSetup(new Controller(recorder))
                .addFilter(new RecordingFilter(recorder))
                .addFilter(new ThrowingFilter())
                .build();

        assertThatThrownBy(() -> withThrowingFilter.perform(get("/handled")))
                .as("raw, unwrapped, and past the DispatcherServlet entirely")
                .isInstanceOf(IllegalStateException.class);

        assertThat(recorder.labels())
                .as("the controller's own @ExceptionHandler was never consulted")
                .doesNotContain("@ExceptionHandler");
    }

    // ---------------------------------------------------------------------------------------

    @RestController
    static class Controller {

        private final Recorder recorder;

        Controller(Recorder recorder) {
            this.recorder = recorder;
        }

        @GetMapping("/hello")
        String hello(RequestId requestId) {
            recorder.record("controller");
            return "hello " + requestId.value();
        }

        @GetMapping("/boom")
        String boom() {
            recorder.record("controller: throwing");
            throw new IllegalStateException("from the controller");
        }

        @GetMapping("/handled")
        String handled() {
            recorder.record("controller: throwing handled");
            throw new ConflictException();
        }

        @ExceptionHandler(ConflictException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        String onConflict() {
            recorder.record("@ExceptionHandler");
            return "conflict";
        }
    }

    static class ConflictException extends RuntimeException {
    }

    /** Stands in for an authentication or tracing filter that rejects a request by throwing. */
    static class ThrowingFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) {

            throw new IllegalStateException("from a filter");
        }
    }

    record RequestId(String value) {
    }

    /**
     * A servlet filter: outside the DispatcherServlet entirely, so it sees an HTTP status rather
     * than a Java exception.
     */
    static class RecordingFilter extends OncePerRequestFilter {

        private final Recorder recorder;

        RecordingFilter(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, java.io.IOException {

            recorder.record("filter: before chain");
            try {
                chain.doFilter(request, response);
            }
            catch (Exception ex) {
                recorder.record("filter: caught " + ex.getClass().getSimpleName());
                throw ex;
            }
            recorder.record("filter: after chain");
        }
    }

    /** A handler interceptor: inside the DispatcherServlet, and aware of the handler method. */
    static class RecordingInterceptor implements HandlerInterceptor {

        private final Recorder recorder;

        RecordingInterceptor(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            recorder.record("interceptor: preHandle");
            if ("true".equals(request.getHeader("X-Block"))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
            return true;
        }

        @Override
        public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                ModelAndView modelAndView) {
            recorder.record("interceptor: postHandle");
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                Exception ex) {
            recorder.record(ex == null
                    ? "interceptor: afterCompletion"
                    : "interceptor: afterCompletion with " + ex.getClass().getSimpleName());
        }
    }

    /** The extension point behind @RequestParam, @PathVariable, Principal, and every other parameter. */
    static class RecordingArgumentResolver implements HandlerMethodArgumentResolver {

        private final Recorder recorder;

        RecordingArgumentResolver(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return RequestId.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

            recorder.record("argument resolver");
            return new RequestId("generated");
        }
    }
}
