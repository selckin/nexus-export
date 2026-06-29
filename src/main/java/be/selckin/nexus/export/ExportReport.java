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

    static final int MAX_FAILURE_DETAILS = 1000;

    private static final class Tally {
        final AtomicLong downloaded = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();
    }

    private final Map<String, Tally> byRepo = new ConcurrentSkipListMap<>();
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger failureDetailCount = new AtomicInteger();

    public void downloaded(String repo, long bytes) {
        Tally t = byRepo.computeIfAbsent(repo, k -> new Tally());
        t.downloaded.incrementAndGet();
        t.bytes.addAndGet(bytes);
    }

    public void skipped(String repo) {
        byRepo.computeIfAbsent(repo, k -> new Tally()).skipped.incrementAndGet();
    }

    public void failed(String repo, String path, String reason) {
        byRepo.computeIfAbsent(repo, k -> new Tally()).failed.incrementAndGet();
        if (failureDetailCount.incrementAndGet() <= MAX_FAILURE_DETAILS) {
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

    public long totalBytes() {
        return byRepo.values().stream().mapToLong(t -> t.bytes.get()).sum();
    }

    public boolean hasFailures() {
        return totalFailed() > 0;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "totals: downloaded=%d skipped=%d failed=%d bytes=%d%n",
                totalDownloaded(), totalSkipped(), totalFailed(), totalBytes()));
        byRepo.forEach((repo, t) -> sb.append(String.format(Locale.ROOT,
                "  %s: downloaded=%d skipped=%d failed=%d bytes=%d%n",
                repo, t.downloaded.get(), t.skipped.get(), t.failed.get(), t.bytes.get())));
        if (!failures.isEmpty()) {
            sb.append("failures:").append(System.lineSeparator());
            synchronized (failures) {
                for (String f : failures) {
                    sb.append("  ").append(f).append(System.lineSeparator());
                }
            }
        }
        long total = totalFailed();
        if (total > MAX_FAILURE_DETAILS) {
            sb.append(String.format(Locale.ROOT, "… and %d more failures (detail capped at %d)%n",
                    total - MAX_FAILURE_DETAILS, MAX_FAILURE_DETAILS));
        }
        return sb.toString();
    }
}
