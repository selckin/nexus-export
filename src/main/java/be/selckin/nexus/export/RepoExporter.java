package be.selckin.nexus.export;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class RepoExporter {

    private static final Logger log = LoggerFactory.getLogger(RepoExporter.class);

    private final NexusClient client;
    private final Path outRoot;
    private final Executor executor;
    private final boolean dryRun;
    private final ExportReport report;

    public RepoExporter(NexusClient client, Path outRoot, Executor executor, boolean dryRun, ExportReport report) {
        this.client = client;
        this.outRoot = outRoot;
        this.executor = executor;
        this.dryRun = dryRun;
        this.report = report;
    }

    public void export(String repo) {
        log.info("exporting repository '{}'{}", repo, dryRun ? " (dry-run)" : "");
        // first auth failure during download aborts the whole repo export
        AtomicReference<NexusException> fatal = new AtomicReference<>();
        // A dedicated single thread (separate from the download pool) lets the next page's
        // listing run while the current page's downloads are in flight, so list latency is
        // hidden behind downloads without contending for a download worker.
        ExecutorService lister = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nexus-export-list");
            t.setDaemon(true);
            return t;
        });
        try {
            AssetPage page = client.listAssets(repo, null);
            while (page != null) {
                // treat a null items list as an empty page
                List<Asset> items = page.items() == null ? List.of() : page.items();
                List<CompletableFuture<Void>> pageFutures = new ArrayList<>(items.size());
                for (Asset asset : items) {
                    pageFutures.add(CompletableFuture.runAsync(() -> process(repo, asset, fatal), executor));
                }
                // Prefetch the next page's listing concurrently. Only THIS page's download
                // futures are ever held, so the per-page memory bound is preserved.
                String token = page.continuationToken();
                CompletableFuture<AssetPage> nextPage = (token != null && !token.isBlank())
                        ? CompletableFuture.supplyAsync(() -> client.listAssets(repo, token), lister)
                        : null;
                CompletableFuture.allOf(pageFutures.toArray(new CompletableFuture[0])).join();
                // propagate the first auth failure so ExportRunner maps it to exit 2
                NexusException authFailure = fatal.get();
                if (authFailure != null) throw authFailure;
                page = nextPage == null ? null : awaitPage(nextPage);
            }
        } finally {
            lister.shutdownNow();
        }
    }

    /** Joins a prefetched page, unwrapping CompletionException so the original NexusException /
     *  UncheckedIOException type reaches ExportRunner's exit-code mapping (as a synchronous list call would). */
    private static AssetPage awaitPage(CompletableFuture<AssetPage> nextPage) {
        try {
            return nextPage.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private void process(String repo, Asset asset, AtomicReference<NexusException> fatal) {
        try {
            Path dest = PathMapper.resolve(outRoot, repo, asset.path());
            String expectedSha1 = asset.checksum() == null ? null : asset.checksum().sha1();

            // skip if file exists and (no recorded checksum OR sha1 matches)
            if (Files.exists(dest) && (expectedSha1 == null
                    || expectedSha1.equalsIgnoreCase(ChecksumUtil.sha1(dest)))) {
                // dry-run must not write sidecars
                if (!dryRun) {
                    // sidecar write on skip path is best-effort; a failure must not mark an intact file as failed
                    try {
                        writeSidecars(dest, asset.checksum(), false);
                    } catch (IOException e) {
                        log.warn("could not write sidecars for skipped asset {} / {}: {}", repo, asset.path(), e.toString());
                    }
                }
                report.skipped(repo);
                return;
            }
            if (dryRun) {
                report.downloaded(repo, asset.fileSize());
                return;
            }

            Files.createDirectories(dest.getParent());
            Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
            try {
                client.download(asset.downloadUrl(), tmp);

                // verify only when a recorded checksum is available
                if (expectedSha1 != null) {
                    String actual = ChecksumUtil.sha1(tmp);
                    if (!expectedSha1.equalsIgnoreCase(actual)) {
                        throw new IOException("checksum mismatch: expected " + expectedSha1 + " got " + actual);
                    }
                }
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                report.downloaded(repo, Files.size(dest));
                // when no recorded checksum, compute sha1+md5 for self-describing tree
                if (asset.checksum() != null) {
                    writeSidecars(dest, asset.checksum(), true);
                } else {
                    // No recorded checksum — compute from the downloaded file
                    writeSidecars(dest, new Checksum(ChecksumUtil.sha1(dest), ChecksumUtil.md5(dest), null), true);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (NexusException e) {
            // auth failures are fatal — store for propagation, do not record as per-asset failure
            fatal.compareAndSet(null, e);
        } catch (Exception e) {
            log.warn("failed {} / {}: {}", repo, asset.path(), e.toString());
            report.failed(repo, asset.path(), e.toString());
        }
    }

    /**
     * Writes the {@code .sha1}/{@code .md5} sidecars next to {@code dest} from the given checksum.
     * Skipped entirely for checksum-less or metadata files. When {@code overwrite} is false, an
     * existing sidecar is left untouched (skip path); when true, it is rewritten (download path).
     */
    private void writeSidecars(Path dest, Checksum checksum, boolean overwrite) throws IOException {
        if (checksum == null || isMetadataExtension(dest.getFileName().toString())) {
            return;
        }
        writeSidecar(dest.resolveSibling(dest.getFileName() + ".sha1"), checksum.sha1(), overwrite);
        writeSidecar(dest.resolveSibling(dest.getFileName() + ".md5"), checksum.md5(), overwrite);
    }

    private static void writeSidecar(Path file, String content, boolean overwrite) throws IOException {
        if (content == null || (!overwrite && Files.exists(file))) {
            return;
        }
        Files.write(file, content.getBytes(UTF_8));
    }

    private static boolean isMetadataExtension(String name) {
        return name.endsWith(".sha1") || name.endsWith(".md5")
                || name.endsWith(".sha256") || name.endsWith(".sha512") || name.endsWith(".asc");
    }
}
