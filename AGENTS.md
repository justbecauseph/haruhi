# AGENTS.md

Haruhi is a Fabric 26.2 mod for scriptable NPCs:
- data-driven dialogue graphs, edited as one JSON content bundle;
- a visual-novel dialogue screen, with vanilla dialogs as the fallback for a client without Haruhi's dialogue channel;
- NPCs rendered with Blockbench models through GeckoLib;
- conversations bound to placed blocks and to existing entities that are not Haruhi NPCs;
- per-character dialogue flags and visit counts.

In practice every client needs Haruhi: the `haruhi:npc` entity type is a synced registry entry, so Fabric API's registry
sync disconnects a client that lacks it before it joins, and the vanilla dialog fallback is not reached by a normal
player. Human-facing documentation is in `README.md` and `docs/`; keep it in step when behaviour changes.

It is the framework half of the Lampas
[Guild commissions and NPC framework plan](https://github.com/justbecauseph/lampas-two-kb/blob/main/docs/plan/GUILD-PLAN.md).
The Guild itself is a later consumer and is not in this repository.

## Build and verify

```
./gradlew build          # compile, unit tests (JUnit 5, Testcontainers), and runGameTest (server GameTests)
./gradlew test           # unit tests only
./gradlew runGameTest    # server GameTests only
./gradlew runClient      # dev client, for checking the screen and renderer by hand
```

- Testcontainers tests need Docker. Annotate them `@Testcontainers(disabledWithoutDocker = true)` (from
  `testcontainers-junit-jupiter`, a test dependency) so `build` still passes on a machine without Docker. Say in your
  report when they were skipped.
- GameTests that activate content wait until `HaruhiServices.content().isStartupComplete()` first, so the startup
  activation cannot replace their fixture.
- Java 25. Minecraft 26.2 names: `Identifier` (not `ResourceLocation`) and `EntityTypes` holders. Use permission checks
  such as `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`, or `Permissions.COMMANDS_ADMIN` from
  `net.minecraft.server.permissions`.
- When unsure of a 26.2 or Fabric API, check the decompiled jar with `javap -cp
  ~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar <class>`, not memory. For GeckoLib 5.5.5, the jar is in the
  Gradle cache under `maven.modrinth/geckolib`.
- `../lampas-addons` is a working 26.2 Fabric mod with payloads, commands, GameTests and entity code. Read it for
  idioms; do not change it.
- **Publishing:** `.github/workflows/release.yml` publishes `town.lampas:haruhi` to the JustBecause Maven repository
  (`justbecauseph/maven`) when a GitHub release `v<mod_version>` is published, after `./gradlew clean build` passes. A
  version already in that repository is never replaced, so bump `mod_version` for every release. Locally,
  `./gradlew publishMavenJavaPublicationToPublicMavenRepository` writes to `build/public-maven`. Dev-only runtime
  dependencies go in `localRuntime`, never `runtimeOnly`, which would publish them to consumers; `check` fails if
  GeckoLib reaches the POM or the Gradle module metadata.

## Architecture

| Component | Package and resources | Delivers | Depends on |
|---|---|---|---|
| storage | `town.lampas.haruhi.storage` (except the contract files below), `src/main/resources/haruhi/migrations/` | `StorageModule`, config `config/haruhi.json`, in-memory and PostgreSQL (`HikariCP`, versioned SQL migrations) implementations of `HaruhiStorage` | nothing |
| content | `town.lampas.haruhi.content` (except the contract files), `src/main/resources/haruhi/seed_bundle.json` | `ContentModule`: JSON codecs for the bundle format below, full validation, SHA-256 digest, revisions through `HaruhiStorage`, activation on startup, `/haruhi content` commands | storage (interface), `DialogueRegistries` for parsing specs |
| dialogue | `town.lampas.haruhi.dialogue` (except `dialogue/api`), `town.lampas.haruhi.mixin` | `DialogueModule`: sessions (tied to an NPC or, through `openAt`, to a place, with an optional `DialogueSpeaker` for nodes that name no speaker), graph runtime, node text resolved for the player when sent, every built-in below, flag and visit caching and persistence, server payload receivers, the vanilla dialog presenter, `/haruhi dialogue` commands | content (`ContentService`), storage (interface), npc (`NpcControl`) |
| binding | `town.lampas.haruhi.binding` | `BindingModule`: block bindings in per-dimension saved data (`BlockBindings`, `<dimension>/data/haruhi/block_bindings.dat`), entity bindings as the persistent Fabric attachment `haruhi:binding` (`EntityBindings`), the server-side `UseBlockCallback` and `UseEntityCallback` that open a bound graph in place of the vanilla interaction, the `PlayerBlockBreakEvents` listeners that drop a broken block's binding (noted before the break, removed once the block is gone, at the latest at the end of the tick), `/haruhi bind`, `/haruhi unbind` and `/haruhi bindings` | content (`ContentService`), dialogue (`DialogueService`), npc (`NpcControl`) |
| npc and client | `town.lampas.haruhi.npc` (except `NpcControl`), `town.lampas.haruhi.client` (except `HaruhiClient`), `assets/haruhi/**` | `NpcModule`: entity type, profile and look syncing, interaction, `/haruhi npc` commands, `NpcControl`. `NpcClient`: GeckoLib replaced-entity renderer, vanilla player-model fallback, default humanoid model and animations, animation payload receiver. `DialogueClient`: the visual-novel screen; while it is open, `DialogueHud` hides the vanilla HUD elements under its box (hotbar, status bars, experience bar and level, held-item name, crosshair) and moves the chat above the box | content (`ContentService`), dialogue (`DialogueService`), the payloads |

Initialisation order, in `Haruhi.onInitialize`: payload types, storage, content, dialogue, npc, binding. A module may
read the services of the modules before it from `HaruhiServices`. A module that needs a later one, such as dialogue
needing `NpcControl`, reads it lazily at use time, never during `init()`.

### Contracts

Only the orchestrator changes these. If your component needs a change, do not edit them. Report the change you need,
with the reason, in your final message.

- `Haruhi.java`, `api/HaruhiServices.java`, `client/HaruhiClient.java`
- `storage/HaruhiStorage.java`, `FlagKey`, `FlagChange`, `VisitKey`, `VisitRecord`, `StoredRevision`, `RevisionSummary`
- `content/ContentService.java`, `content/model/*`, `content/api/*`
- `dialogue/api/*`
- `npc/NpcControl.java`
- `network/*` (payload records and `HaruhiNetworking`)
- `build.gradle`, `gradle.properties`, `settings.gradle`, `.github/workflows/release.yml`, `src/main/resources/fabric.mod.json`, both mixin configs, `src/gametest/resources/fabric.mod.json`, `src/test/java/town/lampas/haruhi/ArchitectureTest.java`

Each component writes its GameTests in its own class, already listed in the GameTest entrypoints:
`town.lampas.haruhi.gametest.{Storage,Content,Npc,Dialogue,Binding}GameTests`. Each also adds unit tests under
`src/test/java/town/lampas/haruhi/<component>/`. GameTest fixtures go in
`src/gametest/resources/haruhi/fixtures/<component>/`.

### Rules every component follows

- **Client code stays in the client package.** Nothing outside `town.lampas.haruhi.client` references
  `net.minecraft.client` or `com.geckolib`. The Lampas server does not have GeckoLib, and `ArchitectureTest` fails the
  build if this is broken. The NPC entity class is a plain Minecraft entity.
- **No blocking I/O on the server thread.** Storage futures complete off-thread. Hop back with `server.execute(...)`
  before touching game state.
- **The server checks again whatever the client sends.** The client sends only session id, node id and choice index.
- **Lang file:** only the npc and client component edits `assets/haruhi/lang/en_us.json`. Other components use
  `Component.literal` for command feedback.
- **Configuration:** storage owns `config/haruhi.json`. Dialogue owns `config/haruhi-dialogue.json`, which holds the
  command-action allowlist.

## Content bundle format

`docs/bundle.schema.json` is a JSON Schema of this format and the built-ins below, for editors. Change it with any
change to the format or to a built-in's arguments; `BundleSchemaTest` and `BuiltinSchemaTest` fail when it disagrees
with the reader or a built-in's parser.

One JSON document. Ids are `namespace:path` strings, and text fields use Minecraft's text component JSON (a plain
string is allowed). Every key is snake_case. A spec (condition, action or provider) is an object with `type`; its
other keys are the arguments, stored in `ConditionSpec.args` with `type` removed.

