package gg.playit.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Read the modern agent run-data endpoint. Tunnel creation in the dashboard is
 * intentionally separate: this API is used only for honest status reporting.
 */
public final class PlayitTunnelInspector {
    private static final URI ENDPOINT = URI.create("https://api.playit.gg/v1/agents/rundata");

    private PlayitTunnelInspector() {}

    public static List<String> inspect(String secret, int bedrockPort, int voicePort) throws IOException {
        if (secret == null || secret.length() < 32) {
            throw new IOException("agent-secret is not configured");
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .header("Authorization", "agent-key " + secret)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while querying tunnel status", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Playit API returned HTTP " + response.statusCode());
        }

        JsonNode root = new ObjectMapper().readTree(response.body());
        if (!"success".equals(root.path("status").asText())) {
            throw new IOException("Playit API did not return tunnel run-data");
        }

        List<String> messages = new ArrayList<>();
        JsonNode tunnels = root.path("data").path("tunnels");
        if (!tunnels.isArray() || tunnels.isEmpty()) {
            messages.add("No tunnels assigned to this agent. Create them at https://playit.gg/account/tunnels");
            return messages;
        }

        messages.add("Assigned Playit tunnels (availability/packet forwarding not yet verified):");
        for (JsonNode tunnel : tunnels) {
            String name = safeText(tunnel.path("name"), "Unnamed");
            String type = safeText(tunnel.path("tunnel_type_display"),
                    safeText(tunnel.path("tunnel_type"), "Custom"));
            String address = safeText(tunnel.path("display_address"), "address pending");
            String protocol = safeText(tunnel.path("port_type"), "unknown");
            String disabled = safeText(tunnel.path("disabled_reason"), "");
            int localPort = resolveLocalPort(tunnel);
            String purpose;
            if (localPort == bedrockPort && "udp".equalsIgnoreCase(protocol)) {
                purpose = "Bedrock";
            } else if (localPort == voicePort && "udp".equalsIgnoreCase(protocol)) {
                purpose = "Voice";
            } else if ("tcp".equalsIgnoreCase(protocol)) {
                purpose = "Java / TCP";
            } else {
                purpose = "Unclassified";
            }
            messages.add(" - " + purpose + ": " + name + " (" + type + ", "
                    + protocol + ") => " + address + " [local port: "
                    + (localPort > 0 ? localPort : "check dashboard") + "]"
                    + (disabled.isEmpty() ? "" : " [disabled: " + disabled + "]"));
            if ("Java / TCP".equals(purpose) && localPort <= 0) {
                messages.add("   WARNING: No explicit local Java port is reported! Native Playit "
                        + "may skip this tunnel if the public address has no numeric port.");
                messages.add("   Run /playit createtunnels to check/repair the Java destination "
                        + "or set the local port in the Playit dashboard.");
            }
            if ("Voice".equals(purpose) && !"address pending".equals(address)) {
                messages.add("   Simple Voice Chat: set voice_host=" + address
                        + " in plugins/voicechat/voicechat-server.properties");
            }
        }
        return messages;
    }

    private static int resolveLocalPort(JsonNode tunnel) {
        int port = tunnel.path("local_port").asInt(0);
        if (port > 0) {
            return port;
        }
        JsonNode fields = tunnel.path("agent_config").path("fields");
        if (fields.isArray()) {
            for (JsonNode field : fields) {
                String name = field.path("name").asText().replace('-', '_');
                if ("local_port".equals(name) || "port".equals(name)) {
                    try {
                        return Integer.parseInt(field.path("value").asText());
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                }
            }
        }
        return 0;
    }

    private static String safeText(JsonNode field, String fallback) {
        return field.isMissingNode() || field.isNull() || field.asText().isBlank()
                ? fallback : field.asText().replace("\u00a7", "");
    }
}
