package no.scienta.cfp;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.ClassUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Error bodies byte for byte as Spring Boot 2.7 wrote them (specs/spring-boot-4/spec.md R12a, D9a; the PO wants them
 * exactly the same). Boot keeps the shape; what Spring 7 and Jackson 3 changed is put back here, in one place:
 * <ul>
 * <li>Request bodies are read with Jackson 2, configured as Boot 2.7 configured it, so a parse error is Jackson 2's
 * exception with its text and {@code [Source: ...]} location. Responses are still written by Boot 4's Jackson 3.</li>
 * <li>{@code message}: Spring 5 rebuilt from the exception's own fields for the framework exceptions whose wording
 * changed (Spring 5's NestedRuntimeException appended "; nested exception is &lt;cause&gt;").</li>
 * <li>{@code errors} of @Valid failures: Spring 5's property order, and the fields in declaration order (2.7's order
 * came from a HashSet and changed from request to request).</li>
 * <li>{@code timestamp}: yyyy-MM-dd'T'HH:mm:ss.SSS+00:00 in UTC, as Jackson 2 wrote a Date (Jackson 3 writes Z).</li>
 * </ul>
 */
@Configuration
public class LegacyErrorBodies implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // Right before Boot's Jackson 3 JSON converter, where 2.7 had its Jackson 2 one: String and byte[] bodies
        // (the acceptance endpoints read the body as text) still go to the converters ahead of it.
        int jackson3 = 0;
        while (jackson3 < converters.size() && !(converters.get(jackson3) instanceof JacksonJsonHttpMessageConverter)) {
            jackson3++;
        }
        converters.add(jackson3, new Jackson2Reader());
    }

    /** Reads JSON request bodies like Boot 2.7's converter did; never writes. */
    @SuppressWarnings("removal")
    static final class Jackson2Reader extends MappingJackson2HttpMessageConverter {

        Jackson2Reader() {
            super(JsonMapper.builder()
                    // Boot 2.7's Jackson2ObjectMapperBuilder defaults
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                    // spring.jackson.parser.allow-comments=true (D7)
                    .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                    // on by default up to Jackson 2.15 (2.7 had 2.13): the [Source: ...] part of parse errors
                    .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                    .build());
        }

        @Override
        public boolean canWrite(Class<?> clazz, MediaType mediaType) {
            return false;
        }

        @Override
        public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
            return false;
        }
    }

    @Bean
    DefaultErrorAttributes errorAttributes() {
        return new DefaultErrorAttributes() {
            @Override
            public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
                Map<String, Object> attributes = super.getErrorAttributes(request, options);
                if (attributes.get("timestamp") instanceof Date date) {
                    SimpleDateFormat format = new SimpleDateFormat(TalkDto.SUBMITTED_AT);
                    format.setTimeZone(TimeZone.getTimeZone("UTC"));
                    attributes.put("timestamp", format.format(date));
                }
                Throwable error = getError(request);
                if (attributes.containsKey("message") && error != null) {
                    String message = spring5Message(error);
                    if (message != null) {
                        attributes.put("message", message);
                    }
                }
                if (attributes.get("errors") instanceof List<?> errors && error instanceof BindingResult result) {
                    attributes.put("errors", spring5Errors(errors, result));
                }
                return attributes;
            }
        };
    }

    /** The message Spring 5 gave these exceptions, or null to keep Boot's. */
    static String spring5Message(Throwable error) {
        if (error instanceof TypeMismatchException e) {
            String message = "Failed to convert value of type '" + ClassUtils.getDescriptiveType(e.getValue()) + "'"
                    + (e.getRequiredType() != null ? " to required type '" + ClassUtils.getQualifiedName(e.getRequiredType()) + "'" : "");
            return nested(message, e.getCause());
        }
        if (error instanceof HttpMessageNotReadableException e) {
            return nested(e.getMessage(), e.getCause());
        }
        if (error instanceof HttpMediaTypeNotSupportedException e && e.getContentType() != null) {
            return "Content type '" + e.getContentType() + "' not supported";
        }
        if (error instanceof HttpRequestMethodNotSupportedException e) {
            return "Request method '" + e.getMethod() + "' not supported";
        }
        if (error instanceof MissingServletRequestParameterException e) {
            return "Required request parameter '" + e.getParameterName() + "' for method parameter type "
                    + e.getParameterType() + " is " + (e.isMissingAfterConversion() ? "present but converted to null" : "not present");
        }
        if (error instanceof NoResourceFoundException) {
            return "No message available"; // Spring 5 answered an unknown path with a bare 404
        }
        return null;
    }

    private static String nested(String message, Throwable cause) {
        return cause == null ? message : message + "; nested exception is " + cause;
    }

    /** FieldError and ObjectError as Jackson 2 serialised them, the fields in the form's declaration order. */
    private static List<Object> spring5Errors(List<?> errors, BindingResult result) {
        List<String> declared = result.getTarget() == null ? List.of()
                : Arrays.stream(result.getTarget().getClass().getDeclaredFields()).map(Field::getName).toList();
        List<Object> sorted = new ArrayList<>(errors);
        sorted.sort(Comparator.comparingInt(e -> e instanceof FieldError f && declared.contains(f.getField())
                ? declared.indexOf(f.getField()) : declared.size()));
        List<Object> out = new ArrayList<>();
        for (Object e : sorted) {
            if (!(e instanceof ObjectError error)) {
                out.add(e);
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("codes", error.getCodes());
            m.put("arguments", error.getArguments());
            m.put("defaultMessage", error.getDefaultMessage());
            m.put("objectName", error.getObjectName());
            if (error instanceof FieldError f) {
                m.put("field", f.getField());
                m.put("rejectedValue", f.getRejectedValue());
                m.put("bindingFailure", f.isBindingFailure());
            }
            m.put("code", error.getCode());
            out.add(m);
        }
        return out;
    }
}
