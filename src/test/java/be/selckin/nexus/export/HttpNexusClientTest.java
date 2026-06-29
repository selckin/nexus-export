package be.selckin.nexus.export;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import java.io.UncheckedIOException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpNexusClientTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private HttpNexusClient client() {
        return new HttpNexusClient(base, "admin", "secret", 4, Duration.ofMillis(5));
    }

    @Test
    void listRepositoriesParsesAndSendsBasicAuth() {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        server.createContext("/service/rest/v1/repositories", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            send(ex, 200, ("[{\"name\":\"releases\",\"format\":\"maven2\",\"type\":\"hosted\","
                    + "\"url\":\"" + base + "/repository/releases\"}]").getBytes(UTF_8));
        });
        server.start();

        List<Repository> repos = client().listRepositories();

        assertEquals(1, repos.size());
        assertEquals("releases", repos.get(0).name());
        assertTrue(seenAuth.get() != null && seenAuth.get().startsWith("Basic "));
    }

    @Test
    void listAssetsFollowsContinuationToken() {
        server.createContext("/service/rest/v1/assets", ex -> {
            String q = ex.getRequestURI().getQuery();
            if (q != null && q.contains("continuationToken=TOK2")) {
                send(ex, 200, ("{\"items\":[{\"id\":\"2\",\"path\":\"b.jar\",\"downloadUrl\":\""
                        + base + "/b.jar\",\"fileSize\":2,\"checksum\":{\"sha1\":\"x\"}}],"
                        + "\"continuationToken\":null}").getBytes(UTF_8));
            } else {
                send(ex, 200, ("{\"items\":[{\"id\":\"1\",\"path\":\"a.jar\",\"downloadUrl\":\""
                        + base + "/a.jar\",\"fileSize\":1,\"checksum\":{\"sha1\":\"y\"}}],"
                        + "\"continuationToken\":\"TOK2\"}").getBytes(UTF_8));
            }
        });
        server.start();

        HttpNexusClient c = client();
        AssetPage p1 = c.listAssets("releases", null);
        assertEquals("TOK2", p1.continuationToken());
        assertEquals("a.jar", p1.items().get(0).path());

        AssetPage p2 = c.listAssets("releases", p1.continuationToken());
        assertNull(p2.continuationToken());
        assertEquals("b.jar", p2.items().get(0).path());
    }

    @Test
    void downloadWritesBody() throws IOException {
        server.createContext("/file.jar", ex -> send(ex, 200, "hello".getBytes(UTF_8)));
        server.start();

        Path dest = Files.createTempFile("dl", ".jar");
        client().download(base + "/file.jar", dest);
        assertEquals("hello", Files.readString(dest));
    }

    @Test
    void retriesOn503ThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/service/rest/v1/repositories", ex -> {
            if (calls.incrementAndGet() == 1) {
                send(ex, 503, "busy".getBytes(UTF_8));
            } else {
                send(ex, 200, "[]".getBytes(UTF_8));
            }
        });
        server.start();

        List<Repository> repos = client().listRepositories();
        assertEquals(0, repos.size());
        assertEquals(2, calls.get());
    }

    @Test
    void unauthorizedThrowsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/service/rest/v1/repositories", ex -> {
            calls.incrementAndGet();
            send(ex, 401, "nope".getBytes(UTF_8));
        });
        server.start();

        assertThrows(NexusException.class, () -> client().listRepositories());
        assertEquals(1, calls.get());
    }

    @Test
    void nonRetriable4xxThrowsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/service/rest/v1/repositories", ex -> {
            calls.incrementAndGet();
            send(ex, 404, "not found".getBytes(UTF_8));
        });
        server.start();

        // listRepositories() wraps IOException in UncheckedIOException; verify it is NOT NexusException
        assertThrows(UncheckedIOException.class, () -> client().listRepositories());
        assertEquals(1, calls.get());
    }

    @Test
    void downloadFollowsRedirect() throws IOException {
        server.createContext("/redir", ex -> {
            ex.getResponseHeaders().set("Location", base + "/final");
            ex.sendResponseHeaders(302, -1);
            ex.getResponseBody().close();
        });
        server.createContext("/final", ex -> send(ex, 200, "ok".getBytes(UTF_8)));
        server.start();

        Path dest = Files.createTempFile("redir", ".txt");
        client().download(base + "/redir", dest);
        assertEquals("ok", Files.readString(dest));
    }

    @Test
    void anonymousClientSendsNoAuthHeader() {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        server.createContext("/service/rest/v1/repositories", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            send(ex, 200, "[]".getBytes(UTF_8));
        });
        server.start();

        new HttpNexusClient(base, null, null, 4, Duration.ofMillis(5)).listRepositories();

        assertNull(seenAuth.get());
    }
}
