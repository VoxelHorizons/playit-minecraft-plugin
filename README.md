# VoxelHorizons Playit Minecraft Plugin

A fork of the Playit Minecraft Bukkit/Paper plugin with **optional native-agent
support for Minecraft Java (TCP), Geyser Bedrock (UDP), and Simple Voice Chat (UDP)**.

The original Minecraft plugin has a **TCP-only embedded agent**. Its older Bedrock
fork only creates a UDP tunnel record; it does not carry UDP packets. To make
Bedrock and voice actually work, this fork can start and supervise the official
`playitd` agent in `native` mode. In that mode, ONE agent runs all three tunnels.

## Requirements

- A Paper/Spigot server running the plugin and a reachable local game listener.
- For Bedrock: Geyser (and optionally Floodgate), configured for an ordinary
  UDP/RakNet connection. Geyser's Bedrock port is usually `19132`.
- For proximity voice: Simple Voice Chat on the server and **on Java clients**.
  Its UDP port defaults to `24454`. This does not automatically add Bedrock
  voice-chat support.
- To forward UDP: a separately installed, official `playitd` executable from
  https://playit.gg/download (version 1.0+ recommended), runnable by your
  Minecraft server user, with outbound Playit access.

**Do not download an untrusted Playit binary or run both the embedded plugin
agent and playitd using the same key.** Native mode starts only the latter.

## Setting up three tunnels

1. If you have never claimed the Playit account/agent, initially leave
   `agent-backend: plugin`. Start the server, follow the Playit claim URL from
   the console, and allow the resulting `agent-secret` to be saved.
2. Stop the Minecraft server. Install `playitd` for your host/container and
   ensure it runs with `playitd --help`. In `plugins/playit-gg/config.yml`
   set:

   ```yaml
   agent-backend: native
   native-binary: playitd
   auto_create_java_tunnel: true
   auto_create_bedrock_tunnel: true
   auto_create_voice_tunnel: true
   java_local_port: 0  # auto-detect the Minecraft server port
   bedrock_local_port: 19132
   voice_local_port: 24454
   ```

   For Pterodactyl, the playitd binary **must be executable inside the server
   container**, not merely installed on the host. Absolute paths are supported
   for `native-binary`. No port forwarding is required.
3. Start the server. The native agent process starts automatically and the
   plugin asks Playit's v1 API to create missing tunnels for services it detects.
   If the account plan, region, or API rejects a request, review server logs.
4. Run `/playit agent status` and `/playit tunnels` as a server operator.
   `/playit createtunnels` retries **Java, Geyser and voice** tunnel requests
   without intentionally duplicating existing ones. If the API cannot create
   a tunnel, use https://playit.gg/account/tunnels to create it on the
   **same agent**. Prefer Playit's **MC: Simple Voice Chat** tunnel preset
   (or single-port UDP) for the voice tunnel.
5. In `plugins/Geyser-Spigot/config.yml`, confirm the Bedrock UDP listener
   matches `bedrock_local_port` and that `clone-remote-port` does not override
   your configured port. Playit's *local* address should point at that Geyser
   listener, typically `127.0.0.1:19132`. When enabling Playit's UDP PROXY
   protocol v2 in the dashboard, also enable its corresponding Geyser setting;
   without the matching server-side setting, leave PROXY protocol disabled.
6. In `plugins/voicechat/voicechat-server.properties`, configure:

   ```properties
   port=24454
   bind_address=
   voice_host=PUBLIC_VOICE_ADDRESS:PUBLIC_VOICE_PORT
   ```

   Replace `PUBLIC_VOICE_ADDRESS:PUBLIC_VOICE_PORT` with the **public UDP voice
   address reported by Playit**, not the Minecraft Java address or the local
   port. Restart Simple Voice Chat/the Minecraft server after changing it.
   The voice mod must be installed on each participating Java client.

## Admin commands

| Command | Purpose |
|---|---|
| `/playit agent status` | Shows the selected agent's process/connection status |
| `/playit agent restart` | Restarts the selected agent |
| `/playit agent shutdown` | Stops the agent until restarted (native mode) |
| `/playit createtunnels` | Requests tunnel creation; all three in native mode |
| `/playit tunnels` | Lists assigned tunnels and public addresses using the current v1 API |
| `/playit agent set-secret <key>` | Replaces the private Playit key; use console for privacy |

**Safety:** Tunnel creation success, a running playitd process, and an assigned
address are not proof of reachability. Validate a real external Java connection,
a real Bedrock connection from a separate network, and voice chat between
clients. If possible, verify `/voicechat test` in-game for voice.

## Legacy Java-only backend

`agent-backend: plugin` preserves the existing Java tunnel implementation.
It **cannot** forward Bedrock or voice UDP traffic. The old fork's
`auto_create_bedrock_tunnel` code was misleading, so in this mode no unusable
UDP tunnel is automatically created by this plugin. Switch to native mode or
manage a standalone external Playit agent for UDP.

## Current Bedrock networking caveat (2026)

Minecraft Bedrock's newer NetherNet transport is not identical to the older
RakNet UDP tunnel. The `minecraft-bedrock` preset used here targets the
usual Geyser/RakNet UDP listener. If running a NetherNet-only service, use the
matching Playit NetherNet product/agent version and its required TCP+UDP
endpoints, which may need a premium plan. Do not treat a classic Bedrock UDP
tunnel as confirmation of NetherNet support.

## Development

`mvn --batch-mode verify` builds the shaded Bukkit plugin. For PRs, GitHub
Actions also runs the Maven build and uploads the test jar when successful.

- Official agent: https://github.com/playit-cloud/playit-agent
- Playit voice instructions: https://playit.gg/support/svc-minecraft/
- Geyser Playit guide: https://geysermc.org/wiki/geyser/playit-gg/
