# Leaf — Performance Optimization Inventory

This document tracks identified performance optimization opportunities in the Leaf
server fork and the implementation status of each. It is maintained alongside the
patch tree under `leaf-server/minecraft-patches/features/` and the configuration
modules under `leaf-server/src/main/java/org/dreeam/leaf/config/modules/`.

The inventory records only opportunities that have been actively investigated.
Status labels follow the convention defined in the project's master engineering
directive: `EXISTING`, `IMPROVEMENT_NEEDED`, `NEW`, `DEFERRED`, `REJECTED`,
`IMPLEMENTED_UNVERIFIED`, `IMPLEMENTED_VERIFIED`.

An item is `IMPLEMENTED_VERIFIED` only when the owner has successfully compiled the
patched tree and run the relevant regression and benchmark workloads. Items written
into the patch tree by an external contributor but not yet compiled by the owner
remain `IMPLEMENTED_UNVERIFIED`.

---

## Environment notes (audited at the time of writing)

| Field | Value |
|---|---|
| Repository URL | https://github.com/seyedaliahmadirouein/Snowey |
| Active branch | `ver/26.3` |
| Head commit | `b1e9a23c` — "Fix workflow format (#960)" |
| Leaf revision | 26.3 (mcVersion 26.3) |
| Paper upstream commit | `f68a55a8964e02b11a0951b971951963da5f0276` |
| Build toolchain (declared) | JDK 25 (Zulu), Gradle wrapper, `paperweight 2.0.0-beta.24` |
| Patch architecture | Paperweight patcher; `minecraft-patches/features/`, `paper-patches/features/` |
| Highest existing feature patch | `0314-GUI-theme.patch` |
| CI workflow | `.github/workflows/build-263.yml` |
| Build commands | `./gradlew applyAllPatches && ./gradlew createPaperclipJar` |

The contributor's local environment for this audit had only a JRE (OpenJDK 21) and
no `javac`. The owner must perform `applyAllPatches`, compilation, tests, and
benchmarks; the contributor cannot run them in this environment.

---

## Subsystem coverage matrix

| Subsystem | Existing coverage | Notes |
|---|---|---|
| Main tick loop | Partial — patch `0015` removes ticking-guard lambda | No further change proposed in this audit |
| Entity ticking & activation | Heavy coverage — `0078`, `0170`, `0223`, `0299` etc. | See item #1 below for a remaining gap |
| Entity tracking & packets | `0101`, `0148`, `0257`, `0272`, `0312` | Item #1 below closes a remaining gap |
| Chunk scheduling & loading | `0239`, `0240`, `0242`, `0302` | No new opportunity confirmed in this audit |
| Chunk packet construction | `0172`, `0313` | Encode-side allocations remain — deferred |
| World generation & noise | `0097`, `0218` | No new opportunity confirmed |
| Block entities & scheduled ticks | `0198`, `0286` | No new opportunity confirmed |
| Memory & allocation | `0040`, `0156`, `0264`, `0269`, `0270` | Item #1 contributes here |
| Region storage & serialization | `0098`, `0150`, `0191` (Linear + MCA) | Candidate 5 below — deferred |
| Networking & compression | `0187`, `0196`, `0197`, `0272` | Items #2 and #3 below |
| Background task execution | `0024`–`0027`, `0048`, `0108` | No new opportunity confirmed |
| JVM & runtime | Documented only | Out of source-tree scope |
| Admin observability | `0017`–`0021`, `0066`–`0068`, `0177` | No new opportunity confirmed |
| Build & CI | `0058`, `0066` | No new opportunity confirmed |

---

## Inventory items

### Item 1 — Cache `ClientboundSetEntityMotionPacket` in `ServerEntity`

