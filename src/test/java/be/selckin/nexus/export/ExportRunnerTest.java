package be.selckin.nexus.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportRunnerTest {

    private static final String HELLO_SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";

    private FakeNexusClient client() {
        Asset a = new Asset("1", "com/x/a/1.0/a-1.0.jar", "http://fake/a.jar",
                new Checksum(HELLO_SHA1, "5d41402abc4b2a76b9719d911017c592", null), 5);
        return new FakeNexusClient(
                List.of(new Repository("releases", "maven2", "hosted", "http://fake/releases"),
                        new Repository("npm-private", "npm", "hosted", "http://fake/npm")),
                Map.of("releases", List.of(a)),
                Map.of("http://fake/a.jar", "hello".getBytes(UTF_8)));
    }

    @Test
    void listPrintsMaven2ReposAndExitsZero() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = new ExportRunner(new PrintStream(buf, true, UTF_8))
                .run(client(), List.of(), Path.of("/unused"), true, false, 2);
        assertEquals(0, code);
        String printed = buf.toString(UTF_8);
        assertTrue(printed.contains("releases"));
        assertTrue(printed.contains("npm-private"));
    }

    @Test
    void exportWritesFilesAndExitsZero(@TempDir Path out) throws Exception {
        int code = new ExportRunner(new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
                .run(client(), List.of("releases"), out, false, false, 2);
        assertEquals(0, code);
        assertEquals("hello", Files.readString(out.resolve("releases/com/x/a/1.0/a-1.0.jar")));
    }

    @Test
    void skipsUnknownOrNonMaven2Repo(@TempDir Path out) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = new ExportRunner(new PrintStream(buf, true, UTF_8))
                .run(client(), List.of("npm-private", "does-not-exist"), out, false, false, 2);
        assertEquals(0, code);
        assertFalse(Files.exists(out.resolve("npm-private")));
        assertTrue(buf.toString(UTF_8).contains("skipping"));
    }

    @Test
    void dryRunWritesNothing(@TempDir Path out) {
        int code = new ExportRunner(new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
                .run(client(), List.of("releases"), out, false, true, 2);
        assertEquals(0, code);
        assertFalse(Files.exists(out.resolve("releases/com/x/a/1.0/a-1.0.jar")));
    }

    @Test
    void connectionFailureReturnsFatal() {
        // Hand-rolled throwing client (no mock framework)
        NexusClient throwingClient = new NexusClient() {
            @Override
            public List<Repository> listRepositories() {
                throw new UncheckedIOException(new IOException("connection refused"));
            }

            @Override
            public AssetPage listAssets(String repository, String continuationToken) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void download(String url, Path dest) {
                throw new UnsupportedOperationException();
            }
        };

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = new ExportRunner(new PrintStream(buf, true, UTF_8))
                .run(throwingClient, List.of("releases"), Path.of("/unused"), false, false, 2);
        assertEquals(2, code);
        assertTrue(buf.toString(UTF_8).contains("ERROR"));
    }
}
