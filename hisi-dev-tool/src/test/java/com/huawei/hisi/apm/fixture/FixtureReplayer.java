package com.huawei.hisi.apm.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

/**
 * Static helpers to load the P0 OTLP/JSON span fixtures from the test
 * classpath and (optionally) POST them at the running OTLP receiver.
 *
 * <p>Used by the Failure-Locator E2E test (Task 13) to drive
 * {@code OtlpReceiverController} → {@code SpanIngestionService} →
 * {@code ExceptionSpanIndex} → {@code FailureLocatorService} without
 * requiring a live target JVM.
 *
 * <p>Discovered receiver path:
 * {@code POST /v1/traces} (the JSON overload of
 * {@code com.huawei.hisi.apm.controller.OtlpReceiverController}).
 */
public final class FixtureReplayer {

    /** Names of the canned P0 fixtures available on the classpath. */
    private static final List<String> FIXTURE_NAMES =
            List.of("npe", "sql-fail", "http-5xx");

    /** Default base URL of the OTLP receiver when the host runs locally. */
    public static final String DEFAULT_RECEIVER_URL =
            "http://localhost:8080/v1/traces";

    private FixtureReplayer() {
        // utility class — no instances
    }

    /**
     * Load a fixture by short name from {@code apm/fixtures/<name>.json}
     * on the test classpath.
     *
     * @param name one of {@link #listFixtures()}
     * @return raw OTLP/JSON payload as a UTF-8 string
     * @throws IllegalArgumentException if {@code name} is unknown
     * @throws UncheckedIOException     on read failure
     */
    public static String loadFixture(String name) {
        Objects.requireNonNull(name, "fixture name must not be null");
        if (!FIXTURE_NAMES.contains(name)) {
            throw new IllegalArgumentException(
                    "Unknown fixture: " + name + ". Available: " + FIXTURE_NAMES);
        }
        String resource = "apm/fixtures/" + name + ".json";
        ClassLoader cl = FixtureReplayer.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read fixture: " + resource, e);
        }
    }

    /**
     * POST a fixture payload to the OTLP receiver as
     * {@code application/json}. Uses a plain {@link RestTemplate}; callers
     * are responsible for the receiver being up.
     *
     * @param url         full receiver URL (e.g. {@link #DEFAULT_RECEIVER_URL})
     * @param fixtureJson OTLP/JSON payload (from {@link #loadFixture(String)})
     */
    public static void postToReceiver(String url, String fixtureJson) {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(fixtureJson, "fixtureJson must not be null");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(fixtureJson, headers);
        new RestTemplate().postForEntity(url, entity, Void.class);
    }

    /** @return the canonical list of P0 fixture names. */
    public static List<String> listFixtures() {
        return FIXTURE_NAMES;
    }
}
