package com.kubo.shell;

import com.kubo.model.shell.SystemProcess;
import com.kubo.model.shell.TableCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("What is running, asked for as columns")
class SystemProcessTest {

    /** Exactly the shape Kubo's ps line produces: eight fields, then the command with its spaces. */
    private static final String PS = """
              1     0 root      0.0  0.1  12480 Ss     191400 /sbin/init splash
           2691 33051 xana     14.3  6.2 508240 Sl       4320 node /home/xana/app/server.js --port 3000
           3350  2691 mongod    3.5  2.0 275968 Ssl    191400 mongod --replSet rs0 --bind_ip_all
           4102     1 xana      0.0  0.0      0 Z           7 [node] <defunct>
            """;

    @Test
    void everyColumnIsTheOneItWasAskedFor() {
        List<SystemProcess> found = SystemProcess.parse(PS);

        assertEquals(4, found.size());
        SystemProcess node = found.get(0);
        assertEquals(2691, node.pid());
        assertEquals(33051, node.parent());
        assertEquals("xana", node.user());
        assertEquals(14.3, node.cpu());
        assertEquals(6.2, node.memoryShare());
        assertEquals(508_240L * 1024, node.resident(), "ps reports resident memory in kilobytes");
        assertEquals("Sl", node.state());
        assertEquals(Duration.ofSeconds(4320), node.up());
        assertEquals("node /home/xana/app/server.js --port 3000", node.command());
    }

    @Test
    void theHeaviestComesFirst() {
        List<SystemProcess> found = SystemProcess.parse(PS);

        assertEquals(List.of(14.3, 3.5, 0.0, 0.0), found.stream().map(SystemProcess::cpu).toList());
    }

    @Test
    void theCommandKeepsItsSpacesBecauseItIsLast() {
        // Split on whitespace with a limit. Anything else cuts "mongod --replSet rs0" into three processes.
        assertEquals("mongod --replSet rs0 --bind_ip_all", SystemProcess.parse(PS).get(1).command());
        assertEquals("mongod", SystemProcess.parse(PS).get(1).program());
        assertEquals("node", SystemProcess.parse(PS).get(0).program(), "the path it was started from is not the name");
    }

    @Test
    void theNameIsWhatIsBeingRunRatherThanWhatIsRunningIt() {
        // A server with five things under node has five processes called node. The file is the name.
        List<SystemProcess> found = SystemProcess.parse(PS);

        assertEquals("server.js", found.get(0).name());
        assertEquals("node", found.get(0).program(), "the program is still the program");
        assertEquals("mongod", found.get(1).name());
        assertEquals("init", found.get(2).name());
    }

    @Test
    void aKernelThreadKeepsItsBrackets() {
        // "[kworker/1:1H]" is the name of that thing. Stripped, it looks like a program somebody started.
        SystemProcess thread = SystemProcess.parse("   79     2 root 0.0 0.0 0 I< 191400 [kworker/1:1H-kblockd]")
                .get(0);

        assertEquals("[kworker/1:1H-kblockd]", thread.name());
    }

    @Test
    void sshdLosesTheColonItWritesIntoItsOwnFirstWord() {
        SystemProcess session = SystemProcess.parse("3592 3350 root 0.0 0.3 48128 Ss 120600 sshd: xana [priv]")
                .get(0);

        assertEquals("sshd", session.name());
    }

    @Test
    void anInterpreterWithOnlyOptionsKeepsItsOwnName() {
        SystemProcess bare = SystemProcess.parse("  12 1 root 0.0 0.0 100 Ss 10 python3 -u").get(0);

        assertEquals("python3", bare.name());
    }

    @Test
    void theStateIsReadFromItsFirstLetter() {
        List<SystemProcess> found = SystemProcess.parse(PS);

        assertTrue(found.get(3).zombie(), "Z is a process waiting for its parent to notice it has gone");
        assertFalse(found.get(3).running());
        assertFalse(found.get(0).running(), "Sl is asleep, whatever else it is");
    }

