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
}