```json
{
  "schema": 1,
  "flags": {
    "haruhi:example/greeter": ["met", "times_talked", "helped"]
  },
  "npcs": {
    "haruhi:example/greeter": {
      "name": "Greeter",
      "model": "haruhi:example/greeter",
      "texture": "minecraft:textures/entity/player/wide/steve.png",
      "animations": "haruhi:humanoid",
      "portrait": "haruhi:textures/portrait/greeter.png",
      "dialogue": "haruhi:example/greeter",
      "scale": 1.0
    }
  },
  "dialogues": {
    "haruhi:example/greeter": {
      "start": "hello",
      "nodes": {
        "hello": {
          "random": [ { "node": "hello_first", "weight": 1 }, { "node": "hello_again", "weight": 1 } ]
        },
        "hello_first": {
          "speaker": "haruhi:example/greeter",
          "text": "Welcome to Maraha.",
          "animation": "talk",
          "on_enter": [ { "type": "haruhi:flag/add", "namespace": "haruhi:example/greeter", "flag": "times_talked", "delta": 1 } ],
          "choices": [
            { "text": "Thanks.", "next": "bye", "actions": [ { "type": "haruhi:flag/set", "namespace": "haruhi:example/greeter", "flag": "met", "value": 1 } ] },
            { "text": "Have we met?", "next": "met_before",
              "only_if": [ { "type": "haruhi:flag", "namespace": "haruhi:example/greeter", "flag": "met", "op": "set" } ],
              "when_unavailable": "grayed_out", "unavailable_message": "You haven't met yet." }
          ]
        },
        "hello_again": { "text": "Back again?", "next": "bye" },
        "met_before": { "text": "Of course.", "next": "bye" },
        "bye": { "text": "Safe travels." }
      }
    }
  }
}
```

