package be.selckin.nexus.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ExportReport {

    static final int MAX_DETAILS = 1000;

    private static final class Tally {
        final AtomicLong downloaded = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong keptMismatch = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();
    }

    private final Map<String, Tally> byRepo = new ConcurrentSkipListMap<>();
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger failureDetailCount = new AtomicInteger();
    private final List<String> keptMismatches = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger keptMismatchDetailCount = new AtomicInteger();

    public void downloaded(String repo, long bytes) {
        Tally t = byRepo.computeIfAbsent(repo, k -> new Tally());
        t.downloaded.incrementAndGet();
        t.bytes.addAndGet(bytes);
    }

    /**
     * Records a file kept despite its bytes not matching the source's recorded checksum
     * (only reachable under {@code --no-verify-checksums}). The file is also counted via
     * {@link #downloaded}; this is an additional audit tally, not a failure.
     */
    public void keptMismatch(String repo, String path) {
        byRepo.computeIfAbsent(repo, k -> new Tally()).keptMismatch.incrementAndGet();
        if (keptMismatchDetailCount.incrementAndGet() <= MAX_DETAILS) {
            keptMismatches.add(repo + "/" + path);
        }
    }

    public void skipped(String repo) {
        byRepo.computeIfAbsent(repo, k -> new Tally()).skipped.incrementAndGet();
    }

    public void failed(String repo, String path, String reason) {
        byRepo.computeIfAbsent(repo, k -> new Tally()).failed.incrementAndGet();
        if (failureDetailCount.incrementAndGet() <= MAX_DETAILS) {
            failures.add(repo + "/" + path + ": " + reason);
        }
    }

    public long totalDownloaded() {
        return byRepo.values().stream().mapToLong(t -> t.downloaded.get()).sum();
    }

    public long totalSkipped() {
        return byRepo.values().stream().mapToLong(t -> t.skipped.get()).sum();
    }

    public long totalFailed() {
        return byRepo.values().stream().mapToLong(t -> t.failed.get()).sum();
    }

    public long totalKeptMismatch() {
        return byRepo.values().stream().mapToLong(t -> t.keptMismatch.get()).sum();
    }

    public long totalBytes() {
        return byRepo.values().stream().mapToLong(t -> t.bytes.get()).sum();
    }

    public boolean hasFailures() {
        return totalFailed() > 0;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        long keptTotal = totalKeptMismatch();
        sb.append(String.format(Locale.ROOT, "totals: downloaded=%d skipped=%d failed=%d",
                totalDownloaded(), totalSkipped(), totalFailed()));
        // kept-mismatch is a subset of downloaded; only shown when non-zero to keep clean runs quiet
        if (keptTotal > 0) {
            sb.append(String.format(Locale.ROOT, " kept-mismatch=%d", keptTotal));
        }
        sb.append(String.format(Locale.ROOT, " bytes=%d%n", totalBytes()));
        byRepo.forEach((repo, t) -> {
            sb.append(String.format(Locale.ROOT, "  %s: downloaded=%d skipped=%d failed=%d",
                    repo, t.downloaded.get(), t.skipped.get(), t.failed.get()));
            if (t.keptMismatch.get() > 0) {
                sb.append(String.format(Locale.ROOT, " kept-mismatch=%d", t.keptMismatch.get()));
            }
            sb.append(String.format(Locale.ROOT, " bytes=%d%n", t.bytes.get()));
        });
        appendDetailSection(sb, "failures:", failures, totalFailed(), "more failures");
        appendDetailSection(sb, "kept despite checksum mismatch:", keptMismatches, keptTotal, "more kept");
        return sb.toString();
    }

    /** Appends a capped detail list (failures, kept mismatches) with an overflow note when truncated. */
    private static void appendDetailSection(StringBuilder sb, String header, List<String> details,
                                            long total, String overflowLabel) {
        if (details.isEmpty()) {
            return;
        }
        sb.append(header).append(System.lineSeparator());
        synchronized (details) {
            for (String d : details) {
                sb.append("  ").append(d).append(System.lineSeparator());
            }
        }
        if (total > MAX_DETAILS) {
            sb.append(String.format(Locale.ROOT, "… and %d %s (detail capped at %d)%n",
                    total - MAX_DETAILS, overflowLabel, MAX_DETAILS));
        }
    }
}
