package be.selckin.nexus.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
