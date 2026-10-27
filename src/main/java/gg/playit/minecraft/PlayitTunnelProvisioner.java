package gg.playit.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Provision separate TCP/UDP tunnels using the current Playit v1 API. */
public final class PlayitTunnelProvisioner {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String secret;

    public PlayitTunnelProvisioner(String secret) { this.secret = secret; }

    public List<String> ensure(boolean javaEnabled, boolean bedrockEnabled, boolean voiceEnabled,
                               int javaPort, int bedrockPort, int voicePort) throws IOException {
        JsonNode runData = call("/v1/agents/rundata", mapper.createObjectNode());
        String agentId = runData.path("agent_id").asText();
        if (agentId.isBlank()) throw new IOException("API did not return agent_id");
        JsonNode tunnels = call("/v1/tunnels/list", mapper.createObjectNode()).path("tunnels");
        if (!tunnels.isArray()) throw new IOException("Unable to check for existing tunnels; creation cancelled");

        List<String> results = new ArrayList<>();
        if (javaEnabled) ensureOne(tunnels, agentId, "Java", "minecraft-java", "tcp", javaPort, results);
        if (bedrockEnabled) ensureOne(tunnels, agentId, "Bedrock", "minecraft-bedrock", "udp", bedrockPort, results);
        if (voiceEnabled) ensureOne(tunnels, agentId, "Voice", null, "udp", voicePort, results);
        return results;
    }

