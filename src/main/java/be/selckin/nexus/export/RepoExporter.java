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
import java.util.concurrent.Executor;

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
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        String token = null;
        do {
            AssetPage page = client.listAssets(repo, token);
            for (Asset asset : page.items()) {
                futures.add(CompletableFuture.runAsync(() -> process(repo, asset), executor));
            }
            token = page.continuationToken();
        } while (token != null);
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void process(String repo, Asset asset) {
        try {
            Path dest = PathMapper.resolve(outRoot, repo, asset.path());
            String expectedSha1 = asset.checksum() == null ? null : asset.checksum().sha1();

            if (Files.exists(dest) && expectedSha1 != null
                    && expectedSha1.equalsIgnoreCase(ChecksumUtil.sha1(dest))) {
                report.skipped(repo);
                return;
            }
            if (dryRun) {
                report.downloaded(repo, asset.fileSize());
                return;
            }

            Files.createDirectories(dest.getParent());
            Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
            client.download(asset.downloadUrl(), tmp);

            if (expectedSha1 != null) {
                String actual = ChecksumUtil.sha1(tmp);
                if (!expectedSha1.equalsIgnoreCase(actual)) {
                    Files.deleteIfExists(tmp);
                    throw new IOException("checksum mismatch: expected " + expectedSha1 + " got " + actual);
                }
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            report.downloaded(repo, Files.size(dest));
            writeSidecars(dest, asset.checksum());
        } catch (Exception e) {
            log.warn("failed {} / {}: {}", repo, asset.path(), e.toString());
            report.failed(repo, asset.path(), e.getMessage());
        }
    }

    private void writeSidecars(Path dest, Checksum checksum) throws IOException {
        if (checksum == null || isMetadataExtension(dest.getFileName().toString())) {
            return;
        }
        if (checksum.sha1() != null) {
            Files.write(dest.resolveSibling(dest.getFileName() + ".sha1"), checksum.sha1().getBytes(UTF_8));
        }
        if (checksum.md5() != null) {
            Files.write(dest.resolveSibling(dest.getFileName() + ".md5"), checksum.md5().getBytes(UTF_8));
        }
    }

    private static boolean isMetadataExtension(String name) {
        return name.endsWith(".sha1") || name.endsWith(".md5")
                || name.endsWith(".sha256") || name.endsWith(".sha512") || name.endsWith(".asc");
    }
}
