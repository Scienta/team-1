package no.scienta.cfp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs one line per request: method, path and query, status, time taken, user agent and both bodies (truncated).
 * The harness's checks are only visible through these lines, so this is the main debugging aid on the platform.
 *
 * Errors raised with sendError (ResponseStatusException and friends) get their body written later, by Spring Boot's
 * error dispatch, so those requests log a second line, marked "error", with the error body.
 * Health probes that answer 200 aren't logged: the platform polls them and they'd drown everything else.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("http");

    static final int MAX_BODY = 2000;

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            logErrorDispatch(request, response, chain);
            return;
        }
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, MAX_BODY + 1);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        long started = System.nanoTime();
        Integer failedWith = null;
        try {
            chain.doFilter(req, res);
        } catch (IOException | ServletException | RuntimeException e) {
            failedWith = 500;
            throw e;
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            int status = failedWith != null ? failedWith : res.getStatus();
            if (!(status == 200 && "/health".equals(request.getRequestURI()))) {
                log.info("{} {} -> {} {}ms ua={} req={} res={}",
                        request.getMethod(), pathAndQuery(request), status, millis,
                        oneLine(request.getHeader("User-Agent")),
                        requestBody(req), body(res.getContentAsByteArray(), res.getContentType()));
            }
            res.copyBodyToResponse();
        }
    }

    private void logErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, res);
        } finally {
            Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
            log.info("{} {} -> {} error res={}", request.getMethod(), uri != null ? uri : request.getRequestURI(),
                    res.getStatus(), body(res.getContentAsByteArray(), res.getContentType()));
            res.copyBodyToResponse();
        }
    }

    private static String pathAndQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private static String requestBody(ContentCachingRequestWrapper req) {
        byte[] cached = req.getContentAsByteArray();
        if (cached.length == 0 && req.getContentLengthLong() > 0) {
            return "(" + req.getContentLengthLong() + " bytes, not read by the app)";
        }
        return body(cached, req.getContentType());
    }

    static String body(byte[] bytes, String contentType) {
        if (bytes.length == 0) {
            return "-";
        }
        if (contentType != null && !isText(contentType)) {
            return "(" + bytes.length + " bytes " + contentType + ")";
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() > MAX_BODY) {
            text = text.substring(0, MAX_BODY) + "…(truncated)";
        }
        return oneLine(text);
    }

    private static boolean isText(String contentType) {
        String type = contentType.toLowerCase();
        return type.contains("json") || type.startsWith("text/") || type.contains("xml")
                || type.contains("x-www-form-urlencoded");
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "-";
        }
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }
}
