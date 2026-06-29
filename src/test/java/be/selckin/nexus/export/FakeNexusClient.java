package be.selckin.nexus.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Hand-rolled in-memory NexusClient for tests (no mock framework). */
final class FakeNexusClient implements NexusClient {

    private final List<Repository> repos;
    private final Map<String, List<Asset>> assetsByRepo;
    private final Map<String, byte[]> contentByUrl;
    private final AtomicInteger downloads = new AtomicInteger();

    FakeNexusClient(List<Repository> repos,
                    Map<String, List<Asset>> assetsByRepo,
                    Map<String, byte[]> contentByUrl) {
        this.repos = repos;
        this.assetsByRepo = assetsByRepo;
        this.contentByUrl = contentByUrl;
    }

    @Override
    public List<Repository> listRepositories() {
        return repos;
    }

    @Override
    public AssetPage listAssets(String repository, String continuationToken) {
        // Single page: ignore the token, always return everything with a null next token.
        return new AssetPage(assetsByRepo.getOrDefault(repository, List.of()), null);
    }

    @Override
    public void download(String url, Path dest) throws IOException {
        byte[] body = contentByUrl.get(url);
        if (body == null) {
            throw new IOException("404 " + url);
        }
        downloads.incrementAndGet();
        Files.write(dest, body);
    }

    int downloadCount() {
        return downloads.get();
    }
}
