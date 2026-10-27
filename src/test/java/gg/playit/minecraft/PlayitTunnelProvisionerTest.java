package gg.playit.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PlayitTunnelProvisionerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode tunnels(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void doesNotConfuseVoiceWithBedrock() throws Exception {
        JsonNode existing = tunnels("""
                [{"name":"VoxelHorizons Voice","tunnel_type":"custom",
                  "port_type":"udp","origin":{"type":"agent","details":{
                  "agent_id":"server-agent","config_data":{"fields":[
                  {"name":"local_ip","value":"127.0.0.1"},
                  {"name":"local_port","value":"24454"}]}}}}]
                """);
        assertTrue(PlayitTunnelProvisioner.exists(existing, "server-agent",
                "Voice", null, "udp", 24454));
        assertFalse(PlayitTunnelProvisioner.exists(existing, "server-agent",
                "Bedrock", "minecraft-bedrock", "udp", 19132));
        assertFalse(PlayitTunnelProvisioner.exists(existing, "server-agent",
                "Java", "minecraft-java", "tcp", 25565));
    }

    @Test
    void detectsExistingBedrockByTypeEvenWhenLocalPortDiffers() throws Exception {
        JsonNode existing = tunnels("""
                [{"name":"Bedrock","tunnel_type":"minecraft-bedrock",
                  "port_type":"udp","origin":{"type":"agent","details":{
                  "agent_id":"server-agent","config_data":{"fields":[
                  {"name":"local_port","value":"19134"}]}}}}]
                """);
        assertTrue(PlayitTunnelProvisioner.exists(existing, "server-agent",
                "Bedrock", "minecraft-bedrock", "udp", 19132));
    }

    @Test
    void ignoresTunnelsAssignedToOtherAgents() throws Exception {
        JsonNode existing = tunnels("""
                [{"name":"VoxelHorizons Voice","tunnel_type":"custom",
                  "port_type":"udp","origin":{"type":"agent","details":{
                  "agent_id":"other-agent","config_data":{"fields":[
                  {"name":"local_port","value":"24454"}]}}}}]
                """);
        assertFalse(PlayitTunnelProvisioner.exists(existing, "server-agent",
                "Voice", null, "udp", 24454));
    }

    @Test
    void ignoresNonAgentOrigins() throws Exception {
        JsonNode existing = tunnels("""
                [{"name":"Voice","tunnel_type":"custom","port_type":"udp",
                  "origin":{"type":"not-set"}}]
                """);
        assertFalse(PlayitTunnelProvisioner.exists(existing, "server-agent",
                "Voice", null, "udp", 24454));
    }

    @Test
    void doesNotCreateDuplicateJavaByType() throws Exception {
        JsonNode existing = tunnels("""
                [{"name":"Minecraft","tunnel_type":"minecraft-java","port_type":"tcp",
                  "origin":{"type":"agent","details":{
                  "agent_id":"server-agent","config_data":{"fields":[]}}}}]
                """);
        assertTrue(PlayitTunnelProvisioner.exists(existing, "server-agent",
                "Java", "minecraft-java", "tcp", 25565));
    }
}
