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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
                .run(client(), List.of(), Path.of("/unused"), true, false, 2, 0);
        assertEquals(0, code);
        String printed = buf.toString(UTF_8);
        assertTrue(printed.contains("releases"));
        assertFalse(printed.contains("npm-private"));
    }

    @Test
    void exportWritesFilesAndExitsZero(@TempDir Path out) throws Exception {
        int code = new ExportRunner(new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
                .run(client(), List.of("releases"), out, false, false, 2, 0);
        assertEquals(0, code);
        assertEquals("hello", Files.readString(out.resolve("releases/com/x/a/1.0/a-1.0.jar")));
    }

    @Test
    void skipsUnknownOrNonMaven2Repo(@TempDir Path out) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = new ExportRunner(new PrintStream(buf, true, UTF_8))
                .run(client(), List.of("npm-private", "does-not-exist"), out, false, false, 2, 0);
        // all requested repos are non-maven2 → exit 2, not 0
        assertEquals(2, code);
        assertFalse(Files.exists(out.resolve("npm-private")));
        assertTrue(buf.toString(UTF_8).contains("skipping"));
        assertTrue(buf.toString(UTF_8).contains("ERROR"));
    }

    @Test
    void someValidSomeInvalidStillExports(@TempDir Path out) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = new ExportRunner(new PrintStream(buf, true, UTF_8))
                .run(client(), List.of("releases", "does-not-exist"), out, false, false, 2, 0);
        // One valid repo matched → normal 0 outcome
        assertEquals(0, code);
        assertEquals("hello", Files.readString(out.resolve("releases/com/x/a/1.0/a-1.0.jar")));
        assertTrue(buf.toString(UTF_8).contains("skipping"));
    }

    @Test
    void partialExportBeforeFatalPrintsReport(@TempDir Path out) throws IOException {
        // Client: repoA exports one asset successfully, then repoB's listAssets throws NexusException
        NexusClient twoRepoClient = new NexusClient() {
            @Override
            public List<Repository> listRepositories() {
                return List.of(
                        new Repository("repoA", "maven2", "hosted", "http://fake/a"),
                        new Repository("repoB", "maven2", "hosted", "http://fake/b"));
            }

            @Override
            public AssetPage listAssets(String repository, String continuationToken) {
                if ("repoB".equals(repository)) {
                    throw new NexusException("HTTP 401");
                }
                Asset a = new Asset("1", "com/x/a/1.0/a-1.0.jar", "http://fake/a.jar",
                        new Checksum(HELLO_SHA1, "5d41402abc4b2a76b9719d911017c592", null), 5);
                return new AssetPage(List.of(a), null);
            }

            @Override
            public void download(String url, Path dest) throws IOException {
                Files.write(dest, "hello".getBytes(UTF_8));
            }
        };

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = new ExportRunner(new PrintStream(buf, true, UTF_8))
                .run(twoRepoClient, List.of("repoA", "repoB"), out, false, false, 1, 0);
        assertEquals(2, code);
        String output = buf.toString(UTF_8);
        assertTrue(output.contains("ERROR"));
        // report printed even on fatal — should show repoA's download
        assertTrue(output.contains("repoA"), "output should contain repoA tally: " + output);
    }

    @Test
    void mismatchFailsByDefaultButNoVerifyKeepsFile(@TempDir Path out) throws Exception {
        // recorded sha1/md5 are bogus; the server actually serves "hello"
        Asset wrong = new Asset("1", "com/x/a/1.0/a-1.0.jar", "http://fake/a.jar",
                new Checksum("0000000000000000000000000000000000000000", "ffffffffffffffffffffffffffffffff", null), 5);
        FakeNexusClient client = new FakeNexusClient(
                List.of(new Repository("releases", "maven2", "hosted", "http://fake/releases")),
                Map.of("releases", List.of(wrong)),
                Map.of("http://fake/a.jar", "hello".getBytes(UTF_8)));
        Path jar = out.resolve("releases/com/x/a/1.0/a-1.0.jar");

        // default (verify on): mismatch fails the asset → exit 1, nothing written
        int strict = new ExportRunner(new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
                .run(client, List.of("releases"), out, false, false, 2, 0);
        assertEquals(1, strict);
        assertFalse(Files.exists(jar));

        // verify off: keep the bytes despite the wrong source checksum → exit 0
        int lenient = new ExportRunner(new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
                .run(client, List.of("releases"), out, false, false, 2, 0, false);
        assertEquals(0, lenient);
        assertEquals("hello", Files.readString(jar));
    }

    @Test
    void dryRunWritesNothing(@TempDir Path out) {
        int code = new ExportRunner(new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
                .run(client(), List.of("releases"), out, false, true, 2, 0);
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
                .run(throwingClient, List.of("releases"), Path.of("/unused"), false, false, 2, 0);
        assertEquals(2, code);
        assertTrue(buf.toString(UTF_8).contains("ERROR"));
    }
}
