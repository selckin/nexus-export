package be.selckin.nexus.export;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class ExportRunner {

    private static final Logger log = LoggerFactory.getLogger(ExportRunner.class);

    private final PrintStream out;

    public ExportRunner(PrintStream out) {
        this.out = out;
    }

    private ScheduledExecutorService startHeartbeat(int intervalSeconds, ExportReport report,
                                                    AtomicReference<String> currentRepo) {
        if (intervalSeconds <= 0) {
            return null;
        }
        long startMillis = System.currentTimeMillis();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexus-export-progress");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                () -> log.info(formatProgress(currentRepo.get(),
                        report.totalDownloaded(), report.totalSkipped(), report.totalFailed(),
                        report.totalBytes(), System.currentTimeMillis() - startMillis)),
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        return scheduler;
    }

    static String formatProgress(String repo, long downloaded, long skipped, long failed,
                                 long bytes, long elapsedMillis) {
        double mb = bytes / 1_000_000.0;
        double elapsedSec = elapsedMillis / 1000.0;
        double rate = elapsedSec > 0 ? mb / elapsedSec : 0.0;
        long totalSec = elapsedMillis / 1000;
        return String.format(Locale.ROOT,
                "progress: repo=%s downloaded=%d skipped=%d failed=%d, %.1f MB (%.1f MB/s), elapsed %02d:%02d",
                repo, downloaded, skipped, failed, mb, rate, totalSec / 60, totalSec % 60);
    }

    public int run(NexusClient client, List<String> repos, Path outRoot,
                   boolean list, boolean dryRun, int threads, int progressIntervalSeconds) {
        try {
            List<Repository> all = client.listRepositories();
            if (list) {
                all.stream()
                        .filter(r -> "maven2".equals(r.format()))
                        .forEach(r -> out.printf("%-30s %-8s %s%n", r.name(), r.type(), r.format()));
                return 0;
            }

            Set<String> maven2 = all.stream()
                    .filter(r -> "maven2".equals(r.format()))
                    .map(Repository::name)
                    .collect(Collectors.toSet());

            ExportReport report = new ExportReport();
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(64, Math.max(1, threads)));
            AtomicReference<String> currentRepo = new AtomicReference<>("");
            ScheduledExecutorService heartbeat = startHeartbeat(progressIntervalSeconds, report, currentRepo);
            try {
                for (String repo : repos) {
                    if (!maven2.contains(repo)) {
                        out.println("WARN skipping unknown or non-maven2 repository: " + repo);
                        continue;
                    }
                    currentRepo.set(repo);
                    new RepoExporter(client, outRoot, pool, dryRun, report).export(repo);
                }
            } finally {
                pool.shutdown();
                pool.awaitTermination(1, TimeUnit.MINUTES);
                if (heartbeat != null) {
                    heartbeat.shutdownNow();
                }
            }

            out.print(dryRun ? "DRY RUN — " : "");
            out.print(report.render());
            return report.hasFailures() ? 1 : 0;
        } catch (NexusException e) {
            out.println("ERROR: " + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("ERROR: interrupted");
            return 2;
        } catch (RuntimeException e) {
            out.println("ERROR: " + e.getMessage());
            return 2;
        }
    }
}
