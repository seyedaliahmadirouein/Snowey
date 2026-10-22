# Leaf — Engineering Changelog

All notable source-level engineering changes to this Leaf fork are documented here.
This changelog records **what changed in the source tree**, not what has been
compiled or shipped. Compilation, testing, and benchmarking are performed by the
repository owner per the project's `AGENTS.md` validation policy.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/).
Each entry links the patch file, the affected subsystem, and the configuration
flag (if any) that gates the change.

---

## [Unreleased]

### Added — Source-level optimizations pending owner validation

#### Cache `ClientboundSetEntityMotionPacket` in `ServerEntity`

- **Patch:** `leaf-server/minecraft-patches/features/0315-Cache-ClientboundSetEntityMotionPacket.patch`
- **Config:** `optimization.reduce-packets.cache-entity-motion-packet` (default `false`)
- **Subsystem:** Entity tracking — motion packet broadcast
- **Files changed:**
  - `leaf-server/minecraft-patches/features/0315-Cache-ClientboundSetEntityMotionPacket.patch` (new)
  - `leaf-server/src/main/java/org/dreeam/leaf/config/modules/opt/ReduceUselessPackets.java` (added `cacheMotionPacket` flag + loader line)
- **Summary:** Adds two private fields (`leaf$cachedMotionPacketVec`, `leaf$cachedMotionPacket`) and a helper `leaf$getOrBuildMotionPacket()` to `ServerEntity`. The helper rebuilds the cached packet only when `this.lastSentMovement` is reassigned to a fresh `Vec3` reference (identity check); otherwise it returns the cached instance. The helper is wired into both motion-broadcast call sites — the legacy `synchronizer.sendToTrackingPlayers(...)` path and the multithreaded `ctx.sendToTrackingPlayers(tracker, ...)` path introduced by patch `0257` — and is gated by the new `cacheMotionPacket` config flag.
- **Rationale:** `ClientboundSetEntityMotionPacket`'s payload is fully derived from `(entity.getId(), lastSentMovement)`, both stable between actual motion changes (SparklyPaper patch `0101` guarantees `lastSentMovement` is reassigned, not mutated). Constructing a fresh packet on every broadcast is unnecessary allocation on a high-throughput tracker hot path.
- **Defaults & compatibility:** Flag defaults to `false` because ProtocolLib intercepts `Connection#send` and may mutate the packet object on interception. Sharing a single instance across players could leak such mutations across sends. Servers without such interceptors can opt in.
- **Threading:** `ServerEntity` is touched by one tracker thread at a time per call. The cached fields are not read by any other thread. No synchronization required.
- **Lifecycle:** The cached packet stores only an int entity id and three packed int motion components — no reference to the live `Entity`. It cannot leak entity state across entity removal or respawn.
- **Acceptance status:** `IMPLEMENTED_UNVERIFIED` — owner must run `./gradlew applyAllPatches` to confirm clean patch application, then run the regression and benchmark workloads defined in `docs/engineering/OPTIMIZATION_INVENTORY.md` (item 1) to move it to `IMPLEMENTED_VERIFIED`.

### Identified but deferred

The following opportunities were identified during this audit but are not included
in this changeset. They are documented in
`docs/engineering/OPTIMIZATION_INVENTORY.md` (items 2–5):

- `Connection#sendBatch` API to eliminate per-packet `Runnable` lambda allocations
  on the off-event-loop send path (item 2).
- Per-section dirty accumulator for `ClientboundSectionBlocksUpdatePacket` to
  coalesce multi-block updates within a single chunk section per tick (item 3).
- `RegionFile` (MCA) write coalescing for auto-save batches (item 4).
- `SerializableChunkData#write` encode-path caching of `heightmapsBitSet` and
  `sectionCountInt` (item 5).

Each deferred item is a candidate for a focused follow-up task with its own design,
risk assessment, and validation plan.

---

## Conventions for future entries

- Each entry must reference the patch file path under
  `leaf-server/minecraft-patches/features/` (or the equivalent `paper-patches/` /
  `leaf-api/paper-patches/` directory).
- Each entry must name the configuration flag (if any) that gates the change, and
  state its default value and the reason for that default.
- Each entry must state the acceptance status using the project's status labels:
  `PLANNED`, `IMPLEMENTED`, `TESTED`, `BENCHMARKED`, `VERIFIED`, `DEFERRED`,
  `REJECTED`.
- An entry may not be marked `VERIFIED` unless the owner has confirmed successful
  compilation and the relevant regression/benchmark workloads have passed.
- Deferred entries must reference the corresponding item in
  `docs/engineering/OPTIMIZATION_INVENTORY.md`.
