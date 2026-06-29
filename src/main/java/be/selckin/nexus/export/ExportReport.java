package be.selckin.nexus.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ExportReport {

    private static final class Tally {
        final AtomicLong downloaded = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();
    }

    private final Map<String, Tally> byRepo = new ConcurrentSkipListMap<>();
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());

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
        failures.add(repo + "/" + path + ": " + reason);
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

    public boolean hasFailures() {
        return totalFailed() > 0;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        long bytes = byRepo.values().stream().mapToLong(t -> t.bytes.get()).sum();
        sb.append(String.format("totals: downloaded=%d skipped=%d failed=%d bytes=%d%n",
                totalDownloaded(), totalSkipped(), totalFailed(), bytes));
        byRepo.forEach((repo, t) -> sb.append(String.format(
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
        return sb.toString();
    }
}
