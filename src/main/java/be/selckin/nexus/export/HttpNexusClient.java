package be.selckin.nexus.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

public final class HttpNexusClient implements NexusClient {

    private static final Logger log = LoggerFactory.getLogger(HttpNexusClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final String baseUrl;
    private final String authHeader;
    private final int maxRetries;
    private final Duration baseBackoff;

    public HttpNexusClient(String baseUrl, String user, String password) {
        this(baseUrl, user, password, 4, Duration.ofSeconds(1));
    }

    public HttpNexusClient(String baseUrl, String user, String password, int maxRetries, Duration baseBackoff) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authHeader = user == null ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.maxRetries = maxRetries;
        this.baseBackoff = baseBackoff;
    }

    @Override
    public List<Repository> listRepositories() {
        String body = getString(baseUrl + "/service/rest/v1/repositories");
        try {
            return MAPPER.readValue(body,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Repository.class));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to parse repositories response", e);
        }
    }

    @Override
    public AssetPage listAssets(String repository, String continuationToken) {
        String url = baseUrl + "/service/rest/v1/assets?repository=" + enc(repository);
        if (continuationToken != null) {
            url += "&continuationToken=" + enc(continuationToken);
        }
        try {
            return MAPPER.readValue(getString(url), AssetPage.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to parse assets response", e);
        }
    }

    @Override
    public void download(String url, Path dest) throws IOException {
        HttpRequest req = request(url).build();
        sendWithRetry(req, HttpResponse.BodyHandlers.ofFile(dest,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
    }

    private String getString(String url) {
        try {
            return sendWithRetry(request(url).build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new UncheckedIOException("request failed: " + url, e);
        }
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET()
                .header("Accept", "application/json");
        if (authHeader != null) {
            b.header("Authorization", authHeader);
        }
        return b;
    }

    private <T> HttpResponse<T> sendWithRetry(HttpRequest req, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            HttpResponse<T> resp;
            try {
                resp = http.send(req, handler);
            } catch (IOException e) {
                last = e;
                log.warn("request error for {} (attempt {}/{}): {}", req.uri(), attempt, maxRetries, e.toString());
                if (attempt < maxRetries) sleepBackoff(attempt);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while requesting " + req.uri(), e);
            }
            int s = resp.statusCode();
            if (s == 401 || s == 403) {
                throw new NexusException("authentication failed (HTTP " + s + ") for " + req.uri());
            }
            if (s == 429 || s >= 500) {
                last = new IOException("HTTP " + s + " for " + req.uri());
                log.warn("retryable HTTP {} for {} (attempt {}/{})", s, req.uri(), attempt, maxRetries);
                if (attempt < maxRetries) sleepBackoff(attempt);
                continue;
            }
            if (s >= 300) {
                throw new IOException("HTTP " + s + " for " + req.uri());
            }
            return resp;
        }
        throw last != null ? last : new IOException("request failed: " + req.uri());
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(baseBackoff.toMillis() * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
