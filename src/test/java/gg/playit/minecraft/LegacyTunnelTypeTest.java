package gg.playit.minecraft;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gg.playit.api.models.TunnelType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyTunnelTypeTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);

    @Test
    void acceptsVoicePresetWithoutFailingJavaTunnelList() throws Exception {
        String json = "{\"tunnel_type\":\"mc-simple-voice-chat\"}";
        assertEquals(TunnelType.McSimpleVoiceChat,
                mapper.readTree(json).path("tunnel_type").isTextual()
                ? mapper.readValue("\"mc-simple-voice-chat\"", TunnelType.class) : null);
    }

    @Test
    void ignoresFutureTunnelPresets() throws Exception {
        assertEquals(TunnelType.Unknown,
                mapper.readValue("\"unknown-upstream-preset\"", TunnelType.class));
    }

    @Test
    void keepsJavaAndBedrockCompatibility() throws Exception {
        assertEquals(TunnelType.MinecraftJava,
                mapper.readValue("\"minecraft-java\"", TunnelType.class));
        assertEquals(TunnelType.MinecraftBedrock,
                mapper.readValue("\"minecraft-bedrock\"", TunnelType.class));
    }
}
