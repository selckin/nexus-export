package be.selckin.nexus.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgressTest {

    @Test
    void runWithProgressEnabledStillExports(@TempDir Path out) throws Exception {
        String helloSha1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";
        Asset a = new Asset("1", "com/x/a/1.0/a-1.0.jar", "http://fake/a.jar",
                new Checksum(helloSha1, "5d41402abc4b2a76b9719d911017c592", null), 5);
        FakeNexusClient client = new FakeNexusClient(
                List.of(new Repository("releases", "maven2", "hosted", "http://fake/releases")),
                Map.of("releases", List.of(a)),
                Map.of("http://fake/a.jar", "hello".getBytes(StandardCharsets.UTF_8)));

        int code = new ExportRunner(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .run(client, List.of("releases"), out, false, false, 2, 1);

        assertEquals(0, code);
        assertEquals("hello", Files.readString(out.resolve("releases/com/x/a/1.0/a-1.0.jar")));
    }

    @Test
    void progressIntervalDefaultsTo10() {
        Main m = new Main();
        new CommandLine(m).parseArgs();
        assertEquals(10, m.progressInterval);
    }

    @Test
    void progressIntervalParsesCustomAndZero() {
        Main custom = new Main();
        new CommandLine(custom).parseArgs("--progress-interval", "5");
        assertEquals(5, custom.progressInterval);

        Main off = new Main();
        new CommandLine(off).parseArgs("--progress-interval", "0");
        assertEquals(0, off.progressInterval);
    }

    @Test
    void formatsProgressLine() {
        String line = ExportRunner.formatProgress("releases", 1234, 56, 0, 245_300_000L, 30_000L);
        assertEquals(
                "progress: repo=releases downloaded=1234 skipped=56 failed=0, 245.3 MB (8.2 MB/s), elapsed 00:30",
                line);
    }

    @Test
    void formatsZeroElapsedWithoutDivByZero() {
        String line = ExportRunner.formatProgress("snapshots", 0, 0, 2, 0L, 0L);
        assertEquals(
                "progress: repo=snapshots downloaded=0 skipped=0 failed=2, 0.0 MB (0.0 MB/s), elapsed 00:00",
                line);
    }

    @Test
    void formatsElapsedOverAMinute() {
        String line = ExportRunner.formatProgress("thirdparty", 10, 0, 0, 1_000_000L, 125_000L);
        assertEquals(
                "progress: repo=thirdparty downloaded=10 skipped=0 failed=0, 1.0 MB (0.0 MB/s), elapsed 02:05",
                line);
    }
}
