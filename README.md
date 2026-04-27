# Voxy World Gen V2

![Logo](src/main/resources/logo.png)

This is a rewrite of my old Voxy World Gen mod, this mod is NOT a fork of the passive chunk generator mod and instead is a entirely different mod.

## FORK NOTES
This fork adds in several changes to the way generated chunks are synced to clients.

Currently, chunks are only synced to clients if that chunk is loaded in the server's chunk cache in memory. This is a problem because distant chunks are ditched from memory right after being generated. If a client joins the server after voxy world gen is finished generating chunks, those chunks will never be in memory until the player goes to those chunks, which defeats the purpose of the mod. There are two solutions to this:
- Keep distant chunks in memory
- Load distant chunks from disk as needed

Obviously the first option would use way too much memory, so in this fork, chunks are loaded from disk when a client needs to sync them.

Another issue is that currently, the player tracker (the thing that tracks which players have which chunks) is not persistent between server restarts. If a client syncs all the chunks they need, then the server restarts, the server will re-send all those chunks that the client already has. This is an unnecessary burden on both client and server resources. This will be solved by adding in a client to server handshake in addition to the current server to client handshake. When the client joins, it will send the server a list of every chunk that it has, and the server will add this to the player tracker. The reason for not just saving the player tracker to disk between server starts and stops is because of another issue outlined below:

The third issue is that if chunks are modified while a client is offline, they will not recieve those modified chunks when they re-join later and try to sync again. To solve this, a timestamp will be attached to each chunk in the client to server handshake. The server will also store a timestamp of when each chunk has been generated/modified. If the timestamp does not match between client and server, the server will send the updated chunk.

## Features

- Generates chunks very fast in the background and auto-ingest them with voxy.
- Configurable generation speed and queue size.
- Tellus integration. https://github.com/Yucareux/Tellus
- Server-side support

## Dependencies

- **Minecraft**: 1.21.6 - 1.21.11 (Tested on 1.21.11, anything less is considered unstable and may not work)
- **Fabric Loader**: >= 0.16.0
- **Java**: 21 (Required)
- **Fabric API**
- **Cloth Config**: >= 15.0.127

## Building

This project requires Java 21.

```bash
# Clone the repo
git clone https://github.com/iSeeEthan/voxy_worldgen_v2.git

# Build
./gradlew build
```

Artifacts are output to `build/libs/`.

## Configuration

Config files are located in `config/voxyworldgenv2.json`.

## License

CUSTOM, refer to LICENSE file for more information.
