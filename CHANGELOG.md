# Changelog

## 2.4.3

fixed players without the mod getting kicked with invalid player data when joining a neoforge server, the mod is optional now and vanilla clients just don't get lod data
stopped generating chunks around players who don't have the client mod, no point since they can't render it anyway
active task count in f3 holds steady near your configured max instead of bouncing between the cap and a low number, load easing is smoothed now

## 2.4.2

fixed chunks generating unevenly when lots of players are on (one player used to hog all the work, now it's split fairly)
fixed catch-up leaving permanent white holes (chunk got marked synced even when it never sent)
chunk loads no longer spam every player every time, skips people who lack the mod or already have the chunk
block edits no longer tax every single block change on the server, bails early when no one has the mod
dirty section backlog is capped now so a redstone/fluid storm can't blow up memory
block edit resend timing actually uses the update_interval config instead of a hardcoded value
send queue is capped so a broadcast storm can't grow memory forever
stopped sending chunks to players who already left or walked out of range
synced chunks get cleared on dimension change so old stuff doesn't block syncing in the new world
client ingest sorts once per tick instead of scanning the whole queue every time, way less lag
when the queue overflows it drops the farthest chunk instead of the oldest
empty block light is sent as null now instead of a dead 2048 byte array, saves bandwidth
added a protocol version to the handshake so mismatched versions just don't sync instead of breaking
heads up: handshake changed, 2.4.2 won't sync with older versions, update both sides
fabric 26.2 support yayyy (fabric-api 0.153.0+26.2, cloth 26.2.155, mod menu 20.0.0-beta.4)
fabric client ingest now matches the neoforge design (queued + drained per tick nearest-first)
op check is the same on both loaders now (gamemaster / level 2)
