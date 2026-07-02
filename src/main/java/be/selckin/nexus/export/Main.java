package be.selckin.nexus.export;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "nexus-export",
        mixinStandardHelpOptions = true,
        version = "nexus-export 1.0",
        description = "Bulk-export Nexus 3 hosted Maven repositories to on-disk Maven layouts.")
public final class Main implements Callable<Integer> {

    @Option(names = "--url", description = "Nexus base URL (or env NEXUS_URL), e.g. https://nexus.example.com")
    String url;

    @Option(names = "--user", description = "Nexus user (or env NEXUS_USER); omit for anonymous")
    String user;

    @Option(names = "--password", description = "Nexus password (discouraged; prefer env NEXUS_PASSWORD)")
    String password;

    @Option(names = "--out", description = "Output root directory (default: ./export)")
    Path out = Path.of("export");

    @Option(names = "--repo", description = "Repository to export; repeatable (default: releases, snapshots)")
    List<String> repos = List.of();

    @Option(names = "--threads", description = "Parallel downloads per repository (default: 6)")
    int threads = 6;

    @Option(names = "--progress-interval", description = "Seconds between progress log lines (default: 10; 0 = off)")
    int progressInterval = 10;

    @Option(names = "--list", description = "List maven2 repositories and exit")
    boolean list;

    @Option(names = "--dry-run", description = "Enumerate and report, but download nothing")
    boolean dryRun;

    @Option(names = "--no-verify-checksums",
            description = "Keep downloaded files even when they don't match the source's recorded "
                    + "checksum (for repos with bad checksums on the server); sidecars record the "
                    + "actual downloaded bytes. Default: mismatches fail the asset.")
    boolean noVerifyChecksums;

    @Override
    public Integer call() {
        try {
            String resolvedUrl = resolveRequired(url, System.getenv("NEXUS_URL"), "--url / NEXUS_URL");
            String resolvedUser = resolveOptional(user, System.getenv("NEXUS_USER"));
            String resolvedPassword = resolveOptional(password, System.getenv("NEXUS_PASSWORD"));
            validateCredentials(resolvedUser, resolvedPassword);

            NexusClient client = new HttpNexusClient(resolvedUrl, resolvedUser, resolvedPassword);
            List<String> targetRepos = repos.isEmpty() ? List.of("releases", "snapshots") : repos;

            return new ExportRunner(System.out)
                    .run(client, targetRepos, out, list, dryRun, threads, progressInterval, !noVerifyChecksums);
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            return 2;
        }
    }

    static String resolveRequired(String flag, String env, String name) {
        String v = resolveOptional(flag, env);
        if (v == null) {
            throw new IllegalArgumentException("missing required option " + name);
        }
        return v;
    }

    static String resolveOptional(String flag, String env) {
        if (flag != null) {
            return flag;
        }
        return env;
    }

    static void validateCredentials(String user, String password) {
        if (user != null && password == null) {
            throw new IllegalArgumentException(
                    "--password (or NEXUS_PASSWORD) is required when --user is set");
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