    private void ensureOne(JsonNode tunnels, String agentId, String label, String preset,
                           String proto, int port, List<String> results) throws IOException {
        if (port < 1 || port > 65535) {
            results.add(label + ": invalid local port " + port); return;
        }
        if (exists(tunnels, agentId, label, preset, proto, port)) {
            if ("Java".equals(label)) {
                checkJavaDestination(tunnels, agentId, port, results);
            } else {
                results.add(label + ": already exists for this agent");
            }
            return;
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("name", "VoxelHorizons " + label);
        ObjectNode protocol = body.putObject("protocol");
        if (preset != null) {
            protocol.put("type", "tunnel-type").put("details", preset);
        } else {
            ObjectNode raw = protocol.put("type", "raw-ports").putObject("details");
            raw.put("port_type", "udp").put("port_count", 1)
                    .put("software_description", "Simple Voice Chat");
        }
        ObjectNode origin = body.putObject("origin");
        origin.put("type", "agent");
        ObjectNode info = origin.putObject("data");
        info.put("agent_id", agentId);
        ArrayNode fields = info.putObject("config").putArray("fields");
        fields.addObject().put("name", "local_ip").put("value", "127.0.0.1");
        fields.addObject().put("name", "local_port").put("value", String.valueOf(port));
        body.putObject("endpoint").put("type", "region")
                .putObject("details").put("region", "global");
        body.put("enabled", true);
        try {
            call("/v1/tunnels/create", body);
            results.add(label + ": creation accepted (" + proto + " -> 127.0.0.1:" + port
                    + "); check /playit tunnels for the public address");
        } catch (IOException e) {
            if ("Bedrock".equals(label)) {
                // The preset can be restricted by the account/API. The single
                // port UDP transport works for a standard Geyser RakNet listener.
                ObjectNode fallback = body.deepCopy();
                ObjectNode fallbackProtocol = fallback.putObject("protocol");
                ObjectNode raw = fallbackProtocol.put("type", "raw-ports").putObject("details");
                raw.put("port_type", "udp").put("port_count", 1)
                        .put("software_description", "Geyser Bedrock");
                try {
                    call("/v1/tunnels/create", fallback);
                    results.add("Bedrock: preset unavailable; custom UDP tunnel creation accepted"
                            + " (127.0.0.1:" + port + ")");
                    return;
                } catch (IOException fallbackError) {
                    results.add("Bedrock: preset failed (" + e.getMessage()
                            + "), custom UDP fallback also failed (" + fallbackError.getMessage() + ")");
                    return;
                }
            }
            results.add(label + ": API rejected creation: " + e.getMessage());
        }
    }

    private void checkJavaDestination(JsonNode tunnels, String agentId, int port,
                                      List<String> results) {
        for (JsonNode tunnel : tunnels) {
            JsonNode origin = tunnel.path("origin");
            JsonNode details = origin.path("details");
            if (!"minecraft-java".equals(tunnel.path("tunnel_type").asText())
                    || !"tcp".equals(tunnel.path("port_type").asText())
                    || !"agent".equals(origin.path("type").asText())
                    || !agentId.equals(details.path("agent_id").asText())) continue;

            ArrayNode fields = mapper.createArrayNode();
            JsonNode existing = details.path("config_data").path("fields");
            if (existing.isArray()) existing.forEach(field -> fields.add(field.deepCopy()));

            for (JsonNode field : fields) {
                if ("local_port".equals(field.path("name").asText())) {
                    int configured = field.path("value").asInt(-1);
                    results.add(configured == port
                            ? "Java: existing local destination TCP " + port
                            : "Java: existing local port " + configured
                                + " differs from server port " + port + "; check dashboard");
                    return;
                }
            }

            String id = tunnel.path("id").asText();
            if (id.isBlank()) {
                results.add("Java: missing local port, but cannot identify tunnel to repair");
                return;
            }

            boolean hasIp = false;
            for (JsonNode field : fields) {
                if ("local_ip".equals(field.path("name").asText())) hasIp = true;
            }
            if (!hasIp) fields.addObject().put("name", "local_ip").put("value", "127.0.0.1");
            fields.addObject().put("name", "local_port").put("value", Integer.toString(port));

            ObjectNode update = mapper.createObjectNode();
            update.put("tunnel_id", id);
            update.putObject("new_config").set("fields", fields);
            try {
                call("/v1/tunnels/config", update);
                results.add("Java: repaired missing destination port to TCP " + port
                        + " (public address unchanged)");
            } catch (IOException error) {
                results.add("Java: destination port missing; API repair failed: "
                        + error.getMessage() + "; set local port " + port + " in dashboard");
            }
            return;
        }
    }

    static boolean exists(JsonNode tunnels, String agentId, String label,
                          String preset, String protocol, int port) {
        for (JsonNode tunnel : tunnels) {
            JsonNode origin = tunnel.path("origin");
            if (!"agent".equals(origin.path("type").asText())) continue;
            JsonNode details = origin.path("details");
            if (!agentId.equals(details.path("agent_id").asText())) continue;
            String type = tunnel.path("tunnel_type").asText();
            String proto = tunnel.path("port_type").asText();
            String name = tunnel.path("name").asText("");
            int localPort = -1;
            JsonNode fields = details.path("config_data").path("fields");
            if (fields.isArray()) for (JsonNode f : fields) {
                if ("local_port".equals(f.path("name").asText())) {
                    localPort = f.path("value").asInt(-1);
                }
            }
            if (preset != null && preset.equals(type)) return true;
            if (protocol.equals(proto) && localPort == port) return true;
            if (preset == null && "udp".equals(proto) &&
                    name.toLowerCase(java.util.Locale.ROOT).contains("voice")) return true;
        }
        return false;
    }

    private JsonNode call(String route, JsonNode payload) throws IOException {
        if (secret == null || secret.length() < 32) throw new IOException("Missing agent secret");
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.playit.gg" + route))
                .header("Authorization", "agent-key " + secret)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response;
        try { response = client.send(request, HttpResponse.BodyHandlers.ofString()); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Playit API request interrupted", e);
        }
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        JsonNode root = mapper.readTree(response.body());
        if (!"success".equals(root.path("status").asText())) {
            throw new IOException("API status " + root.path("status").asText() + ": "
                    + root.path("data").toString().substring(0,
                        Math.min(150, root.path("data").toString().length())));
        }
        return root.path("data");
    }
}