Field rules (the content component enforces them, and other components rely on them):

- **NPC fields:**
  - `name` and `texture` are required.
  - `model`, `animations`, `portrait` and `dialogue` are optional.
  - `scale` defaults to 1.0 and must be within (0, 4].
  - No `model` means the built-in default humanoid model, with `texture` used as a 64×64 player-layout skin.
- **Node fields:**
  - A node with `random` is a router. It has no `text` and no `choices`, and every weight is > 0.
  - Any other node requires `text`.
  - `choices`, `next` and `random` all name nodes in the same graph.
  - `on_enter` defaults to empty.
- **Choice fields:**
  - A choice needs either `text` or `provider`.
  - `when_unavailable` is `hidden` (the default) or `grayed_out`.
  - `only_if` and `actions` default to empty.
- **Graph and bundle rules:**
  - `start` must exist, and every node must be reachable from it. Edges are `next`, choice `next`, `random` and
    `haruhi:goto` targets.
  - One exception: in a graph where a node reachable from `start` holds a provider choice, nodes that no edge reaches
    are allowed, because a provider names its next node at run time. A provider in an unreachable node does not
    count.
  - Every `speaker`, NPC `dialogue` and `haruhi:open` graph must exist in the bundle.
  - A flag used by any condition or action must be declared under `flags`.
  - Unknown keys are errors at every level (bundle, NPC, graph, node, choice, router branch). A misspelt `only_if`
    must fail validation, never silently drop a gate.
  - Two keys that parse to the same id (`guard` and `minecraft:guard`) are an error.
  - Router weights must sum to at most `Integer.MAX_VALUE`. Flag namespaces and names must be non-empty and contain
    no NUL character.
- **Extensions:** the optional top-level `extensions` object maps an extension id to a section owned by another mod.
  The mod registers a parser with `ContentExtensions.register(id, valueClass, type)` during its init. An id nobody
  registered is an error. Sections are parsed after flags, NPCs and graphs and before any condition, action or
  provider, which read the parsed values through `ParseContext.extension(key)`; the active bundle exposes them through
  `ContentBundle.extension(key)`. The digest covers them. `docs/bundle.schema.json` checks only that `extensions` is
  an object keyed by ids.
