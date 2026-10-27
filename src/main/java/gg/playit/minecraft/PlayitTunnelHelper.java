package gg.playit.minecraft;

import gg.playit.api.ApiClient;
import gg.playit.api.ApiError;
import gg.playit.api.actions.CreateTunnel;
import gg.playit.api.models.AccountTunnel;
import gg.playit.api.models.AccountTunnels;
import gg.playit.api.models.PortType;
import gg.playit.api.models.TunnelType;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Helper class for managing playit.gg tunnels.
 * Provides functionality to list, check, and create Java and Bedrock tunnels.
 */
public class PlayitTunnelHelper {
    private static final Logger log = Logger.getLogger(PlayitTunnelHelper.class.getName());

    private final ApiClient api;
    private final String agentId;
    private final int javaLocalPort;
    private final int bedrockLocalPort;

    /**
     * Create a new tunnel helper.
     *
     * @param secretKey       The playit.gg secret key for API authentication
     * @param agentId         The agent ID for this server
     * @param javaLocalPort   Local port for Java tunnel (typically 25565)
     * @param bedrockLocalPort Local port for Bedrock tunnel (typically 19132)
     */
    public PlayitTunnelHelper(String secretKey, String agentId, int javaLocalPort, int bedrockLocalPort) {
        this.api = new ApiClient(secretKey);
        this.agentId = agentId;
        this.javaLocalPort = javaLocalPort;
        this.bedrockLocalPort = bedrockLocalPort;
    }

    /**
     * Result of tunnel check/creation operations.
     */
    public static class TunnelStatus {
        public boolean hasJavaTunnel = false;
        public boolean hasBedrockTunnel = false;
        public String javaTunnelAddress = null;
        public String bedrockTunnelAddress = null;
        public boolean javaCreated = false;
        public boolean bedrockCreated = false;
        public String errorMessage = null;
    }

    /**
     * List existing tunnels and check which ones are present.
     *
     * @return TunnelStatus with information about existing tunnels
     */
    public TunnelStatus checkExistingTunnels() {
        TunnelStatus status = new TunnelStatus();

        try {
            AccountTunnels tunnels = api.listTunnels();

            for (AccountTunnel tunnel : tunnels.tunnels) {
                if (tunnel.tunnelType == TunnelType.MinecraftJava && tunnel.portType == PortType.TCP) {
                    status.hasJavaTunnel = true;
                    status.javaTunnelAddress = tunnel.displayAddress;
                    log.info("[PlayitTunnelHelper] Found existing Java tunnel: " + tunnel.displayAddress);
                }
                // Check for both MinecraftBedrock and Custom UDP tunnels (Custom is used as fallback for Bedrock)
                if ((tunnel.tunnelType == TunnelType.MinecraftBedrock
                            || (tunnel.tunnelType == TunnelType.Custom
                                && tunnel.name != null
                                && (tunnel.name.toLowerCase(java.util.Locale.ROOT).contains("bedrock")
                                    || tunnel.name.toLowerCase(java.util.Locale.ROOT).contains("geyser"))))
                            && tunnel.portType == PortType.UDP) {
                    status.hasBedrockTunnel = true;
                    status.bedrockTunnelAddress = tunnel.displayAddress;
                    log.info("[PlayitTunnelHelper] Found existing Bedrock/UDP tunnel: " + tunnel.displayAddress + " (type: " + tunnel.tunnelType + ")");
                }
            }

            log.info("[PlayitTunnelHelper] Tunnel check complete - Java: " + status.hasJavaTunnel + ", Bedrock: " + status.hasBedrockTunnel);
        } catch (ApiError e) {
            status.errorMessage = "API error while listing tunnels: " + e.getMessage();
            log.warning("[PlayitTunnelHelper] " + status.errorMessage);
        } catch (IOException e) {
            status.errorMessage = "IO error while listing tunnels: " + e.getMessage();
            log.severe("[PlayitTunnelHelper] " + status.errorMessage);
        }

        return status;
    }

