package be.selckin.nexus.export;

import java.nio.file.Path;

public final class PathMapper {

    private PathMapper() {
    }

    public static Path resolve(Path outRoot, String repo, String assetPath) {
        Path repoRoot = outRoot.resolve(repo).normalize();
        String relative = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;
        Path dest = repoRoot.resolve(relative).normalize();
        if (!dest.startsWith(repoRoot)) {
            throw new IllegalArgumentException(
                    "asset path escapes repository root: " + assetPath);
        }
        return dest;
    }
}
