package be.selckin.nexus.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChecksumUtilTest {

    @Test
    void sha1AndMd5OfKnownContent(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("f.txt");
        Files.write(f, "abc".getBytes(UTF_8));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ChecksumUtil.sha1(f));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ChecksumUtil.md5(f));
    }
}
