package be.selckin.nexus.export;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void noVerifyChecksumsDefaultsOff() {
        Main main = CommandLine.populateCommand(new Main(), "--url", "http://x");
        assertFalse(main.noVerifyChecksums);
    }

    @Test
    void noVerifyChecksumsFlagParses() {
        Main main = CommandLine.populateCommand(new Main(), "--url", "http://x", "--no-verify-checksums");
        assertTrue(main.noVerifyChecksums);
    }
}
