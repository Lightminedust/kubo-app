package com.kubo.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubo.files.ShellQuote;
import com.kubo.model.docker.ContainerDetails;
import com.kubo.model.docker.ContainerState;
import com.kubo.model.docker.ContainerStats;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.docker.DockerStatus;
import com.kubo.model.docker.DockerImage;
import com.kubo.model.docker.DockerNetwork;
import com.kubo.model.docker.DockerStatus.Availability;
import com.kubo.model.docker.EnvVar;
import com.kubo.model.docker.Health;
import com.kubo.model.docker.Mount;
import com.kubo.model.docker.PortMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Docker CLI commands Kubo runs and the reading of their answers. Every value uses {@code --format '{{json .}}'}
 * so the output is one JSON object per line, robust to spaces in names, images and status.
 */
public final class DockerCommands {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DockerCommands() {
    }

    /**
     * Tells whether Docker is installed and reachable. Prints "ABSENT", "NOPERM", or "OK &lt;version&gt;".
     */
    public static final String STATUS = "if ! command -v docker >/dev/null 2>&1; then echo ABSENT; exit 0; fi; "
            + "V=$(docker version --format '{{.Server.Version}}' 2>/dev/null) && printf 'OK %s\\n' \"$V\" || echo NOPERM";

    public static final String LIST = "docker ps -a --no-trunc --format '{{json .}}'";
    public static final String STATS = "docker stats --no-stream --no-trunc --format '{{json .}}'";

    public static DockerStatus parseStatus(String output, boolean needsRoot) {
        String text = output.strip();
        if (text.startsWith("OK")) {
            return new DockerStatus(Availability.USABLE, text.substring(2).strip(), needsRoot);
        }
        if (text.equals("NOPERM")) {
            return new DockerStatus(Availability.NO_PERMISSION, "", needsRoot);
        }
        return new DockerStatus(Availability.ABSENT, "", false);
    }

    public static String inspect(String id) {
        return "docker inspect " + ShellQuote.quote(id);
    }

    /**
     * The last {@code lines} lines of a container's logs, output and errors together, without following.
     */
    public static String logs(String id, int lines) {
        return "docker logs --tail " + Math.max(1, lines) + " --timestamps " + ShellQuote.quote(id) + " 2>&1";
    }

    public static String start(String id) {
        return "docker start " + ShellQuote.quote(id);
    }

    public static String stop(String id) {
        return "docker stop " + ShellQuote.quote(id);
    }

    public static String restart(String id) {
        return "docker restart " + ShellQuote.quote(id);
    }

    /**
     * Removes a container, stopping it first if it is running.
     */
    public static String remove(String id) {
        return "docker rm -f " + ShellQuote.quote(id);
    }

    /**
     * Runs one command inside a container through its shell; not interactive.
     */
    public static String exec(String id, String command) {
        return "docker exec " + ShellQuote.quote(id) + " /bin/sh -c " + ShellQuote.quote(command);
    }

