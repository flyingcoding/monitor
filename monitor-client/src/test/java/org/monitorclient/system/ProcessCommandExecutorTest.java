package org.monitorclient.system;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real child pipes using the same JRE as the test process. */
class ProcessCommandExecutorTest {
    /** Ensures newline-free output cannot allocate an unbounded line or leak reader threads. */
    @Test
    void shouldRejectOversizedSingleLineOutput() {
        CommandExecutor.CommandResult result = runFixture("large", Duration.ofSeconds(5));
        assertFalse(result.success());
        assertEquals("", result.stdout());
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("monitor-command-reader")));
    }

    /** Reads stdout and stderr concurrently without pipe backpressure deadlocking a child. */
    @Test
    void shouldDrainBothStreamsAndEnforceDeadline() {
        CommandExecutor.CommandResult output = runFixture("both", Duration.ofSeconds(5));
        assertTrue(output.success());
        assertEquals(65536, output.stdout().length());
        assertEquals(65536, output.stderr().length());
        long start = System.nanoTime();
        assertTrue(runFixture("sleep", Duration.ofMillis(200)).timedOut());
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos());
    }

    /** Starts only the test fixture; no administrator-configured command is executed. */
    private CommandExecutor.CommandResult runFixture(String mode, Duration timeout) {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessCommandExecutor().execute(Arrays.asList(java, "-cp", System.getProperty("java.class.path"),
                Child.class.getName(), mode), timeout);
    }

    /** Generates deterministic pipe pressure, large lines and a stalled command. */
    public static final class Child {
        /** Runs one isolated output fixture in a subprocess. */
        public static void main(String[] args) throws Exception {
            byte[] bytes = new byte[4096];
            Arrays.fill(bytes, (byte) 'x');
            if ("sleep".equals(args[0])) { Thread.sleep(10000); return; }
            int count = "large".equals(args[0]) ? 256 : 16;
            for (int i = 0; i < count; i++) {
                System.out.write(bytes);
                if ("both".equals(args[0])) System.err.write(bytes);
            }
            System.out.flush();
            System.err.flush();
        }
    }
}
