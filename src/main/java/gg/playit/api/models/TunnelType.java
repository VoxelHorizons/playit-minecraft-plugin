package gg.playit.api.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum TunnelType {
    @JsonProperty("minecraft-java")
    MinecraftJava,
    @JsonProperty("minecraft-bedrock")
    MinecraftBedrock,
    @JsonProperty("valheim")
    Valheim,
    @JsonProperty("terraria")
    Terraria,
    @JsonProperty("starbound")
    Starbound,
    @JsonProperty("rust")
    Rust,
    @JsonProperty("7days")
    SevenDays,
    @JsonProperty("unturned")
    Unturned,
    @JsonProperty("mc-simple-voice-chat")
    McSimpleVoiceChat,
    @JsonProperty("custom")
    Custom,
    @JsonEnumDefaultValue
    Unknown
}
