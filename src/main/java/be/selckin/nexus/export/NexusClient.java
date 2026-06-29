package be.selckin.nexus.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface NexusClient {

    List<Repository> listRepositories();

    /** One page of assets. Pass {@code null} for the first page; follow the returned token. */
    AssetPage listAssets(String repository, String continuationToken);

    /** Streams the URL to {@code dest} (parent dir must exist), truncating any existing file. */
    void download(String url, Path dest) throws IOException;
}