    @Test
    void anythingThatIsNotAProcessIsSkipped() {
        assertEquals(List.of(), SystemProcess.parse("ps: unrecognized option"));
        assertEquals(List.of(), SystemProcess.parse("  PID  PPID USER"));
        assertEquals(List.of(), SystemProcess.parse(""));
        assertEquals(List.of(), SystemProcess.parse(null));
    }

    @Test
    void theMachinesOwnFiguresComeBackInTheSameAnswer() {
        // One command, so the table and the numbers above it are from the same moment.
        String whole = PS + """
                @@load
                1.94 1.62 1.38 3/382 41233
                @@mem
                MemTotal:        8039420 kB
                MemAvailable:    6584312 kB
                @@cpus
                4
                """;

        com.kubo.model.shell.ProcessReading reading = com.kubo.model.shell.ProcessReading.parse(whole);

        assertEquals(4, reading.processes().size());
        assertEquals(1.94, reading.load());
        assertEquals(3, reading.running());
        assertEquals(382, reading.total(), "the count comes from the kernel, not from how many ps listed");
        assertEquals(4, reading.cores());
        assertEquals(8_039_420L * 1024, reading.memoryTotal());
        // Available, not free: free counts the cache as taken, which makes a healthy server look full.
        assertEquals((8_039_420L - 6_584_312L) * 1024, reading.memoryUsed());
        assertEquals(0.485, reading.loadShare(), 0.001, "1.94 over four cores is half a machine");
    }

    @Test
    void theMarkersAreLinesRatherThanTextFoundAnywhere() {
        // ps lists the shell running this very command, and that shell's arguments are the command, markers
        // and all. Looked for as text, every marker was found inside that one line, so every figure came out
        // of Kubo's own command line: load 0, memory nothing, one core.
        String withItself = """
                   1     0 root      0.0  0.1  12480 Ss     191400 /sbin/init splash
                3409     1 root      0.0  0.0   4096 Ss         1 sh -c LC_ALL=C ps -eo pid=; echo @@load;                 cat /proc/loadavg; echo @@mem; grep MemTotal /proc/meminfo; echo @@cpus; nproc
                @@load
                1.94 1.62 1.38 3/382 41233
                @@mem
                MemTotal:        8039420 kB
                MemAvailable:    6584312 kB
                @@cpus
                4
                """;

        com.kubo.model.shell.ProcessReading reading = com.kubo.model.shell.ProcessReading.parse(withItself);

        assertEquals(1.94, reading.load());
        assertEquals(382, reading.total());
        assertEquals(4, reading.cores());
        assertEquals(8_039_420L * 1024, reading.memoryTotal());
    }

    @Test
    void aReadingWithNothingAfterTheProcessesStillHasTheProcesses() {
        com.kubo.model.shell.ProcessReading reading = com.kubo.model.shell.ProcessReading.parse(PS);

        assertEquals(4, reading.processes().size());
        assertEquals(4, reading.total());
        assertEquals(1, reading.cores(), "one, so a load is never divided by zero");
        assertEquals(0, reading.memoryShare());
    }

    @Test
    void theScreenDrawersAreAnsweredWithTheTable() {
        assertEquals(Optional.of(new TableCall(TableCall.Kind.PROCESSES, false)), TableCall.of("htop"));
        assertEquals(Optional.of(new TableCall(TableCall.Kind.PROCESSES, false)), TableCall.of("top"));
        assertEquals(Optional.of(new TableCall(TableCall.Kind.PROCESSES, true)), TableCall.of("sudo htop"));
        assertEquals(Optional.of(new TableCall(TableCall.Kind.PROCESSES, false)), TableCall.of("ps aux"));
        assertEquals(Optional.of(new TableCall(TableCall.Kind.PROCESSES, false)), TableCall.of("ps -ef"));
    }

    @Test
    void askingForTheTextOnPurposeStillGetsTheText() {
        // "top -b -n1" is the batch mode somebody uses when they want the output, usually into a file.
        assertEquals(Optional.empty(), TableCall.of("top -b -n1"));
        assertEquals(Optional.empty(), TableCall.of("ps aux --sort=-rss"));
        assertEquals(Optional.empty(), TableCall.of("ps aux | grep node"));
        assertEquals(Optional.empty(), TableCall.of("ps"));
    }
}