| Field | Value |
|---|---|
| Subsystem | Entity tracking — motion packet broadcast |
| Source locations | `net/minecraft/server/level/ServerEntity.java` — `sendChanges` legacy path and `MultithreadedTracker` async path |
| Existing behavior | Each motion broadcast constructs `new ClientboundSetEntityMotionPacket(this.entity.getId(), this.lastSentMovement)`. The constructor packs the `Vec3` into three primitive `int` fields and stores the entity id, producing an immutable packet object. SparklyPaper patch `0101` already guarantees `lastSentMovement` is reassigned to a fresh `Vec3` reference whenever the entity's delta movement actually changes — never mutated in place. |
| Evidence | Source analysis of patches `0101`, `0148`, `0257`, `0272`. The packet payload is fully derived from `(entity.getId(), lastSentMovement)`, both of which are stable between actual motion changes. |
| Bottleneck | Per-broadcast allocation of a small but high-throughput packet object on the tracker hot path. At ~100 players × ~100 tracked entities × 4 packets/tick this is tens of thousands of allocations per tick, contributing to young-gen GC pressure and visible P99 MSPT variance on entity-dense workloads (mob farms, projectile arrays, item drop explosions). |
| Proposed change | Add two private fields to `ServerEntity`: `leaf$cachedMotionPacketVec` (the `Vec3` reference used to build the cached packet) and `leaf$cachedMotionPacket` (the cached packet itself). Add a helper `leaf$getOrBuildMotionPacket()` that rebuilds the cached packet iff `this.lastSentMovement != this.leaf$cachedMotionPacketVec` (identity check), otherwise returns the cached instance. Wire the helper into both broadcast call sites (legacy `synchronizer.sendToTrackingPlayers` and multithreaded `ctx.sendToTrackingPlayers`) behind a new config flag `cacheMotionPacket`, defaulting to `false`. |
| Expected benefit | Per-call allocation on the tracker motion path drops to zero once the cache is warm; rebuild happens only when the entity's delta movement actually changes. Most visible on P99 MSPT under entity-dense workloads and on young-gen GC frequency. |
| Risk | Low–medium. **Threading:** `ServerEntity` is touched by one tracker thread at a time per call (legacy path: main tick thread; multithreaded path: a single async-tracker worker per entity per call, per the `0257` design). The cached fields are not read by any other thread. **Vanilla behavior:** identical payload — the cached packet is byte-for-byte identical to a fresh construction. **Plugin compatibility:** ProtocolLib intercepts `Connection#send` and may mutate the packet object on interception. Sharing a single instance across players could leak such mutations. Mitigation: the new flag defaults to `false`; servers without such interceptors can opt in. **Lifecycle:** the cached packet holds no reference to the live `Entity`; it stores only the int id and three packed ints, so it cannot leak entity state after removal. |
| Tests required | (1) Single-player movement reproduces vanilla motion-sync behavior (no visible desync). (2) Multi-player tracking: all players see identical motion packets. (3) Mob farm workload: no allocation regression after warmup. (4) With `cacheMotionPacket=true` and ProtocolLib installed: verify ProtocolLib's interception still works on at least one packet (or that the flag is documented as incompatible). |
| Benchmark | Reproducible before/after workload: 1 world, 200 item entities dropped in a 32×32 block area, 1 dummy player observing from center. Measure young-gen GC frequency and P99 MSPT over 1200 ticks after a 200-tick warm-up. |
| Status | `IMPLEMENTED_UNVERIFIED` — patch `0315-Cache-ClientboundSetEntityMotionPacket.patch` and config flag `cacheMotionPacket` added; awaiting owner compilation and validation. |

---

### Item 2 — `Connection#sendBatch` API to eliminate per-packet `Runnable` lambda