    /**
     * Restarts several containers at once, e.g. a whole Compose stack.
     */
    public static String restartAll(List<String> ids) {
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("No containers to restart");
        }
        StringBuilder command = new StringBuilder("docker restart");
        for (String id : ids) {
            command.append(' ').append(ShellQuote.quote(id));
        }
        return command.toString();
    }

    /**
     * Frees disk space: removes stopped containers, unused networks, dangling images and build cache.
     */
    public static final String PRUNE = "docker system prune -f";

    /**
     * The space {@code docker system prune} reclaimed, in bytes, from its "Total reclaimed space" line.
     */
    public static long parseReclaimed(String output) {
        for (String line : output.split("\\R")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("reclaimed space")) {
                int colon = line.indexOf(':');
                if (colon >= 0) {
                    return parseSize(line.substring(colon + 1));
                }
            }
        }
        return 0;
    }

    // Images

    public static final String IMAGES = "docker images --format '{{json .}}'";

    public static String removeImage(String id) {
        return "docker rmi " + ShellQuote.quote(id);
    }

    /**
     * Removes only dangling (untagged) images.
     */
    public static final String PRUNE_IMAGES = "docker image prune -f";

    public static List<DockerImage> parseImages(String output) {
        List<DockerImage> images = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(line);
                String id = node.path("ID").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                String repository = node.path("Repository").asText("");
                String tag = node.path("Tag").asText("");
                boolean dangling = repository.equals("<none>") || repository.isEmpty();
                images.add(new DockerImage(id, repository, tag, parseSize(node.path("Size").asText("0B")),
                        node.path("CreatedSince").asText(""), dangling || tag.equals("<none>")));
            } catch (com.fasterxml.jackson.core.JacksonException ignored) {
                // Skip.
            }
        }
        return images;
    }

    // Networks

    public static final String NETWORKS = "docker network ls --format '{{json .}}'";

    public static List<DockerNetwork> parseNetworks(String output) {
        List<DockerNetwork> networks = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(line);
                String id = node.path("ID").asText("");
                String name = node.path("Name").asText("");
                if (id.isEmpty() || name.isEmpty()) {
                    continue;
                }
                networks.add(new DockerNetwork(id, name, node.path("Driver").asText(""),
                        node.path("Scope").asText(""), List.of()));
            } catch (com.fasterxml.jackson.core.JacksonException ignored) {
                // Skip.
            }
        }
        return networks;
    }

    // Run a new container

    /**
     * Starts a new detached container from an image, with optional published ports and a name.
     *
     * @param ports each entry a "hostPort:containerPort" mapping
     */
    public static String run(String image, String name, List<String> ports) {
        StringBuilder command = new StringBuilder("docker run -d");
        if (name != null && !name.isBlank()) {
            command.append(" --name ").append(ShellQuote.quote(name));
        }
        if (ports != null) {
            for (String port : ports) {
                command.append(" -p ").append(ShellQuote.quote(port));
            }
        }
        command.append(' ').append(ShellQuote.quote(image));
        return command.toString();
    }

    /**
     * Why an image reference cannot be used, or empty when it can.
     */
    public static java.util.Optional<String> imageProblem(String image) {
        String value = image == null ? "" : image.strip();
        if (value.isEmpty()) {
            return java.util.Optional.of("Type an image, e.g. redis:alpine");
        }
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._/:@-]*")) {
            return java.util.Optional.of("An image name uses letters, digits and . _ - / : @");
        }
        return java.util.Optional.empty();
    }

    /**
     * A "hostPort:containerPort" mapping from two ports, or empty when either is out of range.
     */
    public static java.util.Optional<String> portMapping(String hostPort, String containerPort) {
        try {
            int host = Integer.parseInt(hostPort.strip());
            int container = Integer.parseInt(containerPort.strip());
            if (host < 1 || host > 65535 || container < 1 || container > 65535) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(host + ":" + container);
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    public static List<DockerContainer> parseList(String output) {
        List<DockerContainer> containers = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(line);
                String id = node.path("ID").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                String status = node.path("Status").asText("");
                Map<String, String> labels = parseLabels(node.path("Labels").asText(""));
                containers.add(new DockerContainer(id, node.path("Names").asText(""), node.path("Image").asText(""),
                        ContainerState.parse(node.path("State").asText("")), Health.fromStatus(status), status,
                        labels.getOrDefault("com.docker.compose.project", ""),
                        labels.getOrDefault("com.docker.compose.service", ""),
                        parsePorts(node.path("Ports").asText("")), null));
            } catch (com.fasterxml.jackson.core.JacksonException ignored) {
                // A line that is not the JSON we asked for is skipped.
            }
        }
        return containers;
    }

    /**
     * The networks each container is on, container name to its network names, read from the same {@code docker ps}.
     */
    public static Map<String, List<String>> parseNetworkMembership(String output) {
        Map<String, List<String>> membership = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(line);
                String name = node.path("Names").asText("");
                String networks = node.path("Networks").asText("");
                if (!name.isEmpty() && !networks.isBlank()) {
                    membership.put(name, List.of(networks.split(",")));
                }
            } catch (com.fasterxml.jackson.core.JacksonException ignored) {
                // Skip.
            }
        }
        return membership;
    }

    private static Map<String, String> parseLabels(String labels) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : labels.split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                map.put(pair.substring(0, equals).strip(), pair.substring(equals + 1).strip());
            }
        }
        return map;
    }

    /**
     * Container id (short or full) to its live figures.
     */
    public static Map<String, ContainerStats> parseStats(String output) {
        Map<String, ContainerStats> stats = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(line);
                String id = node.hasNonNull("ID") ? node.get("ID").asText() : node.path("Container").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                String[] memory = node.path("MemUsage").asText("0B / 0B").split("/");
                stats.put(id, new ContainerStats(
                        parsePercent(node.path("CPUPerc").asText("0%")),
                        parseSize(memory[0]),
                        memory.length > 1 ? parseSize(memory[1]) : 0,
                        parsePercent(node.path("MemPerc").asText("0%"))));
            } catch (com.fasterxml.jackson.core.JacksonException ignored) {
                // Skip an unexpected line.
            }
        }
        return stats;
    }

    /**
     * The result of {@code docker inspect}: a one-element JSON array.
     */
    public static ContainerDetails parseInspect(String output) {
        try {
            JsonNode root = MAPPER.readTree(output);
            JsonNode c = root.isArray() && root.size() > 0 ? root.get(0) : root;
            if (c == null || !c.isObject()) {
                return null;
            }
            JsonNode config = c.path("Config");
            JsonNode hostConfig = c.path("HostConfig");
            String command = joinArray(c.path("Path"), c.path("Args"));
            String restart = hostConfig.path("RestartPolicy").path("Name").asText("no");

            return new ContainerDetails(
                    c.path("Id").asText(""),
                    stripSlash(c.path("Name").asText("")),
                    config.path("Image").asText(""),
                    ContainerState.parse(c.path("State").path("Status").asText("")),
                    command,
                    restart.isBlank() ? "no" : restart,
                    c.path("Created").asText(""),
                    parseInspectPorts(c.path("NetworkSettings").path("Ports")),
                    parseMounts(c.path("Mounts")),
                    parseEnv(config.path("Env")));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
    }

    // Ports

    /**
     * Reads the "Ports" column: "0.0.0.0:8080-&gt;8080/tcp, :::8080-&gt;8080/tcp, 5432/tcp".
     */
    static List<PortMapping> parsePorts(String text) {
        Map<String, PortMapping> byPort = new LinkedHashMap<>();
        for (String token : text.split(",")) {
            String part = token.strip();
            if (part.isEmpty()) {
                continue;
            }
            PortMapping mapping = parsePort(part);
            if (mapping == null) {
                continue;
            }
            String key = mapping.containerPort() + "/" + mapping.protocol();
            PortMapping existing = byPort.get(key);
            // Prefer a published mapping, and among those an IPv4 host over "::".
            if (existing == null || !existing.isPublished() && mapping.isPublished()
                    || existing.isPublished() && mapping.isPublished() && existing.hostIp().contains(":")
                    && !mapping.hostIp().contains(":")) {
                byPort.put(key, mapping);
            }
        }
        return List.copyOf(byPort.values());
    }

    private static PortMapping parsePort(String token) {
        int arrow = token.indexOf("->");
        try {
            if (arrow < 0) {
                String[] inside = token.split("/");
                return PortMapping.exposedOnly(Integer.parseInt(inside[0].strip()),
                        inside.length > 1 ? inside[1].strip() : "tcp");
            }
            String host = token.substring(0, arrow).strip();
            String[] inside = token.substring(arrow + 2).strip().split("/");
            int colon = host.lastIndexOf(':');
            String ip = colon < 0 ? "" : host.substring(0, colon);
            int hostPort = Integer.parseInt(host.substring(colon + 1).strip());
            return PortMapping.published(ip, hostPort, Integer.parseInt(inside[0].strip()),
                    inside.length > 1 ? inside[1].strip() : "tcp");
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static List<PortMapping> parseInspectPorts(JsonNode ports) {
        List<PortMapping> result = new ArrayList<>();
        if (!ports.isObject()) {
            return result;
        }
        ports.fields().forEachRemaining(entry -> {
            String[] portProto = entry.getKey().split("/");
            int containerPort;
            try {
                containerPort = Integer.parseInt(portProto[0]);
            } catch (NumberFormatException e) {
                return;
            }
            String proto = portProto.length > 1 ? portProto[1] : "tcp";
            JsonNode bindings = entry.getValue();
            if (bindings.isArray() && bindings.size() > 0) {
                for (JsonNode binding : bindings) {
                    try {
                        result.add(PortMapping.published(binding.path("HostIp").asText(""),
                                Integer.parseInt(binding.path("HostPort").asText("0")), containerPort, proto));
                    } catch (NumberFormatException ignored) {
                        // A binding without a numeric host port is skipped.
                    }
                }
            } else {
                result.add(PortMapping.exposedOnly(containerPort, proto));
            }
        });
        return result;
    }

    private static List<EnvVar> parseEnv(JsonNode env) {
        List<EnvVar> result = new ArrayList<>();
        if (env.isArray()) {
            for (JsonNode line : env) {
                result.add(EnvVar.parse(line.asText()));
            }
        }
        return result;
    }

    private static List<Mount> parseMounts(JsonNode mounts) {
        List<Mount> result = new ArrayList<>();
        if (mounts.isArray()) {
            for (JsonNode mount : mounts) {
                String source = mount.hasNonNull("Source") ? mount.get("Source").asText()
                        : mount.path("Name").asText("");
                result.add(new Mount(mount.path("Type").asText("volume"), source,
                        mount.path("Destination").asText(""), mount.path("RW").asBoolean(true)));
            }
        }
        return result;
    }

    private static String joinArray(JsonNode path, JsonNode args) {
        StringBuilder command = new StringBuilder(path.asText(""));
        if (args.isArray()) {
            for (JsonNode arg : args) {
                command.append(' ').append(arg.asText());
            }
        }
        return command.toString().strip();
    }

    private static String stripSlash(String name) {
        return name.startsWith("/") ? name.substring(1) : name;
    }

    static double parsePercent(String value) {
        try {
            return Double.parseDouble(value.replace("%", "").strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Reads a humanised size such as "25.5MiB", "1.945GiB" or "0B" into bytes.
     */
    static long parseSize(String value) {
        String text = value.strip();
        int i = 0;
        while (i < text.length() && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
            i++;
        }
        if (i == 0) {
            return 0;
        }
        try {
            double number = Double.parseDouble(text.substring(0, i));
            String unit = text.substring(i).strip().toLowerCase(Locale.ROOT);
            double factor = switch (unit) {
                case "b", "" -> 1;
                case "kb" -> 1_000d;
                case "kib" -> 1024d;
                case "mb" -> 1_000_000d;
                case "mib" -> 1024d * 1024;
                case "gb" -> 1_000_000_000d;
                case "gib" -> 1024d * 1024 * 1024;
                case "tb" -> 1_000_000_000_000d;
                case "tib" -> 1024d * 1024 * 1024 * 1024;
                default -> 1;
            };
            return Math.round(number * factor);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
