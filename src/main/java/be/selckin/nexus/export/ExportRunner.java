package be.selckin.nexus.export;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class ExportRunner {

    private final PrintStream out;

    public ExportRunner(PrintStream out) {
        this.out = out;
    }

    public int run(NexusClient client, List<String> repos, Path outRoot,
                   boolean list, boolean dryRun, int threads) {
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
            try {
                for (String repo : repos) {
                    if (!maven2.contains(repo)) {
                        out.println("WARN skipping unknown or non-maven2 repository: " + repo);
                        continue;
                    }
                    new RepoExporter(client, outRoot, pool, dryRun, report).export(repo);
                }
            } finally {
                pool.shutdown();
                pool.awaitTermination(1, TimeUnit.MINUTES);
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