    /**
     * Create a Java (TCP) tunnel if it doesn't already exist.
     *
     * @param status The current tunnel status (will be updated)
     * @return true if tunnel was created or already exists, false on error
     */
    public boolean createJavaTunnelIfMissing(TunnelStatus status) {
        if (status.hasJavaTunnel) {
            log.info("[PlayitTunnelHelper] Java tunnel already exists: " + status.javaTunnelAddress);
            return true;
        }

        try {
            log.info("[PlayitTunnelHelper] Creating Java (TCP) tunnel on local port " + javaLocalPort);

            CreateTunnel create = new CreateTunnel();
            create.localIp = "127.0.0.1";
            create.localPort = javaLocalPort;
            create.portCount = 1;
            create.portType = PortType.TCP;
            create.tunnelType = TunnelType.MinecraftJava;
            create.agentId = agentId;

            log.info("[PlayitTunnelHelper] API Request - Creating Java tunnel: tunnel_type=minecraft-java, port_type=tcp, local_ip=127.0.0.1, local_port=" + javaLocalPort + ", agent_id=" + agentId);
            api.createTunnel(create);
            status.javaCreated = true;
            log.info("[PlayitTunnelHelper] Successfully created Java tunnel");
            return true;
        } catch (ApiError e) {
            if (e.responseBody != null && e.responseBody.contains("tunnel already exists")) {
                log.info("[PlayitTunnelHelper] Java tunnel already exists (API response)");
                log.warning("[PlayitTunnelHelper] If local_port mismatch, update in playit dashboard: https://playit.gg/account");
                status.hasJavaTunnel = true;
                return true;
            }
            status.errorMessage = "API error creating Java tunnel: " + e.getMessage();
            log.warning("[PlayitTunnelHelper] " + status.errorMessage);
            log.warning("[PlayitTunnelHelper] API Response: " + e.responseBody);
            logTunnelCreationHelp("Java", e);
            return false;
        } catch (IOException e) {
            status.errorMessage = "IO error creating Java tunnel: " + e.getMessage();
            log.severe("[PlayitTunnelHelper] " + status.errorMessage);
            return false;
        }
    }

    /**
     * Create a Bedrock (UDP) tunnel if it doesn't already exist.
     * Uses Custom tunnel type to avoid "Invalid Origin" errors from the playit.gg API.
     *
     * @param status The current tunnel status (will be updated)
     * @return true if tunnel was created or already exists, false on error
     */
    public boolean createBedrockTunnelIfMissing(TunnelStatus status) {
        if (status.hasBedrockTunnel) {
            log.info("[PlayitTunnelHelper] Bedrock tunnel already exists: " + status.bedrockTunnelAddress);
            return true;
        }

        // Try creating with Custom UDP type first to avoid "Invalid Origin" errors
        try {
            log.info("[PlayitTunnelHelper] Creating Bedrock (UDP) tunnel on local port " + bedrockLocalPort + " using Custom tunnel type");

            CreateTunnel create = new CreateTunnel();
            create.localIp = "127.0.0.1";
            create.localPort = bedrockLocalPort;
            create.portCount = 1;
            create.portType = PortType.UDP;
            create.tunnelType = TunnelType.Custom;
            create.agentId = agentId;

            log.info("[PlayitTunnelHelper] API Request - Creating Bedrock tunnel: tunnel_type=custom, port_type=udp, local_ip=127.0.0.1, local_port=" + bedrockLocalPort + ", agent_id=" + agentId);
            api.createTunnel(create);
            status.bedrockCreated = true;
            log.info("[PlayitTunnelHelper] Successfully created Bedrock tunnel (Custom UDP)");
            return true;
        } catch (ApiError e) {
            if (e.responseBody != null && e.responseBody.contains("tunnel already exists")) {
                log.info("[PlayitTunnelHelper] Bedrock tunnel already exists (API response)");
                log.warning("[PlayitTunnelHelper] If local_port mismatch, update in playit dashboard: https://playit.gg/account");
                status.hasBedrockTunnel = true;
                return true;
            }
            
            // If Custom type fails, try MinecraftBedrock as fallback
            log.warning("[PlayitTunnelHelper] Custom UDP tunnel creation failed: " + e.getMessage());
            log.warning("[PlayitTunnelHelper] API Response: " + e.responseBody);
            log.info("[PlayitTunnelHelper] Trying fallback with MinecraftBedrock tunnel type...");
            
            return createBedrockTunnelWithFallback(status, e);
        } catch (IOException e) {
            status.errorMessage = "IO error creating Bedrock tunnel: " + e.getMessage();
            log.severe("[PlayitTunnelHelper] " + status.errorMessage);
            return false;
        }
    }

