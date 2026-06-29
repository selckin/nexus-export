package be.selckin.nexus.export;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainHelpTest {

    @Test
    void helpExitsZero() {
        int code = new CommandLine(new Main()).execute("--help");
        assertEquals(0, code);
    }
}
