package app.perroute.service;

import app.perroute.parser.ParseResult;
import app.perroute.parser.SubscriptionParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class SubscriptionService {
    private static final int MAX_SUBSCRIPTION_BYTES = 10 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final SubscriptionParser parser = new SubscriptionParser();

    public ParseResult fetch(String url) throws IOException, InterruptedException {
        URI uri;
        try { uri = URI.create(url.strip()); }
        catch (RuntimeException e) { throw new IOException("Subscription URL is invalid", e); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Subscription URL must use HTTPS or HTTP");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "PerRoute/0.1")
                .header("Accept", "text/plain, application/octet-stream, */*")
                .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            int status = response.statusCode();
            if (status == 404) {
                throw new IOException("Subscription link returned HTTP 404. The link is invalid, expired, or has been revoked by the provider.");
            }
            if (status == 401 || status == 403) {
                throw new IOException("Subscription access was rejected (HTTP " + status + "). The link may be expired or unauthorized.");
            }
            throw new IOException("Subscription server returned HTTP " + status);
        }
        if (response.body().length > MAX_SUBSCRIPTION_BYTES) throw new IOException("Subscription is larger than 10 MB");
        return parser.parse(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
    }

    public ParseResult parsePasted(String content) {
        return parser.parse(content);
    }
}
