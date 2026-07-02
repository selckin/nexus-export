package be.selckin.nexus.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoExporterTest {

    // SHA-1/MD5 of the literal bytes "hello"
    private static final String HELLO_SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";
    private static final String HELLO_MD5 = "5d41402abc4b2a76b9719d911017c592";
    private static final Executor SAME_THREAD = Runnable::run;

    private FakeNexusClient clientWithHello() {
        Asset a = new Asset("1", "com/x/a/1.0/a-1.0.jar", "http://fake/a.jar",
                new Checksum(HELLO_SHA1, HELLO_MD5, null), 5);
        return new FakeNexusClient(
                List.of(new Repository("releases", "maven2", "hosted", "http://fake/releases")),
                Map.of("releases", List.of(a)),
                Map.of("http://fake/a.jar", "hello".getBytes(UTF_8)));
    }

    @Test
    void downloadsVerifiesAndWritesSidecars(@TempDir Path out) throws Exception {
        ExportReport report = new ExportReport();
        FakeNexusClient client = clientWithHello();

        new RepoExporter(client, out, SAME_THREAD, false, report).export("releases");

        Path jar = out.resolve("releases/com/x/a/1.0/a-1.0.jar");
        assertEquals("hello", Files.readString(jar));
        assertEquals(HELLO_SHA1, Files.readString(out.resolve("releases/com/x/a/1.0/a-1.0.jar.sha1")));
        assertEquals(HELLO_MD5, Files.readString(out.resolve("releases/com/x/a/1.0/a-1.0.jar.md5")));
        assertEquals(1, report.totalDownloaded());
        assertFalse(report.hasFailures());
    }

    @Test
    void skipsWhenSha1Matches(@TempDir Path out) throws Exception {
        ExportReport report = new ExportReport();
        FakeNexusClient client = clientWithHello();
        Path jar = out.resolve("releases/com/x/a/1.0/a-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, "hello".getBytes(UTF_8));

        new RepoExporter(client, out, SAME_THREAD, false, report).export("releases");

        assertEquals(0, client.downloadCount());
        assertEquals(1, report.totalSkipped());
        assertEquals(0, report.totalDownloaded());
    }

    @Test
    void redownloadsWhenSha1Mismatches(@TempDir Path out) throws Exception {
        ExportReport report = new ExportReport();
        FakeNexusClient client = clientWithHello();
        Path jar = out.resolve("releases/com/x/a/1.0/a-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, "stale".getBytes(UTF_8));

        new RepoExporter(client, out, SAME_THREAD, false, report).export("releases");

        assertEquals(1, client.downloadCount());
        assertEquals("hello", Files.readString(jar));
        assertEquals(1, report.totalDownloaded());
    }

    // ---- checksum mismatch on a fresh download ----

    private FakeNexusClient clientWithWrongChecksum() {
        // recorded sha1/md5 are bogus; the server actually serves "hello"
        Asset a = new Asset("1", "com/x/a/1.0/a-1.0.jar", "http://fake/a.jar",
                new Checksum("0000000000000000000000000000000000000000", "ffffffffffffffffffffffffffffffff", null), 5);
        return new FakeNexusClient(
                List.of(new Repository("releases", "maven2", "hosted", "http://fake/releases")),
                Map.of("releases", List.of(a)),
                Map.of("http://fake/a.jar", "hello".getBytes(UTF_8)));
    }

    @Test
    void failsWhenFreshDownloadMismatchesAndVerifyEnabled(@TempDir Path out) {
        ExportReport report = new ExportReport();

        // default (verify on): a source-vs-download mismatch fails the asset and writes nothing
        new RepoExporter(clientWithWrongChecksum(), out, SAME_THREAD, false, report).export("releases");

        assertFalse(Files.exists(out.resolve("releases/com/x/a/1.0/a-1.0.jar")));
        assertTrue(report.hasFailures());
        assertEquals(1, report.totalFailed());
        assertEquals(0, report.totalDownloaded());
    }

    @Test
    void keepsFileOnChecksumMismatchWhenVerifyDisabled(@TempDir Path out) throws Exception {
        ExportReport report = new ExportReport();

        // --no-verify-checksums: keep the downloaded bytes despite the wrong source checksum
        new RepoExporter(clientWithWrongChecksum(), out, SAME_THREAD, false, report, false).export("releases");

        Path jar = out.resolve("releases/com/x/a/1.0/a-1.0.jar");
        assertEquals("hello", Files.readString(jar));
        // sidecars must record the ACTUAL bytes' checksums, not the bogus source ones,
        // so the exported tree stays self-consistent
        assertEquals(HELLO_SHA1, Files.readString(out.resolve("releases/com/x/a/1.0/a-1.0.jar.sha1")));
        assertEquals(HELLO_MD5, Files.readString(out.resolve("releases/com/x/a/1.0/a-1.0.jar.md5")));
        assertEquals(1, report.totalDownloaded());
        assertEquals(1, report.totalKeptMismatch());
        assertFalse(report.hasFailures());
    }

    @Test
    void dryRunWritesNothingButCounts(@TempDir Path out) {
        ExportReport report = new ExportReport();
        FakeNexusClient client = clientWithHello();

        new RepoExporter(client, out, SAME_THREAD, true, report).export("releases");

        assertEquals(0, client.downloadCount());
        assertFalse(Files.exists(out.resolve("releases/com/x/a/1.0/a-1.0.jar")));
        assertEquals(1, report.totalDownloaded()); // counted as "would download"
    }

    @Test
    void exportFollowsPaginationAcrossPages(@TempDir Path out) throws Exception {
        ExportReport report = new ExportReport();

        new RepoExporter(new MultiPageFakeNexusClient(), out, SAME_THREAD, false, report)
                .export("releases");

        Path jarA = out.resolve("releases/com/x/a/1.0/a-1.0.jar");
        Path jarB = out.resolve("releases/com/x/b/1.0/b-1.0.jar");
        assertEquals("hello", Files.readString(jarA));
        assertEquals("hello", Files.readString(jarB));
        assertEquals(2, report.totalDownloaded());
        assertFalse(report.hasFailures());
    }

    @Test
    void listingExceptionOnPrefetchedPagePropagatesUnwrapped(@TempDir Path out) {
        // Page 1 lists one good asset (token "P2"); the prefetched page-2 listing throws.
        NexusClient secondPageFails = new NexusClient() {
            @Override
            public List<Repository> listRepositories() {
                return List.of(new Repository("releases", "maven2", "hosted", "http://fake/releases"));
            }

            @Override
            public AssetPage listAssets(String repository, String continuationToken) {
                if ("P2".equals(continuationToken)) {
                    throw new NexusException("HTTP 401");
                }
                Asset a = new Asset("1", "com/x/a/1.0/a-1.0.jar", "http://fake/a.jar",
                        new Checksum(HELLO_SHA1, HELLO_MD5, null), 5);
                return new AssetPage(List.of(a), "P2");
            }

            @Override
            public void download(String url, Path dest) throws IOException {
                Files.write(dest, "hello".getBytes(UTF_8));
            }
        };

        // The prefetched page's failure must surface as NexusException (unwrapped from the
        // CompletableFuture's CompletionException), not leak a CompletionException.
        ExportReport report = new ExportReport();
        assertThrows(NexusException.class,
                () -> new RepoExporter(secondPageFails, out, SAME_THREAD, false, report).export("releases"));

        // And ExportRunner still maps it to fatal exit 2.
        int code = new ExportRunner(new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
                .run(secondPageFails, List.of("releases"), out, false, false, 1, 0);
        assertEquals(2, code);
    }

    @Test
    void recordsFailureOnDownloadError(@TempDir Path out) {
        ExportReport report = new ExportReport();
        Asset missing = new Asset("9", "com/x/missing/1.0/missing-1.0.jar",
                "http://fake/does-not-exist.jar", new Checksum("deadbeef", null, null), 0);
        FakeNexusClient client = new FakeNexusClient(
                List.of(new Repository("releases", "maven2", "hosted", "http://fake/releases")),
                Map.of("releases", List.of(missing)),
                Map.of());

        new RepoExporter(client, out, SAME_THREAD, false, report).export("releases");

        assertTrue(report.hasFailures());
        assertEquals(1, report.totalFailed());
    }

    // ---- auth failure during download is fatal ----

    @Test
    void authFailureDuringDownloadIsFatalAndExits2(@TempDir Path out) {
        NexusClient authFailClient = new NexusClient() {
            @Override
            public List<Repository> listRepositories() {
                return List.of(new Repository("releases", "maven2", "hosted", "http://fake/releases"));
            }

            @Override
            public AssetPage listAssets(String repository, String continuationToken) {
                Asset a = new Asset("1", "com/x/a/1.0/a-1.0.jar", "http://fake/a.jar",
                        new Checksum(HELLO_SHA1, HELLO_MD5, null), 5);
                return new AssetPage(List.of(a), null);
            }

            @Override
            public void download(String url, Path dest) {
                throw new NexusException("HTTP 401");
            }
        };

        // Direct: export() propagates as NexusException, not a per-asset failure
        ExportReport report = new ExportReport();
        assertThrows(NexusException.class,
                () -> new RepoExporter(authFailClient, out, SAME_THREAD, false, report).export("releases"));
        assertFalse(report.hasFailures(), "auth failure must not be recorded as a per-asset failure");

        // Via runner: exit code 2 (fatal)
        int code = new ExportRunner(new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
                .run(authFailClient, List.of("releases"), out, false, false, 1, 0);
        assertEquals(2, code);
    }

    // ---- null checksum — resume + self-describing sidecars ----

    @Test
    void nullChecksumAssetWritesSidecarsAndResumes(@TempDir Path out) throws Exception {
        Asset noChecksum = new Asset("1", "com/x/a/1.0/a-1.0.jar", "http://fake/a.jar", null, 5);
        FakeNexusClient client = new FakeNexusClient(
                List.of(new Repository("releases", "maven2", "hosted", "http://fake/releases")),
                Map.of("releases", List.of(noChecksum)),
                Map.of("http://fake/a.jar", "hello".getBytes(UTF_8)));

        ExportReport report = new ExportReport();
        new RepoExporter(client, out, SAME_THREAD, false, report).export("releases");

        Path jar = out.resolve("releases/com/x/a/1.0/a-1.0.jar");
        assertEquals("hello", Files.readString(jar));
        // Sidecars computed from downloaded content
        Path sha1File = out.resolve("releases/com/x/a/1.0/a-1.0.jar.sha1");
        Path md5File = out.resolve("releases/com/x/a/1.0/a-1.0.jar.md5");
        assertTrue(Files.exists(sha1File), ".sha1 sidecar must be written");
        assertTrue(Files.exists(md5File), ".md5 sidecar must be written");
        assertEquals(HELLO_SHA1, Files.readString(sha1File));
        assertEquals(HELLO_MD5, Files.readString(md5File));
        assertEquals(1, report.totalDownloaded());

        // Second run: file present, no recorded checksum → skip (resume signal)
        int countBefore = client.downloadCount();
        ExportReport report2 = new ExportReport();
        new RepoExporter(client, out, SAME_THREAD, false, report2).export("releases");
        assertEquals(countBefore, client.downloadCount(), "second run must not re-download");
        assertEquals(1, report2.totalSkipped());
    }

    // ---- dry-run must not write sidecars ----

    @Test
    void dryRunDoesNotWriteSidecarsForExistingFile(@TempDir Path out) throws Exception {
        ExportReport report = new ExportReport();
        FakeNexusClient client = clientWithHello();
        Path jar = out.resolve("releases/com/x/a/1.0/a-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, "hello".getBytes(UTF_8));
        // No sidecars initially

        new RepoExporter(client, out, SAME_THREAD, true, report).export("releases");

        assertEquals(0, client.downloadCount());
        assertFalse(Files.exists(out.resolve("releases/com/x/a/1.0/a-1.0.jar.sha1")),
                "dry-run must not create .sha1 sidecar");
        assertFalse(Files.exists(out.resolve("releases/com/x/a/1.0/a-1.0.jar.md5")),
                "dry-run must not create .md5 sidecar");
        assertEquals(1, report.totalSkipped());
    }

    // ---- sidecar write failure on skip path must not mark file failed ----

    @Test
    void skipPathSidecarWriteFailureCountsAsSkipped(@TempDir Path out) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("user.name", "").equals("root"),
                "skip on root: chmod has no effect");

        ExportReport report = new ExportReport();
        FakeNexusClient client = clientWithHello();
        Path dir = out.resolve("releases/com/x/a/1.0");
        Path jar = dir.resolve("a-1.0.jar");
        Files.createDirectories(dir);
        Files.write(jar, "hello".getBytes(UTF_8));
        // Make directory read-only so sidecar write fails
        dir.toFile().setReadOnly();
        try {
            new RepoExporter(client, out, SAME_THREAD, false, report).export("releases");
        } finally {
            dir.toFile().setWritable(true);
        }

        assertEquals(0, client.downloadCount());
        assertEquals(1, report.totalSkipped());
        assertFalse(report.hasFailures(), "sidecar write failure on skip path must not mark file as failed");
    }

    // ---- null page.items() must not NPE ----

    @Test
    void nullPageItemsCompletesWithoutDownloads(@TempDir Path out) {
        ExportReport report = new ExportReport();
        NexusClient nullItemsClient = new NexusClient() {
            @Override
            public List<Repository> listRepositories() {
                return List.of();
            }

            @Override
            public AssetPage listAssets(String repository, String continuationToken) {
                return new AssetPage(null, null);
            }

            @Override
            public void download(String url, Path dest) {
                throw new UnsupportedOperationException();
            }
        };

        new RepoExporter(nullItemsClient, out, SAME_THREAD, false, report).export("releases");

        assertEquals(0, report.totalDownloaded());
        assertFalse(report.hasFailures());
    }

    /** Hand-rolled two-page fake: page 1 → token "P2", page 2 → null. Both assets contain "hello". */
    private static final class MultiPageFakeNexusClient implements NexusClient {

        private static final byte[] BODY = "hello".getBytes(UTF_8);

        @Override
        public List<Repository> listRepositories() {
            return List.of();
        }

        @Override
        public AssetPage listAssets(String repository, String continuationToken) {
            if ("P2".equals(continuationToken)) {
                Asset b = new Asset("2", "com/x/b/1.0/b-1.0.jar", "http://fake/b.jar",
                        new Checksum(HELLO_SHA1, null, null), 5);
                return new AssetPage(List.of(b), null);
            }
            Asset a = new Asset("1", "com/x/a/1.0/a-1.0.jar", "http://fake/a.jar",
                    new Checksum(HELLO_SHA1, null, null), 5);
            return new AssetPage(List.of(a), "P2");
        }

        @Override
        public void download(String url, Path dest) throws IOException {
            Files.write(dest, BODY);
        }
    }
}
