package gg.playit.minecraft;

import gg.playit.api.ApiClient;
import gg.playit.api.ApiError;
import gg.playit.api.actions.CreateTunnel;
import gg.playit.api.models.AccountTunnel;
import gg.playit.api.models.Notice;
import gg.playit.api.models.PortType;
import gg.playit.api.models.TunnelType;
import gg.playit.minecraft.utils.Hex;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class PlayitKeysSetup {
    private static final Logger log = Logger.getLogger(PlayitKeysSetup.class.getName());
    public final AtomicInteger state;
    public static final int STATE_INIT = 1;
    public static final int STATE_MISSING_SECRET = 2;
    public static final int STATE_CHECKING_SECRET = 3;
    public static final int STATE_CREATING_TUNNEL = 4;
    public static final int STATE_ERROR = 5;
    public static final int STATE_SHUTDOWN = 0;
    private final ApiClient openClient = new ApiClient(null);
    private final boolean isGeyserPresent;
    private final int javaLocalPort;
    private final int bedrockLocalPort;
    private final boolean autoCreateBedrockTunnel;
    private final boolean promptAdminForBedrock;
    private boolean adminPromptSent = false;

    public PlayitKeysSetup(String secretKey, AtomicInteger state, boolean isGeyserPresent, int javaLocalPort, int bedrockLocalPort,
                           boolean autoCreateBedrockTunnel, boolean promptAdminForBedrock) {
        keys.secretKey = secretKey;
        this.state = state;
        this.isGeyserPresent = isGeyserPresent;
        this.javaLocalPort = javaLocalPort;
        this.bedrockLocalPort = bedrockLocalPort;
        this.autoCreateBedrockTunnel = autoCreateBedrockTunnel;
        this.promptAdminForBedrock = promptAdminForBedrock;
    }

    private final PlayitKeys keys = new PlayitKeys();
    private String claimCode;

    public int getState() {
        return state.get();
    }

    public void shutdown() {
        this.state.set(STATE_SHUTDOWN);
    }

    public String getClaimCode() {
        return claimCode;
    }

    public PlayitKeys progress() throws IOException {
        switch (state.get()) {
            case STATE_INIT -> {
                if (keys.secretKey == null) {
                    state.compareAndSet(STATE_INIT, STATE_MISSING_SECRET);
                    return null;
                }

                state.compareAndSet(STATE_INIT, STATE_CHECKING_SECRET);
                log.info("secret key found, checking");
                return null;
            }
            case STATE_MISSING_SECRET -> {
                if (claimCode == null) {
                    byte[] array = new byte[8];
                    new Random().nextBytes(array);
                    claimCode = Hex.encodeHexString(array);
                    log.info("secret key not set, generate claim code: " + claimCode);
                }

                log.info("trying to exchange claim code for secret");
                keys.secretKey = openClient.exchangeClaimForSecret(claimCode);

                if (keys.secretKey == null) {
                    log.info("failed to exchange, to claim visit: https://playit.gg/mc/" + claimCode);
                } else {
                    state.compareAndSet(STATE_MISSING_SECRET, STATE_CHECKING_SECRET);
                }

                return null;
            }
            case STATE_CHECKING_SECRET -> {
                log.info("check secret");

                var api = new ApiClient(keys.secretKey);
                try {
                    var status = api.getStatus();

                    keys.isGuest = status.isGuest;
                    keys.isEmailVerified = status.emailVerified;
                    keys.agentId = status.agentId;
                    keys.notice = status.notice;

                    state.compareAndSet(STATE_CHECKING_SECRET, STATE_CREATING_TUNNEL);
                    return null;
                } catch (ApiError e) {
                    if (e.statusCode == 401 || e.statusCode == 400) {
                        if (claimCode == null) {
                            log.info("secret key invalid, starting over");
                            state.compareAndSet(STATE_CHECKING_SECRET, STATE_MISSING_SECRET);
                        } else {
                            state.compareAndSet(STATE_CHECKING_SECRET, STATE_ERROR);
                            log.info("secret failed verification after creating, moving to error state");
                        }

                        return null;
                    }

                    throw e;
                }
            }
            case STATE_CREATING_TUNNEL -> {
                var api = new ApiClient(keys.secretKey);

                var tunnels = api.listTunnels();
                keys.tunnelAddress = null;
                keys.bedrockTunnelAddress = null;

                boolean haveJava = false;
                boolean haveBedrock = false;

                for (AccountTunnel tunnel : tunnels.tunnels) {
                    if (tunnel.tunnelType == TunnelType.MinecraftJava) {
                        keys.tunnelAddress = tunnel.displayAddress;
                        haveJava = true;
                        log.info("found minecraft java tunnel: " + keys.tunnelAddress);
                    }
                    // Check for both MinecraftBedrock and Custom UDP tunnels (Custom is used as fallback for Bedrock)
                    if ((tunnel.tunnelType == TunnelType.MinecraftBedrock
                            || (tunnel.tunnelType == TunnelType.Custom
                                && tunnel.name != null
                                && (tunnel.name.toLowerCase(java.util.Locale.ROOT).contains("bedrock")
                                    || tunnel.name.toLowerCase(java.util.Locale.ROOT).contains("geyser"))))
                            && tunnel.portType == PortType.UDP) {
                        keys.bedrockTunnelAddress = tunnel.displayAddress;
                        haveBedrock = true;
                        log.info("found minecraft bedrock/UDP tunnel: " + tunnel.displayAddress + " (type: " + tunnel.tunnelType + ")");
                    }
                }

                // Always create Java tunnel if not found
                if (!haveJava) {
                    log.info("Creating new minecraft java tunnel on local port " + javaLocalPort);

                    var create = new CreateTunnel();
                    create.localIp = "127.0.0.1";
                    create.localPort = javaLocalPort;
                    create.portCount = 1;
                    create.portType = PortType.TCP;
                    create.tunnelType = TunnelType.MinecraftJava;
                    create.agentId = keys.agentId;

                    log.info("[PlayitKeysSetup] API Request - Creating Java tunnel: tunnel_type=minecraft-java, port_type=tcp, local_ip=127.0.0.1, local_port=" + javaLocalPort + ", agent_id=" + keys.agentId);
                    try {
                        api.createTunnel(create);
                        log.info("[PlayitKeysSetup] Successfully created Java tunnel request");
                    } catch (ApiError e) {
                        log.warning("[PlayitKeysSetup] Failed to create Java tunnel: " + e.getMessage());
                        if (e.responseBody != null && e.responseBody.contains("tunnel already exists")) {
                            log.info("[PlayitKeysSetup] Tunnel already exists for agent. Checking existing tunnels...");
                            log.warning("[PlayitKeysSetup] If local_port mismatch, update in playit dashboard: https://playit.gg/account");
                        } else {
                            log.warning("[PlayitKeysSetup] API Response: " + e.responseBody);
                            log.warning("[PlayitKeysSetup] Recommended: Visit https://playit.gg/account to check your tunnels");
                        }
                    }
                    return null; // Wait for tunnel to appear
                }

                // Handle Bedrock tunnel based on configuration
                if (isGeyserPresent && !haveBedrock) {
                    if (autoCreateBedrockTunnel) {
                        // Auto-create Bedrock tunnel using Custom UDP type to avoid "Invalid Origin" errors
                        log.info("auto_create_bedrock_tunnel is enabled, creating new Bedrock UDP tunnel on port " + bedrockLocalPort);
                        var create = new CreateTunnel();
                        create.localIp = "127.0.0.1";
                        create.localPort = bedrockLocalPort;
                        create.portCount = 1;
                        create.portType = PortType.UDP;
                        create.tunnelType = TunnelType.Custom;
                        create.agentId = keys.agentId;
                        
                        log.info("[PlayitKeysSetup] API Request - Creating Bedrock tunnel: tunnel_type=custom, port_type=udp, local_ip=127.0.0.1, local_port=" + bedrockLocalPort + ", agent_id=" + keys.agentId);
                        try {
                            api.createTunnel(create);
                            log.info("[PlayitKeysSetup] Successfully created Bedrock tunnel request");
                        } catch (ApiError e) {
                            log.warning("[PlayitKeysSetup] Failed to create Bedrock tunnel: " + e.getMessage());
                            if (e.responseBody != null) {
                                log.warning("[PlayitKeysSetup] API Response: " + e.responseBody);
                                if (e.responseBody.contains("tunnel already exists")) {
                                    log.info("[PlayitKeysSetup] Tunnel already exists for agent. Checking existing tunnels...");
                                    log.warning("[PlayitKeysSetup] If local_port mismatch, update in playit dashboard: https://playit.gg/account");
                                } else if (e.responseBody.contains("Invalid Origin")) {
                                    log.warning("[PlayitKeysSetup] 'Invalid Origin' error. This may be a playit.gg API limitation.");
                                    log.warning("[PlayitKeysSetup] Try creating the Bedrock tunnel manually at: https://playit.gg/account");
                                }
                            }
                            log.warning("[PlayitKeysSetup] Recommended: Visit https://playit.gg/account to manage your tunnels");
                        }
                        return null; // Wait for tunnel to appear
                    } else if (promptAdminForBedrock && !adminPromptSent) {
                        // Prompt admin to create Bedrock tunnel manually
                        log.info("==============================================");
                        log.info("[playit.gg] Geyser-Spigot detected!");
                        log.info("[playit.gg] To create a Bedrock tunnel for Bedrock/mobile players,");
                        log.info("[playit.gg] run: /playit createtunnels");
                        log.info("[playit.gg] Or set 'auto_create_bedrock_tunnel: true' in config.yml");
                        log.info("==============================================");
                        PlayitTunnelHelper.notifyAdminAboutBedrockTunnel();
                        adminPromptSent = true;
                    }
                }

                // Java tunnel is ready (Bedrock is optional unless auto_create is enabled)
                if (haveJava) {
                    return keys;
                }

                return null;
            }
            default -> {
                return null;
            }
        }
    }

    public static class PlayitKeys {
        public String secretKey;
        public String agentId;
        public String tunnelAddress;
        public String bedrockTunnelAddress;
        public boolean isGuest;
        public boolean isEmailVerified;
        public Notice notice;
    }
}