    /**
     * Fallback method to create Bedrock tunnel with MinecraftBedrock type.
     * Called when Custom tunnel type fails with the given originalError.
     */
    private boolean createBedrockTunnelWithFallback(TunnelStatus status, ApiError originalError) {
        try {
            log.info("[PlayitTunnelHelper] Creating Bedrock tunnel with MinecraftBedrock type as fallback (original error: " + originalError.statusCode + ")");

            CreateTunnel create = new CreateTunnel();
            create.localIp = "127.0.0.1";
            create.localPort = bedrockLocalPort;
            create.portCount = 1;
            create.portType = PortType.UDP;
            create.tunnelType = TunnelType.MinecraftBedrock;
            create.agentId = agentId;

            log.info("[PlayitTunnelHelper] API Request (Fallback) - Creating Bedrock tunnel: tunnel_type=minecraft-bedrock, port_type=udp, local_ip=127.0.0.1, local_port=" + bedrockLocalPort + ", agent_id=" + agentId);
            api.createTunnel(create);
            status.bedrockCreated = true;
            log.info("[PlayitTunnelHelper] Successfully created Bedrock tunnel (MinecraftBedrock fallback)");
            return true;
        } catch (ApiError e) {
            if (e.responseBody != null && e.responseBody.contains("tunnel already exists")) {
                log.info("[PlayitTunnelHelper] Bedrock tunnel already exists (API response)");
                log.warning("[PlayitTunnelHelper] If local_port mismatch, update in playit dashboard: https://playit.gg/account");
                status.hasBedrockTunnel = true;
                return true;
            }
            
            // Both attempts failed - include original error info
            status.errorMessage = "API error creating Bedrock tunnel (both Custom and MinecraftBedrock types failed). " +
                    "Custom error: " + originalError.statusCode + ", Fallback error: " + e.statusCode;
            log.warning("[PlayitTunnelHelper] " + status.errorMessage);
            log.warning("[PlayitTunnelHelper] Original Custom type error: " + originalError.responseBody);
            log.warning("[PlayitTunnelHelper] Fallback MinecraftBedrock type error: " + e.responseBody);
            
            if ((e.responseBody != null && e.responseBody.contains("Invalid Origin")) ||
                (originalError.responseBody != null && originalError.responseBody.contains("Invalid Origin"))) {
                log.warning("[PlayitTunnelHelper] 'Invalid Origin' error occurred. This may be a playit.gg API limitation.");
                log.warning("[PlayitTunnelHelper] Please create the Bedrock tunnel manually at: https://playit.gg/account");
                log.warning("[PlayitTunnelHelper] When creating manually, set local_port to " + bedrockLocalPort + " (your Geyser port)");
            }
            
            logTunnelCreationHelp("Bedrock", e);
            return false;
        } catch (IOException e) {
            status.errorMessage = "IO error creating Bedrock tunnel (fallback): " + e.getMessage();
            log.severe("[PlayitTunnelHelper] " + status.errorMessage);
            return false;
        }
    }

    /**
     * Create both Java and Bedrock tunnels if they don't exist.
     * 
     * @param createBedrock Whether to create a Bedrock tunnel
     * @return TunnelStatus with results of the operation
     */
    public TunnelStatus createJavaAndBedrockTunnelsIfMissing(boolean createBedrock) {
        TunnelStatus status = checkExistingTunnels();

        if (status.errorMessage != null) {
            return status;
        }

        createJavaTunnelIfMissing(status);

        if (createBedrock) {
            createBedrockTunnelIfMissing(status);
        }

        // Re-check to get the tunnel addresses after creation
        if (status.javaCreated || status.bedrockCreated) {
            TunnelStatus updatedStatus = checkExistingTunnels();
            status.hasJavaTunnel = updatedStatus.hasJavaTunnel;
            status.hasBedrockTunnel = updatedStatus.hasBedrockTunnel;
            status.javaTunnelAddress = updatedStatus.javaTunnelAddress;
            status.bedrockTunnelAddress = updatedStatus.bedrockTunnelAddress;
        }

        return status;
    }

    /**
     * Log helpful information when tunnel creation fails.
     */
    private void logTunnelCreationHelp(String tunnelType, ApiError error) {
        log.warning("[PlayitTunnelHelper] Failed to create " + tunnelType + " tunnel. Possible causes:");
        log.warning("  - Account tunnel limit reached");
        log.warning("  - Tunnel already exists for this agent");
        log.warning("  - Invalid API credentials");
        
        if (error.statusCode == 400) {
            log.warning("[PlayitTunnelHelper] Recommended actions:");
            log.warning("  1. Visit https://playit.gg/account to check your tunnels");
            log.warning("  2. Delete unused tunnels if you've reached your limit");
            log.warning("  3. Verify your agent configuration");
        }
        
        if (error.statusCode == 401) {
            log.warning("[PlayitTunnelHelper] Authentication failed. Try resetting your agent:");
            log.warning("  Run: /playit agent reset");
        }
    }

    /**
     * Send an admin notification about Bedrock tunnel availability.
     * Uses a delayed task that may fail silently if plugin is not available.
     */
    public static void notifyAdminAboutBedrockTunnel() {
        String message = "[playit.gg] Geyser detected! Use '/playit createtunnels' to create a Bedrock tunnel for Bedrock players.";
        log.info(message);
        
        // Also notify online ops - wrapped in try-catch to be safe
        try {
            var plugin = Bukkit.getPluginManager().getPlugin("playit-gg");
            if (plugin != null && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (var player : Bukkit.getServer().getOnlinePlayers()) {
                        if (player.isOp()) {
                            player.sendMessage("§6[playit.gg]§r Geyser detected! Run §b/playit createtunnels§r to create a Bedrock tunnel.");
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.warning("[PlayitTunnelHelper] Failed to send in-game notification: " + e.getMessage());
        }
    }
}