- **Portrait:** the visual-novel screen shows a square headshot inside the text box, left of the speaker's name and the
  text: the node's `portrait`, else the speaking profile's `portrait` (resolved on the server and sent in the payload),
  stretched to the square, so portrait textures should be square; else a 3D render of the speaker entity's head and
  shoulders when it is loaded; else no headshot, and the text column starts at the box's left padding.

The model and texture ids are client assets. The server cannot check them, so the client falls back to the default
model and the Steve skin when one is missing.

## Built-in conditions, actions and providers

Registered by the dialogue component under these ids, with these arguments. The content seed bundle and every
fixture use exactly these.

| Kind | Id | Arguments |
|---|---|---|
| condition | `haruhi:all_of`, `haruhi:any_of` | `conditions`: array of condition specs |
| condition | `haruhi:not` | `condition`: one condition spec |
| condition | `haruhi:flag` | `namespace`, `flag`, `op`: `set`, `unset`, `eq`, `ne`, `gt`, `gte`, `lt` or `lte`; `value`: int (not used by `set` and `unset`) |
| condition | `haruhi:visits` | `op` (as above, without `set` and `unset`), `value` |
| condition | `haruhi:predicate` | `predicate`: id of a vanilla predicate (the `predicate` registry), tested on the player at the player's position |
| condition | `haruhi:has_item` | `item`: an item id or `#tag`; `count`: int, default 1 |
| condition | `haruhi:advancement` | `advancement`: id; `criterion`: optional name; true when done (or when that criterion is done) |
| condition | `haruhi:tag` | `tag`: a scoreboard tag on the player |
| condition | `haruhi:permission` | `level`: an op permission level, 0–4 |
| condition | `haruhi:time` | `from`, `to`: day-time ticks 0–23999; the range wraps past midnight when `from` > `to` |
| condition | `haruhi:weather` | `weather`: `clear`, `rain` or `thunder` |
| action | `haruhi:flag/set` | `namespace`, `flag`, `value`: int, default 1 |
| action | `haruhi:flag/add` | `namespace`, `flag`, `delta`: int, default 1 |
| action | `haruhi:flag/clear` | `namespace`, `flag` |
| action | `haruhi:goto` | `node` |
| action | `haruhi:end` | none |
| action | `haruhi:open` | `graph` |
| action | `haruhi:animation` | `animation`: played by the interlocutor NPC |
| action | `haruhi:sound` | `sound`: sound event id; `volume`, `pitch`: default 1 |
| action | `haruhi:message` | `text`: text component, sent to the player's chat |
| action | `haruhi:give` | `loot_table`: id; `once`: `{ "namespace", "flag" }`, required. Refuses if the flag is set. Otherwise it sets the flag write-through, waits for the write, then gives the loot. A crash in between loses the reward and never duplicates it. |
| action | `haruhi:command` | `command`: run as the player at the permission level set in `config/haruhi-dialogue.json`. Only runs when the command's first word is on that file's allowlist, which is empty by default. A command not on the list is a validation error. |

Haruhi registers no providers itself. The API exists for consumers such as the Guild board.

## Bindings

The binding component attaches a graph to a placed block or to an entity that is neither a player nor a `haruhi:npc`.
The server guide's "/haruhi bind, unbind and bindings" and "Binding behaviour" sections are the user-facing reference;
keep them in step.

- Commands, all for gamemasters: `/haruhi bind block <pos> <graph> [<speaker>]`, `/haruhi bind entity <targets> <graph>
  [<speaker>]`, `/haruhi unbind block <pos>`, `/haruhi unbind entity <targets>`, `/haruhi bindings [all]`. `<graph>`
  and `<speaker>` (an NPC profile) are checked against the active content when bound.
- A main-hand use of a bound block or entity opens the graph and never reaches vanilla. The off hand and Fabric fake
  players get nothing; spectators get vanilla. A graph the active revision lacks opens nothing, and the vanilla
  interaction is still skipped. A `UseItemCallback` refuses the held item's use in the tick of a consumed use and the
  next, because a client that predicted a passing block or entity use sends one.
