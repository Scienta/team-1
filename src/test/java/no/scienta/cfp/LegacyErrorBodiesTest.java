package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * R12a, D9a: error bodies are byte for byte what Spring Boot 2.7 answered, framework-written messages included. The
 * reference bodies in src/test/resources/spring-boot-4/error-bodies-2.7.txt were recorded from the 2.7 image (Java
 * 11), timestamp masked. Every request is sent several times, so an order that only holds sometimes fails too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegacyErrorBodiesTest {

    private static final Pattern TIMESTAMP = Pattern.compile("\"timestamp\":\"[^\"]*\"");
    private static final Pattern STATUS = Pattern.compile("\"status\":(\\d{3})");
    private static final Pattern COLUMN = Pattern.compile("column: \\d+");
    private static final int TIMES = 5;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    record Case(String method, String path, String contentType, String body, String expected, boolean columnDiffers) {
        @Override
        public String toString() {
            return method + " " + path + " " + (body == null ? "" : body.length() > 60 ? body.substring(0, 60) + "…" : body);
        }
    }

    static List<Case> cases() throws IOException {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = LegacyErrorBodiesTest.class.getResourceAsStream("/spring-boot-4/error-bodies-2.7.txt")) {
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.startsWith("# ") && !l.isEmpty())
                    .toList();
            for (int i = 0; i < lines.size(); i += 3) {
                String[] head = lines.get(i).split(" ");
                String body = lines.get(i + 1);
                cases.add(new Case(head[1], head[2], head[3], body.equals("-") ? null : body, lines.get(i + 2),
                        head.length > 4 && head[4].equals("column-differs")));
            }
        }
        return cases;
    }

    // R12a, D9a: type conversion, malformed JSON, missing body or parameter, 415, 405, 404 and @Valid errors.
    @ParameterizedTest
    @MethodSource("cases")
    void theErrorBodyIsExactlyWhat27Answered(Case c) throws Exception {
        Matcher status = STATUS.matcher(c.expected());
        status.find();
        for (int i = 0; i < TIMES; i++) {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + c.path()))
                    .header("Content-Type", c.contentType())
                    .method(c.method(), c.body() == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(c.body()))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(Integer.parseInt(status.group(1)), res.statusCode(), res.body());
            assertEquals("application/json;charset=UTF-8", res.headers().firstValue("Content-Type").orElse(""));
            String actual = TIMESTAMP.matcher(res.body()).replaceFirst("\"timestamp\":\"<T>\"");
            if (c.columnDiffers()) {
                // Not identical (evidence.md): Jackson 2.21 reports another column than 2.7's Jackson 2.13.
                assertEquals(COLUMN.matcher(c.expected()).replaceAll("column: <C>"), COLUMN.matcher(actual).replaceAll("column: <C>"));
            } else {
                assertEquals(c.expected(), actual);
            }
        }
    }
}
