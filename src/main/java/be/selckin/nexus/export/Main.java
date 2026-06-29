package be.selckin.nexus.export;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(
        name = "nexus-export",
        mixinStandardHelpOptions = true,
        version = "nexus-export 1.0",
        description = "Bulk-export Nexus 3 hosted Maven repositories to on-disk Maven layouts.")
public final class Main implements Callable<Integer> {

    @Override
    public Integer call() {
        // Dispatch logic added in Task 8.
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