| Field | Value |
|---|---|
| Subsystem | Networking — off-event-loop packet dispatch |
| Source locations | `net/minecraft/network/Connection#send(Packet, PacketListener, boolean)` — the `else` branch modified by patch `0187` |
| Existing behavior | Patch `0187` swapped `channel.eventLoop().execute(...)` → `eventLoop.lazyExecute(...)` to skip a wakeup syscall, but the `Runnable` lambda capturing `(this, packet, listener, flush)` is still allocated per packet. |
| Bottleneck | At 100 players × 100 tracked entities × 4 packets/tick, ~40k lambda allocations per tick on the tracker path. |
| Proposed change | Add `Connection#sendBatch(List<Packet<?>>, boolean)` that creates one `Runnable` for the whole list and calls `doSendPacket` in a loop inside it. Update `ChunkMap.TrackedEntity#sendToTrackingPlayers` to defer per-player sends into a `List` and call `sendBatch` once per `ServerEntity#sendChanges` invocation. |
| Risk | Medium. **Threading:** the list must be safely published to the event-loop thread (volatile or single-writer-per-thread). **Plugin compatibility:** ProtocolLib wraps `Connection#send` via reflection; a new `sendBatch` method would bypass interception. Mitigation: fall back to per-packet `send` when ProtocolLib is detected (mirror `0187`'s config gate). |
| Status | `DEFERRED` — design is sketched and the call site is identified, but the change touches the networking pipeline broadly enough that it warrants a dedicated design pass. Not included in this audit's deliverables. |

---

### Item 3 — Per-section dirty accumulator for `ClientboundSectionBlocksUpdatePacket`

| Field | Value |
|---|---|
| Subsystem | Block update broadcast — multi-block section updates |
| Source locations | `net/minecraft.world.level.chunk.LevelChunk#setBlockState` → broadcast path; `net/minecraft.server.level.ServerLevel#sendBlockUpdated` |
| Existing behavior | Vanilla coalesces per-block updates within a single `setBlock` call. Multiple `setBlock` calls touching the same chunk section in one tick (piston doors, obsidian casts, redstone chains) each broadcast their own `ClientboundSectionBlocksUpdatePacket` for the changed blocks. |
| Bottleneck | Per-broadcast iteration of the player list per section. On piston-heavy servers, 16+ block changes in one section per activation can each trigger a separate broadcast pass. |
| Proposed change | Add a per-section dirty `BitSet` on `LevelChunk`. Mark bits dirty in `setBlockState`. At end-of-tick, if the dirty bit count per section is ≥ 2, emit a single `ClientboundSectionBlocksUpdatePacket` per watching player instead of N individual `ClientboundBlockUpdatePacket`s. |
| Risk | Medium. **Threading:** LevelChunk `setBlock` is main-thread (or tick-thread under PWT). **Vanilla behavior:** vanilla only batches within a single `setBlock` call; extending to per-tick batching introduces 1-tick latency on the wire (visually identical because client renders on its own tick). **Plugin compat:** plugins reading `ClientboundSectionBlocksUpdatePacket` are unaffected (same payload, same wire format). Plugins counting packets (rare) might see different counts. **Ordering:** drain the bitset before redstone phase to preserve ordering for gameplay-critical updates. |
| Status | `DEFERRED` — design is sketched; not implemented in this audit because it requires touching both `LevelChunk` (a heavily-patched file) and the broadcast plumbing. Better suited to a focused follow-up task. |

---

### Item 4 — `RegionFile` (MCA) write coalescing for auto-save batches

| Field | Value |
|---|---|
| Subsystem | Region file storage — auto-save I/O |
| Source locations | `net.minecraft.world.level.chunk.storage.RegionFile#write(ChunkPos, ByteBuffer)`; `RegionFileStorage` |
| Existing behavior | Default `region-format: MCA`. Each chunk save does its own `FileChannel.write(ByteBuffer)` plus a header sector update plus an optional flush. Auto-save of N chunks in the same region file in one tick → N separate syscalls + N header updates. |
| Bottleneck | Auto-save spikes (every 6000 ticks by default) take 100–300 ms for large worlds on spinning disks or network-attached storage. |
| Proposed change | Add a `pendingWrites: Long2ObjectMap<ByteBuffer>` on `RegionFile`. Stash writes from the auto-save thread into `pendingWrites` instead of writing immediately. Add `flushPendingWrites()` called at end of auto-save batch (or on a 50 ms timer). Sort positions by sector offset, do one `FileChannel.write` per contiguous run, update the header once, then a single `force(false)`. |
| Risk | Medium–high. **Threading:** RegionFile is currently synchronized on itself; pendingWrites must be guarded by the existing sync block. **Vanilla behavior:** on crash mid-flush, vanilla MCA guarantees that fully-written chunks survive. With batching, a crash mid-flush could lose multiple chunks. Mitigation: always flush on shutdown (Moonrise already calls `MoonriseRegionFileIO#flush` at halt). **Plugin compat:** the public `RegionFile#write(ChunkPos, ByteBuffer)` semantics change. Mitigation: gate behind a `coalesceWrites` config flag, default `false`, only available in MCA mode (B_LINEAR users already get coalescing via the swap-file mechanism). |
| Status | `DEFERRED` — high-value but high-risk; warrants a dedicated task with extensive save/load stress testing. Not included in this audit's deliverables. |

---

### Item 5 — `SerializableChunkData#write` encode-path allocations

| Field | Value |
|---|---|
| Subsystem | Chunk packet & chunk save encode |
| Source locations | `net.minecraft.world.level.chunk.storage.SerializableChunkData#write(RegistryAccess, FriendlyByteBuf)` |
| Existing behavior | Patch `0191` pre-sizes lists and removes streams in the construct path. The encode path (called when the chunk packet is serialized on the main thread, and on every chunk save) re-iterates collections on every call. |
| Bottleneck | Repeated iteration and small allocations per encode call. |
| Proposed change | Pre-cache `heightmapsBitSet` and `sectionCountInt` on the immutable record so the encode path reads them as field accesses. |
| Risk | Low — the record is immutable so the cached values are stable. |
| Status | `DEFERRED` — small win, but a clean follow-up patch. |

---

## Summary table

| # | Item | Subsystem | Status | Risk |
|---|---|---|---|---|
| 1 | Cache `ClientboundSetEntityMotionPacket` in `ServerEntity` | Entity tracking | `IMPLEMENTED_UNVERIFIED` | Low–Med |
| 2 | `Connection#sendBatch` API | Networking | `DEFERRED` | Medium |
| 3 | Per-section dirty accumulator for section-block-update packets | Block update broadcast | `DEFERRED` | Medium |
| 4 | `RegionFile` (MCA) write coalescing | Region storage | `DEFERRED` | Medium–High |
| 5 | `SerializableChunkData#write` encode-path caching | Chunk encode | `DEFERRED` | Low |

---

## Acceptance gates (per the project's master engineering directive)

For item 1 the following gates apply:

| Gate | Status |
|---|---|
| A — Integration | Patch file `0315-Cache-ClientboundSetEntityMotionPacket.patch` placed under `leaf-server/minecraft-patches/features/`. Config flag `cacheMotionPacket` added to `ReduceUselessPackets.java`. Owner must run `./gradlew applyAllPatches` to verify clean application against the current `paperCommit`. |
| B — Correctness | Required regression tests: single-player motion sync (no visible desync), multi-player tracking (identical packets to all viewers), entity removal lifecycle (no stale cached packet leaking across entity respawn). |
| C — Operational safety | Config flag defaults to `false`. Documented ProtocolLib incompatibility. Cached packet holds no live `Entity` reference. |
| D — Performance | Reproducible workload defined under item 1's Benchmark row. Owner must measure young-gen GC frequency and P99 MSPT before/after with the flag on and off. |
| E — Maintainability | Diff is focused: one new patch file, one config flag addition. No new dependencies. No public API change. Upstream rebase risk is low — the change is contained to `ServerEntity` motion broadcast call sites that are already Leaf-modified by `0257` and `0272`. |

Overall item-1 status: `IMPLEMENTED_UNVERIFIED`. Owner action required to move it to `IMPLEMENTED_VERIFIED`.
