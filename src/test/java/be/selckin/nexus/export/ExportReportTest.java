package be.selckin.nexus.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportReportTest {

    @Test
    void talliesAcrossRepos() {
        ExportReport r = new ExportReport();
        r.downloaded("releases", 100);
        r.downloaded("releases", 50);
        r.skipped("releases");
        r.downloaded("snapshots", 10);

        assertEquals(3, r.totalDownloaded());
        assertEquals(1, r.totalSkipped());
        assertEquals(0, r.totalFailed());
        assertFalse(r.hasFailures());
    }

    @Test
    void keptMismatchIsTalliedButNotAFailure() {
        ExportReport r = new ExportReport();
        r.downloaded("releases", 100);
        r.keptMismatch("releases", "com/x/a/1.0/a-1.0.jar");

        // the file was still downloaded; a kept mismatch is an additional audit tally, not a failure
        assertEquals(1, r.totalDownloaded());
        assertEquals(1, r.totalKeptMismatch());
        assertEquals(0, r.totalFailed());
        assertFalse(r.hasFailures());
    }

    @Test
    void renderListsKeptMismatchCountAndPaths() {
        ExportReport r = new ExportReport();
        r.downloaded("releases", 100);
        r.keptMismatch("releases", "com/x/a/1.0/a-1.0.jar");

        String rendered = r.render();
        assertTrue(rendered.contains("kept-mismatch=1"), rendered);
        assertTrue(rendered.contains("com/x/a/1.0/a-1.0.jar"), rendered);
    }

    @Test
    void totalBytesSumsAcrossRepos() {
        ExportReport r = new ExportReport();
        r.downloaded("releases", 100);
        r.downloaded("releases", 50);
        r.downloaded("snapshots", 10);

        assertEquals(160, r.totalBytes());
    }

    @Test
    void recordsFailures() {
        ExportReport r = new ExportReport();
        r.failed("releases", "com/x/a.jar", "checksum mismatch");

        assertEquals(1, r.totalFailed());
        assertTrue(r.hasFailures());
        assertTrue(r.render().contains("com/x/a.jar"));
        assertTrue(r.render().contains("checksum mismatch"));
    }

    // ---- failure detail cap ----

    @Test
    void failureDetailsCappedButTotalRemainsExact() {
        ExportReport r = new ExportReport();
        int cap = 1000;
        int total = cap + 50;
        for (int i = 0; i < total; i++) {
            r.failed("releases", "com/x/a" + i + ".jar", "error");
        }
        assertEquals(total, r.totalFailed(), "totalFailed must be exact even past the cap");
        assertTrue(r.hasFailures());
        String rendered = r.render();
        assertTrue(rendered.contains("more failures"), "render must note capped failures: " + rendered);
        // The 'more' line should mention the overflow count
        assertTrue(rendered.contains(String.valueOf(50)),
                "render should mention the overflow count 50: " + rendered);
    }

    // ---- Locale.ROOT in render() ----

    @Test
    void renderContainsAsciiDigits() {
        ExportReport r = new ExportReport();
        r.downloaded("releases", 100);
        r.downloaded("releases", 100);
        r.downloaded("releases", 100);
        String rendered = r.render();
        assertTrue(rendered.contains("downloaded=3"),
                "render must use ASCII digits (Locale.ROOT): " + rendered);
    }
}
