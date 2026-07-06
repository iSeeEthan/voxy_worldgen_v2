# Voxy World Gen V2

![Logo](neoforge/src/main/resources/logo.png)

This is a rewrite of my old Voxy World Gen mod, this mod is NOT a fork of the passive chunk generator mod and instead is a entirely different mod.

## Features

- Generates chunks very fast in the background and auto-ingest them with voxy.
- Configurable generation speed and queue size.
- Tellus integration. https://github.com/Yucareux/Tellus
- Server-side support

## Project layout (unified multi-loader)

One source tree builds both loaders, with no Architectury(for now might change in future):

```
common/    shared game logic (loader-agnostic, compiled per-loader against each MC)
neoforge/  NeoForge entrypoint, networking, mixins   (MC 1.21.1, Java 21)
fabric/    Fabric entrypoint, networking, mixins      (MC 26.2, Java 25)
```

`common/src/main/java` is added as a source directory to each loader module, so the shared
sources are compiled once per loader against that loader's Minecraft. The few MC-version method
differences (ChunkPos accessors, `getMinSectionY`, the region-ticket API) are bridged by the
`platform/ChunkPosCompat` service; the config dir and networking transport are bridged by
`platform/IPlatformHelper` and `platform/INetworkBridge`. Each loader supplies its concrete
implementations via `META-INF/services` (plain Java `ServiceLoader`).

## Targets

| Loader   | Minecraft | Java |
|----------|-----------|------|
| NeoForge | 1.21.1    | 21   |
| Fabric   | 26.2   | 25   |

## Building

Uses Gradle 9.3 (wrapper included). Requires a JDK 21 and a JDK 25 available to Gradle's
toolchain resolver (Fabric compiles at 25, NeoForge at 21).

```bash
# Build both jars
./gradlew build

# Or one at a time
./gradlew :neoforge:build
./gradlew :fabric:build
```

Artifacts are output to `neoforge/build/libs/` and `fabric/build/libs/`.

## Configuration

Config files are located in `config/voxyworldgenv2.json`.

## License

CUSTOM, refer to LICENSE file for more information.
