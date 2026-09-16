package com.kubo.docker;

import com.kubo.model.docker.ContainerDetails;
import com.kubo.model.docker.ContainerState;
import com.kubo.model.docker.ContainerStats;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.docker.DockerImage;
import com.kubo.model.docker.DockerNetwork;
import com.kubo.model.docker.DockerStatus;
import com.kubo.model.docker.Health;
import com.kubo.model.docker.Mount;
import com.kubo.model.docker.PortMapping;
import com.kubo.model.server.ListeningPort.Exposure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerCommandsTest {

    @Test
    void statusReadsEachOutcome() {
        assertEquals(DockerStatus.Availability.ABSENT, DockerCommands.parseStatus("ABSENT", false).availability());
        assertEquals(DockerStatus.Availability.NO_PERMISSION, DockerCommands.parseStatus("NOPERM", true).availability());

        DockerStatus usable = DockerCommands.parseStatus("OK 24.0.7", true);
        assertTrue(usable.usable());
        assertEquals("24.0.7", usable.version());
        assertTrue(usable.needsRoot());
    }

    @Test
    void everyActionQuotesTheId() {
        String id = "a'b; rm -rf /";
        for (String command : List.of(DockerCommands.inspect(id), DockerCommands.start(id), DockerCommands.stop(id),
                DockerCommands.restart(id), DockerCommands.remove(id), DockerCommands.logs(id, 100),
                DockerCommands.exec(id, "ls"))) {
            assertTrue(command.contains("'a'\\''b; rm -rf /'"), command);
        }
        assertTrue(DockerCommands.remove(id).startsWith("docker rm -f "));
        assertTrue(DockerCommands.exec(id, "ps aux").endsWith("/bin/sh -c 'ps aux'"));
    }

    @Test
    void listReadsContainersPortsStackAndHealth() {
        String output = """
                {"ID":"abc123def456","Names":"kubo-api","Image":"kubo/api:1.4","State":"running","Status":"Up 3 days (healthy)","Ports":"0.0.0.0:8080->8080/tcp, :::8080->8080/tcp","Labels":"com.docker.compose.project=gemmie,com.docker.compose.service=api"}
                {"ID":"def789","Names":"kubo db","Image":"postgres:16","State":"exited","Status":"Exited (0) 2 hours ago","Ports":"5432/tcp","Labels":""}
                not-json
                """;

        List<DockerContainer> containers = DockerCommands.parseList(output);

        assertEquals(2, containers.size());
        DockerContainer api = containers.get(0);
        assertEquals("kubo-api", api.name());
        assertEquals(ContainerState.RUNNING, api.state());
        assertEquals(Health.HEALTHY, api.health());
        assertEquals("gemmie", api.project());
        assertEquals("api", api.service());
        assertTrue(api.inStack());
        // The IPv4 and IPv6 lines of the same port fold into one published mapping.
        assertEquals(1, api.ports().size());
        assertEquals(8080, api.ports().get(0).hostPort());
        assertEquals(Exposure.PUBLIC, api.ports().get(0).exposure());

        DockerContainer db = containers.get(1);
        assertEquals(ContainerState.EXITED, db.state());
        assertEquals(Health.NONE, db.health());
        assertFalse(db.inStack());
    }

    @Test
    void networkMembershipComesFromTheContainerList() {
        String output = """
                {"Names":"kubo-api","Networks":"gemmie_default,proxy"}
                {"Names":"kubo-db","Networks":"gemmie_default"}
                """;

        var membership = DockerCommands.parseNetworkMembership(output);

        assertEquals(List.of("gemmie_default", "proxy"), membership.get("kubo-api"));
        assertEquals(List.of("gemmie_default"), membership.get("kubo-db"));
    }

    @Test
    void imagesReadSizesAndFlagDangling() {
        String output = """
                {"ID":"sha256:aaa111","Repository":"postgres","Tag":"16","Size":"420MB","CreatedSince":"3 weeks ago"}
                {"ID":"bbb222","Repository":"<none>","Tag":"<none>","Size":"1.2GB","CreatedSince":"5 days ago"}
                """;

        List<DockerImage> images = DockerCommands.parseImages(output);

        assertEquals("postgres:16", images.get(0).reference());
        assertEquals("aaa111", images.get(0).shortId());
        assertFalse(images.get(0).dangling());
        assertEquals("<none>", images.get(1).reference());
        assertTrue(images.get(1).dangling());
    }

    @Test
    void networksAreListed() {
        String output = """
                {"ID":"net1","Name":"bridge","Driver":"bridge","Scope":"local"}
                {"ID":"net2","Name":"gemmie_default","Driver":"bridge","Scope":"local"}
                """;

        List<DockerNetwork> networks = DockerCommands.parseNetworks(output);

        assertEquals(2, networks.size());
        assertTrue(networks.get(0).isBuiltIn());
        assertFalse(networks.get(1).isBuiltIn());
    }

    @Test
    void reclaimedSpaceIsReadFromPrune() {
        String output = "Deleted Containers:\nabc\n\nTotal reclaimed space: 1.2GB\n";
        assertEquals(Math.round(1.2 * 1_000_000_000), DockerCommands.parseReclaimed(output));
        assertEquals(0, DockerCommands.parseReclaimed("nothing to report"));
    }

    @Test
    void restartAllQuotesEveryId() {
        String command = DockerCommands.restartAll(List.of("a1", "b2", "c3"));
        assertEquals("docker restart 'a1' 'b2' 'c3'", command);
        assertThrows(IllegalArgumentException.class, () -> DockerCommands.restartAll(List.of()));
    }

    @Test
    void runBuildsASafeDetachedCommand() {
        String command = DockerCommands.run("redis:alpine", "cache", List.of("6379:6379"));

        assertEquals("docker run -d --name 'cache' -p '6379:6379' 'redis:alpine'", command);
        assertEquals("docker run -d 'nginx'", DockerCommands.run("nginx", "", List.of()));
    }

    @Test
    void imageAndPortInputsAreValidated() {
        assertTrue(DockerCommands.imageProblem("redis:alpine").isEmpty());
        assertTrue(DockerCommands.imageProblem("").isPresent());
        assertTrue(DockerCommands.imageProblem("bad name; rm -rf /").isPresent());
        assertEquals("8080:80", DockerCommands.portMapping("8080", "80").orElseThrow());
        assertTrue(DockerCommands.portMapping("0", "80").isEmpty());
        assertTrue(DockerCommands.portMapping("x", "80").isEmpty());
    }

    @Test
    void statsReadPercentagesAndSizes() {
        String output = """
                {"ID":"abc123def456","Name":"kubo-api","CPUPerc":"12.34%","MemUsage":"25.5MiB / 1.945GiB","MemPerc":"1.28%"}
                """;

        Map<String, ContainerStats> stats = DockerCommands.parseStats(output);
        ContainerStats api = stats.get("abc123def456");

        assertEquals(12.34, api.cpuPercent(), 0.001);
        assertEquals(Math.round(25.5 * 1024 * 1024), api.memUsedBytes());
        assertEquals(Math.round(1.945 * 1024 * 1024 * 1024), api.memLimitBytes());
        assertEquals(1.28, api.memPercent(), 0.001);
    }

    @Test
    void sizesUnderstandBinaryAndDecimalUnits() {
        assertEquals(0, DockerCommands.parseSize("0B"));
        assertEquals(1024, DockerCommands.parseSize("1KiB"));
        assertEquals(1000, DockerCommands.parseSize("1kB"));
        assertEquals(Math.round(2.5 * 1024 * 1024), DockerCommands.parseSize("2.5MiB"));
        assertEquals(0, DockerCommands.parseSize(" -- "));
    }

    @Test
    void inspectReadsCommandPortsMountsAndEnvCount() {
        String output = """
                [
                  {
                    "Id": "abc123",
                    "Name": "/kubo-api",
                    "Created": "2026-09-10T08:00:00Z",
                    "Path": "node",
                    "Args": ["server.js", "--port", "8080"],
                    "State": { "Status": "running" },
                    "Config": { "Image": "kubo/api:1.4", "Env": ["A=1", "B=2", "SECRET=hidden"] },
                    "HostConfig": { "RestartPolicy": { "Name": "unless-stopped" } },
                    "NetworkSettings": { "Ports": {
                      "8080/tcp": [ { "HostIp": "0.0.0.0", "HostPort": "8080" } ],
                      "5432/tcp": null
                    } },
                    "Mounts": [
                      { "Type": "bind", "Source": "/srv/data", "Destination": "/data", "RW": true },
                      { "Type": "volume", "Name": "pgdata", "Destination": "/var/lib/postgresql/data", "RW": false }
                    ]
                  }
                ]
                """;

        ContainerDetails details = DockerCommands.parseInspect(output);

        assertEquals("kubo-api", details.name());
        assertEquals("node server.js --port 8080", details.command());
        assertEquals("unless-stopped", details.restartPolicy());
        assertEquals(3, details.envCount());
        assertEquals("A", details.env().get(0).key());
        assertFalse(details.env().get(0).sensitive());
        assertTrue(details.env().get(2).sensitive());
        assertEquals("••••••", details.env().get(2).masked());
        assertEquals(2, details.ports().size());
        assertEquals(2, details.mounts().size());
        Mount bind = details.mounts().get(0);
        assertTrue(bind.isBind());
        assertEquals("/srv/data", bind.source());
        assertTrue(bind.readWrite());
        Mount volume = details.mounts().get(1);
        assertFalse(volume.isBind());
        assertEquals("pgdata", volume.source());
        assertFalse(volume.readWrite());
    }

    @Test
    void logsFollowNothingAndKeepStderr() {
        String command = DockerCommands.logs("abc", 200);

        assertTrue(command.contains("--tail 200"));
        assertTrue(command.contains("--timestamps"));
        assertTrue(command.endsWith("2>&1"));
    }
}
