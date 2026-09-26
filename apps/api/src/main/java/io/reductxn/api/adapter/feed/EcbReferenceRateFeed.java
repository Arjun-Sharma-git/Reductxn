package io.reductxn.api.adapter.feed;

import io.reductxn.fx.api.FxRateFeed;
import io.reductxn.fx.api.PublishedRates;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * The ECB's daily reference rates, fetched from wherever the deployment keeps them.
 *
 * <p><strong>One location, which may be a URL or a path.</strong> A deployment that reaches the bank directly
 * configures its URL; one that archives what it fetched, or runs with no outbound access, configures a file and
 * ingests what an ops process drops there. Both end in the same parser, so the file form is not a second code
 * path - it is the same path with the bytes read from disk, which is also what makes the ingestion testable
 * without a network.
 *
 * <p><strong>Timeouts, because this runs in a scheduled slot.</strong> A fetch with no read timeout is a thread
 * that never comes back: the job would report nothing, then overlap itself, and the deployment would have no
 * rates and no complaint. Five seconds to connect and thirty to answer are not policy about the market; they are
 * the smallest statement that a fetch must end.
 *
 * <p><strong>Absent and broken are different.</strong> A blank document is empty (the provider has published
 * nothing readable yet) and the caller does nothing. A missing file, an unreachable host and a non-2xx answer
 * all throw, because they are faults about the deployment rather than facts about the market - and a refresh
 * that turned them into "nothing to do" would leave a deployment with no rates and a log that looks calm.
 *
 * <p>The bean exists only when the deployment configured a location
 * ({@code reductxn.rates.feed}). Nothing else depends on it - the ingestion use case takes a feed as an
 * argument - so a deployment with no feed starts normally, ingests nothing, and every cross-currency request
 * fails closed.
 */
@Component
@ConditionalOnProperty(name = EcbReferenceRateFeed.LOCATION)
public class EcbReferenceRateFeed implements FxRateFeed {

    /** Where the feed is: an {@code https} URL, or a path to a document already fetched. */
    public static final String LOCATION = "reductxn.rates.feed";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);

    private final String location;

    public EcbReferenceRateFeed(@Value("${" + LOCATION + ":}") String location) {
        if (location.isBlank()) {
            throw new IllegalStateException(
                    LOCATION + " is set but empty. It must be the URL of the provider's document, or the path of "
                            + "one an ops process already fetched. Leave it unset to run without rate ingestion - "
                            + "which leaves every cross-currency request refused with fx.rate_unavailable.");
        }

        this.location = location;
    }

    @Override
    public Optional<PublishedRates> latest() {
        String document = document();

        return document.isBlank() ? Optional.empty() : Optional.of(EcbReferenceRates.parse(document));
    }

    @Override
    public String name() {
        return EcbReferenceRates.PROVIDER;
    }

    /** The document, from the URL or from the file, or a fault saying which of the two failed. */
    private String document() {
        return isUrl() ? fetched() : read();
    }

    private boolean isUrl() {
        return location.startsWith("https://") || location.startsWith("http://");
    }

    private String read() {
        try {
            return Files.readString(Path.of(location));
        } catch (IOException unreadable) {
            throw new IllegalStateException(
                    "the configured rate feed " + location + " could not be read: " + unreadable.getMessage(),
                    unreadable);
        }
    }

    private String fetched() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(location))
                .timeout(ANSWER_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new IllegalStateException("the rate feed " + location + " answered "
                        + response.statusCode() + "; the ingestion wrote nothing");
            }

            return response.body();
        } catch (IOException unreachable) {
            throw new IllegalStateException(
                    "the rate feed " + location + " could not be reached: " + unreachable.getMessage(),
                    unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "the rate feed " + location + " was interrupted before it answered", interrupted);
        }
    }
}