- A block opens with `openAt` on an anchor at the block's centre, 8 blocks (a longer block reach plus 3, up to 64); an
  entity with `open`, the entity as the interlocutor. Both pass `DialogueSpeaker.of(speaker, <block or entity name>)`, so visits count under the speaker
  profile, else `haruhi:none`.
- A block binding stores the block id at bind time and applies only while that block is there. A door, tall plant,
  bed or double chest has one binding for both halves (`BlockParts`). A player breaking that block removes the binding; any other change leaves it inactive until the block is back. `BlockBindings` loads in
  `ServerLevelEvents.LOAD`, so no use reads the file. An entity binding is the persistent
  attachment `haruhi:binding`, not copied on mob conversion and not synced.

## Blockbench model converter

`src/tools` holds a Blockbench `.bbmodel` to GeckoLib converter. It is a separate `tools` source set, not part of the
mod jar; its unit tests are in `src/test/java/town/lampas/haruhi/tools/` and run with `test`.

```
.\gradlew.bat bbmodel --args='<in.bbmodel> <outputRoot> <namespace> <name>'
```

It writes, under `<outputRoot>`:

- `assets/<namespace>/geckolib/models/<name>.geo.json`: Bedrock geometry, format 1.12.0 (1.21.0 when a face has a UV
  rotation);
- `assets/<namespace>/geckolib/animations/<name>.animation.json`: Bedrock animations, format 1.8.0, when the project has
  animations;
- `assets/<namespace>/textures/entity/<name>.png`.

It prints the bones, cubes, skipped elements, textures used and ignored, texture size, animations and warnings, then
the profile fields: `"model": "<namespace>:<name>"`, `"animations": "<namespace>:<name>"`,
`"texture": "<namespace>:textures/entity/<name>.png"`. Relative paths resolve against the project directory.

The rules follow Blockbench's own Bedrock export (its source is in the installed Blockbench's `resources/app.asar`),
cited in the code: X mirroring of origins, pivots and rotations, per-face UV with the up/down flip, box UV with
`uv_offset` and mirror, `export: false`, group pivots and rotations, keyframe sign flips (bbmodel files before format 5.0
store Bedrock signs), loop modes, and catmullrom and step keyframes as Bedrock `pre`/`post`/`lerp_mode`.
`GeckoLibBakeTest` has GeckoLib 5.5.5 bake converted output and checks pivots, rotations and every face's UVs.

- **Textures:** per-texture `uv_width`/`uv_height`, else the project resolution. One used texture is copied and keeps
  its UV space. Several used textures are stitched into one atlas in pixels, with each face's UV remapped and scaled.
  Textures no face uses are left out. In the formats that set `animated_textures` (free, java_block, bedrock,
  bedrock_block, bedrock_old, image), an animated texture keeps its first frame; the other formats keep the whole image.
- **Bones:** outliner groups; elements at the root go in a first bone, `bb_main`. Names are reduced to `[A-Za-z0-9_.-]`
  and made unique with a number suffix (the first keeps its name). Animators follow the renamed bones.
- **Box UV:** kept when GeckoLib can draw it as Blockbench does. A box UV cube with a face that is not drawn, faces on
  different textures, a scaled texture, or a fractional size in modded_entity (`box_uv_float_size`) is written per face
  instead. Elsewhere the size is floored and the cube stays box UV, except a size that float noise leaves within 1e-7
  below a whole number, which is written per face.
- **Skipped, with a warning:** meshes, locators, null objects, texture meshes and other non-cube elements; faces with
  texture `null`; effect (sound, particle, timeline) keyframes; animators of non-bone elements.
- **Changed, with a warning:** bezier keyframes are written linear (Blockbench samples the curve); quaternion
  interpolation is written as Euler angles; an animation set to play once that has no length is written to loop, as
  Blockbench does.
- **Written but ignored by GeckoLib 5.5.5, with a warning:** `override_previous_animation`, `anim_time_update`,
  `blend_weight`, `start_delay`, `loop_delay`, `relative_to` and bone `binding`. Molang strings are kept as strings and
  checked against GeckoLib's parser, functions and queries.
- Compressed `.bbmodel` files are refused; save them uncompressed first.
