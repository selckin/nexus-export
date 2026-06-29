package be.selckin.nexus.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtoParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes());
        }
    }

    @Test
    void parsesRepositories() throws Exception {
        List<Repository> repos = mapper.readValue(
                fixture("repositories.json"),
                mapper.getTypeFactory().constructCollectionType(List.class, Repository.class));
        assertEquals(4, repos.size());
        assertEquals("releases", repos.get(0).name());
        assertEquals("maven2", repos.get(0).format());
        assertEquals("hosted", repos.get(0).type());
    }

    @Test
    void parsesAssetPage() throws Exception {
        AssetPage page = mapper.readValue(fixture("assets-page.json"), AssetPage.class);
        assertEquals("next-token-xyz", page.continuationToken());
        assertEquals(1, page.items().size());
        Asset a = page.items().get(0);
        assertEquals("com/example/foo/1.0/foo-1.0.jar", a.path());
        assertTrue(a.downloadUrl().endsWith("/foo-1.0.jar"));
        assertEquals(1234, a.fileSize());
        assertEquals("0beec7b5ea3f0fdbc95d0dd47f3c5bc275da8a33", a.checksum().sha1());
        assertEquals("5d41402abc4b2a76b9719d911017c592", a.checksum().md5());
    }
}
