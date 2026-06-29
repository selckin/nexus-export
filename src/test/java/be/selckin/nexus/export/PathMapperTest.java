package be.selckin.nexus.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathMapperTest {

    private final Path out = Paths.get("/export");

    @Test
    void mapsAssetPathUnderRepo() {
        Path dest = PathMapper.resolve(out, "releases", "com/example/foo/1.0/foo-1.0.jar");
        assertEquals(Paths.get("/export/releases/com/example/foo/1.0/foo-1.0.jar"), dest);
    }

    @Test
    void stripsLeadingSlashInAssetPath() {
        Path dest = PathMapper.resolve(out, "releases", "/com/example/foo/1.0/foo-1.0.jar");
        assertTrue(dest.startsWith(Paths.get("/export/releases")));
        assertTrue(dest.endsWith(Paths.get("foo-1.0.jar")));
    }

    @Test
    void rejectsTraversalEscape() {
        assertThrows(IllegalArgumentException.class,
                () -> PathMapper.resolve(out, "releases", "../../etc/passwd"));
    }
}
