# Models and animations

For model makers who give an NPC its own Blockbench model, texture, animations and dialogue portrait.

Related pages: [README](../README.md), [content guide](content-guide.md) (NPC profiles and dialogue),
[server guide](server-guide.md), [developer guide](developer-guide.md).

- [How an NPC is drawn](#how-an-npc-is-drawn)
- [The default humanoid](#the-default-humanoid)
- [Profile fields and asset files](#profile-fields-and-asset-files)
- [Animations](#animations)
- [The dialogue headshot](#the-dialogue-headshot)
- [Name tag placement](#name-tag-placement)
- [The Blockbench converter](#the-blockbench-converter)
- [From .bbmodel to a spawned NPC](#from-bbmodel-to-a-spawned-npc)
- [Tips](#tips)
- [Limitations](#limitations)

## How an NPC is drawn

Models, textures and animations are client assets. The server only stores the ids in the NPC's profile and sends them
to each client, so the server never needs GeckoLib or the asset files. What a player sees depends on their client:

| Player's client | How every NPC is drawn |
|---|---|
| Haruhi and GeckoLib | Through GeckoLib's replaced-entity renderer: the profile's `model`, `texture` and `animations`, scaled by `scale`. |
| Haruhi without GeckoLib | As the vanilla player model with wide arms, using the profile's `texture` as its skin and `scale` as its size. `model` and `animations` are ignored, and none of Haruhi's animations play. |

`fabric.mod.json` lists GeckoLib 5.5.5 or later under `recommends`, so Haruhi runs without it.

The server sends each NPC's look (model, texture and animations ids) with the entity, and its name and scale through
the vanilla custom name and scale attribute. Activating a new content revision updates every loaded NPC at once.

## The default humanoid

A profile without `model` uses the model built into Haruhi, `haruhi:humanoid`
(`assets/haruhi/geckolib/models/humanoid.geo.json`), with the profile's `texture` as a skin. It is shaped and
textured like the vanilla player model: a 64x64 texture in the modern player skin layout, with wide (4-pixel) arms and
the outer layer on every part. Use a 64x64 skin with wide arms: an old 64x32 skin does not fit the layout, and a
slim-armed skin (3-pixel arms) does not match the 4-pixel arms.

| Bone | Parent | Texture offset (box UV) | Inflate |
|---|---|---|---|
| `head` | | 0, 0 | |
| `hat` | `head` | 32, 0 | 0.5 |
| `body` | | 16, 16 | |
| `jacket` | `body` | 16, 32 | 0.25 |
| `right_arm` | | 40, 16 | |
| `right_sleeve` | `right_arm` | 40, 32 | 0.25 |
| `left_arm` | | 32, 48 | |
| `left_sleeve` | `left_arm` | 48, 48 | 0.25 |
| `right_leg` | | 0, 16 | |
| `right_pants` | `right_leg` | 0, 32 | 0.25 |
| `left_leg` | | 16, 48 | |
| `left_pants` | `left_leg` | 0, 48 | 0.25 |

Its animations file, `haruhi:humanoid` (`assets/haruhi/geckolib/animations/humanoid.animation.json`), has three
clips:

| Clip | Loop in the file | Length | Moves |
|---|---|---|---|
| `idle` | loop | 6 s | `head`, `body`, `right_arm`, `left_arm` |
| `talk` | loop | 1 s | `head`, `right_arm`, `left_arm` |
| `wave` | play once | 2 s | `right_arm` |

## Profile fields and asset files

Four profile fields describe the look (see the [content guide](content-guide.md#npc-profiles) for the whole profile):

| Field | Example value | File the client loads |
|---|---|---|
| `model` | `shop:mira` | `assets/shop/geckolib/models/mira.geo.json` |
| `animations` | `shop:mira` | `assets/shop/geckolib/animations/mira.animation.json` |
| `texture` | `shop:textures/entity/mira.png` | `assets/shop/textures/entity/mira.png` |
| `portrait` | `shop:textures/portrait/mira.png` | `assets/shop/textures/portrait/mira.png` |

`model` and `animations` name GeckoLib files: the id's path, under `geckolib/models/` or `geckolib/animations/`,
without the `.geo.json` or `.animation.json` suffix. `texture` and `portrait` are texture ids with their full path,
including `textures/` and `.png`. `scale` (above 0, at most 4, default 1) sizes the model, its shadow and its hitbox;
a value below 0.0625 acts as 0.0625, the smallest value Minecraft's scale attribute allows.

```json
"shop:mira": {
  "name": "Mira",
  "model": "shop:mira",
  "animations": "shop:mira",
  "texture": "shop:textures/entity/mira.png",
  "portrait": "shop:textures/portrait/mira.png",
  "dialogue": "shop:mira",
  "scale": 1.0
}
```

### Shipping the files

The files can come from any resource pack the client has loaded. For a resource pack, put the `assets/` tree in a pack
folder or zip next to a `pack.mcmeta`, and enable it on the client (or have the server send it); the converter does
not write `pack.mcmeta`. For a mod, put the `assets/` tree in the mod's resources, which is how Haruhi ships
`haruhi:humanoid`.

Only players who have the files see the model; everyone else sees the fallbacks below. After editing the files, a
resource reload (F3+T) makes GeckoLib bake them again.

### Fallbacks

A profile with a broken or missing asset still renders. For `model` and `animations` the client decides from what
GeckoLib managed to bake, so a file that is present but fails to load counts as missing (GeckoLib logs an "Error
loading resource" with the reason in the client log). For `texture` and `portrait` it checks only that a loaded
resource pack has the file.

| Missing or broken | What the client draws |
|---|---|
| `model` | The default humanoid, with the profile's `texture` mapped onto it as a skin, which only looks right if the texture is a player-layout skin. |
| `texture` | The Steve skin. |
| `animations`, when the profile names it | The default humanoid animations. |
| `animations` not given | The animations file with the model's own id when there is one (the converter writes it there), else the default humanoid animations. |
| A clip name missing from the animations file | The default humanoid's clip of that name, which moves only bones named like the humanoid's. |
| `portrait` | The 3D headshot, when it can be drawn (see [The dialogue headshot](#the-dialogue-headshot)). |
| The whole profile, missing from the live content | The default humanoid with the Steve skin. |

## Animations

Each NPC runs two GeckoLib animation controllers. The base controller loops `idle`, or `talk` while the player's
dialogue screen shows a node that this NPC speaks. Both loop whatever the file says. Only the talking player's client
sees `talk`; everyone else sees `idle`. The actions controller plays a clip on top of the base when something triggers
it by name.

Two things trigger a clip:

| Trigger | Who sees it | When |
|---|---|---|
| A node's `animation` field | The talking player only | When the node arrives on their screen, if the node's speaker is the NPC they are talking to. |
| The `haruhi:animation` action | Every player whose client tracks the NPC and has Haruhi and GeckoLib | When the action runs, on the NPC being talked to. |

A triggered clip keeps one loop mode from its file. A clip set to `hold_on_last_frame` (the `hold` loop mode in a
`.bbmodel`) plays and then holds its last frame until another clip is triggered; use it for a pose that should stay,
such as glasses taken off. Any other clip (loop, play once, or no loop key) plays once, and the base animation takes
over again when it ends. A clip that loops in its file still plays once when triggered.

The names that can be triggered are the clips in the NPC's animations file plus the default humanoid's (`idle`,
`talk`, `wave`); the NPC's own clip wins when both have a name. Any other name does nothing, and nothing checks
animation names when content is validated, so check the spelling against your file.

## The dialogue headshot

The dialogue box shows a square headshot of the speaker, left of the name and text: 64 GUI pixels on a side, smaller
or left out on a small window. It picks, in order:

1. the node's `portrait`, or the speaking profile's `portrait` when the node names none, if the player's client has
   that texture. A node portrait the client lacks does not fall back to the profile's. It is stretched to the square,
   so make portraits square;
2. a 3D render of the speaker's head and shoulders, when the speaker is the NPC or bound entity the player is talking
   to, it is a living entity, and it is loaded on the client. A bound `interaction` entity, item frame or other
   non-living entity gets no 3D headshot;
3. no headshot: the text starts at the box's left edge.

A conversation opened by command or at a bound block has no entity, so it only shows portraits. A node whose `speaker`
is a different profile than the NPC being talked to shows that profile's portrait or nothing. A bound entity that is
not a Haruhi NPC is framed on the top of its hitbox, and plays no animations.

The 3D render turns the body 25 degrees so the face looks toward the text, and the camera looks down at it by 8
degrees. It is framed on the model's head:

- The head is the first bone named `head` in any case (`head`, `Head`), searched depth first from the top-level bones.
- The frame is fitted to the head bone's own cubes in the rest pose, with a little room above, room below for the top
  of the shoulders, and a little at the sides. Cubes in bones under the head (hair, ears, antennae, a hat layer, a
  snout) are left out so they cannot shrink the face to a corner of the frame.
- Only when the head bone has no cubes of its own is the frame fitted to the cubes of every bone under it.
- A model without a head bone, and every NPC on a client without GeckoLib, is framed on the top 30% of its hitbox.
- The frame is measured in the rest pose, so `idle` and `talk` move the head a little inside it.

Name the bone that holds the skull `head`, keep the skull cubes in that bone, and put hair, hats and ears in child
bones. In the default humanoid, the frame fits the 8x8x8 `head` cube without the `hat` layer.

## Name tag placement

The bottom of the name tag sits 1/16 block (one model pixel) above the top of the model as drawn at its `scale`,
instead of on top of the hitbox. The top is the highest point of any cube in the rest pose, counting inflation, so the
default humanoid's top is its hat layer. It is measured once per model each time GeckoLib bakes it, and animations do
not move it.

- The tag follows the model actually drawn, so an NPC that fell back to the default humanoid gets the humanoid's
  height.
- A model with no cubes leaves the tag on top of the hitbox.
- Without GeckoLib the tag goes above the vanilla player model's hat layer.

## The Blockbench converter

The converter turns a Blockbench project into the three files a profile names: a GeckoLib model, an animations file
and one texture. It is a Gradle task in the Haruhi repository, not part of the mod jar, so run it from a checkout
(JDK 25, as for the build). It has been used on Figura avatars.

```
./gradlew bbmodel --args='<in.bbmodel> <outputRoot> <namespace> <name>'
```

In Windows PowerShell: `.\gradlew.bat bbmodel --args='<in.bbmodel> <outputRoot> <namespace> <name>'`.

| Argument | Meaning |
|---|---|
| `<in.bbmodel>` | The Blockbench project. Save it uncompressed: a compressed project (its text starts with `<lz>`) is refused. |
| `<outputRoot>` | The folder to write `assets/` into, such as a resource pack folder. Directories are created. |
| `<namespace>` | The asset namespace: `a-z`, `0-9`, `_`, `.`, `-`. |
| `<name>` | The asset path: the same characters plus `/`, so `npc/mira` is allowed. |

Relative paths resolve against the repository folder, where Gradle runs the task. Textures that are not embedded in
the project are looked up at their path relative to the `.bbmodel` file, then at their absolute path.

It writes:

| File | Contents |
|---|---|
| `<outputRoot>/assets/<namespace>/geckolib/models/<name>.geo.json` | Bedrock geometry, format 1.12.0; 1.16.0 when a bone has a binding; 1.21.0 when a face has a UV rotation. |
| `<outputRoot>/assets/<namespace>/geckolib/animations/<name>.animation.json` | Bedrock animations, format 1.8.0. Only when the project has animations; an older file left there from an earlier run is kept, with a warning. |
| `<outputRoot>/assets/<namespace>/textures/entity/<name>.png` | The one texture the model uses. |

Then it prints what it converted and the profile fields to paste. A run on a small test project (four cubes in three
bones, one mesh, two animations, one with a bezier keyframe) printed:

```
Converted mira.bbmodel -> shop:mira
  wrote:       out\assets\shop\geckolib\models\mira.geo.json
               out\assets\shop\geckolib\animations\mira.animation.json
               out\assets\shop\textures\entity\mira.png
  source:      bbmodel format 5.0, model_format free
  geometry:    format_version 1.12.0
  bones:       3
  cubes:       4
  skipped:     1 mesh
  textures:    used mira.png (64x64)
               ignored none
  texture:     64x64 (mira.png, in its own UV space)
  animations:  2 (idle, bow)
  warnings:    2
    - mesh elements skipped: GeckoLib cannot draw meshes: skirt
    - bezier keyframes written as linear; Blockbench samples the curve, GeckoLib has no bezier: idle Head rotation 1.0
Profile fields:
  "model": "shop:mira",
  "animations": "shop:mira",
  "texture": "shop:textures/entity/mira.png"
```

Read the warnings: each one names something that will look different in game than in Blockbench. Identical warnings
are grouped, with up to eight of the elements or animations they concern listed after them.

### What it converts

The rules follow Blockbench's own Bedrock export, which is what GeckoLib reads.

- Bones are the outliner's groups. Elements at the root go into a first bone named `bb_main`. Bone names keep only
  `A-Z a-z 0-9 _ . -` and must be unique ignoring case; a duplicate gets the first free number from 2 in place of
  any trailing digits (from 1 when the name ends in a single 0), and the first bone keeps its name. Each change is a
  warning, and animations follow the renamed bones. Groups set not to export are skipped unless something inside them
  exports.
- Cubes keep their origin, size, pivot, rotation and inflate, with X mirrored as Blockbench does. Cubes set not to
  export are skipped.
- Box UV cubes stay box UV when GeckoLib can draw them as Blockbench does, with their UV offset and mirroring. A box
  UV cube with a face that is not drawn, faces on different textures, or a texture that is scaled in the atlas is
  written with per-face UVs instead, with a warning; so is one with a fractional size in the `modded_entity` format.
  In other formats a fractional size stays box UV, laid out from the size rounded down, as Blockbench draws it, except
  a size that float noise leaves within 1e-7 below a whole number, which is written per face.
- Per-face UV is written face by face, with Blockbench's flip of the up and down faces, and UV rotation.
- Faces whose texture is set to none are not drawn. A face with no texture assigned is drawn with the default texture
  (the first one, or in `bedrock_block` the one set as default), with a warning outside the single-texture formats
  listed below.
- Projects in the formats with the origin at a block corner (`java_block`, `image`) are moved by -8 in X and Z, with
  a warning, as Blockbench does when converting to an entity format.

### Textures and atlases

GeckoLib draws a model with one texture. Face UVs in a project are in their texture's UV size: the texture's own UV
width and height in formats that have one, else the project resolution.

- When one texture is used, it is copied as it is, and the model keeps its UV size as `texture_width` and
  `texture_height`. The image may have more pixels than the UV size. If the UV size is not whole, UVs are mapped to
  the image's pixels instead, with a warning.
- When several textures are used, they are packed into one atlas image, and every face's UV is moved and scaled into
  it. A face whose UV reaches outside its own texture is a warning, because in the atlas it shows part of a neighbour.
- Unused textures are left out and listed as ignored.
- In the formats that animate textures (`free`, `java_block`, `bedrock`, `bedrock_block`, `bedrock_old`, `image`), an
  animated texture keeps only its first frame, with a warning. Other formats keep the whole image.
- Render modes other than the default (such as emissive) are not carried over; the texture draws as an ordinary one,
  with a warning.
- In the single-texture formats (`bedrock`, `bedrock_old`, `image`, `modded_entity`, `skin`) every drawn face uses the
  first texture, as in Blockbench.

### Animation conversion

| In the `.bbmodel` | In the animations file |
|---|---|
| Loop mode `loop` | `"loop": true` |
| Loop mode `hold` | `"loop": "hold_on_last_frame"` |
| Loop mode `once` | `"loop": false`. A play-once animation with no length is written to loop, as Blockbench does, with a warning. |
| Linear keyframes | plain values |
| Catmull-Rom keyframes | `pre`/`post` values with `"lerp_mode": "catmullrom"` |
| Step keyframes | `pre`/`post` values on the keyframe after the step |
| Bezier keyframes | linear, with a warning |
| Rotation, position and scale channels | written |
| Molang expressions | kept as strings |

- Keyframe times snap to the animation's snapping, as Blockbench exports them. Two keyframes on the same snapped time
  keep the later one, with a warning.
- Signs follow Blockbench: keyframes in files of format 5.0 or later are flipped on export; older files already store
  Bedrock signs and are written unchanged.
- Two animations with the same name, or two animators on the same bone, keep the later one, with a warning.
- Molang is checked without being run: characters GeckoLib's parser rejects (which would stop the file loading),
  functions GeckoLib 5.5.5 does not have, and queries it does not define each give a warning.

### What it skips or changes, with a warning

| Item | What happens |
|---|---|
| Meshes, locators, null objects, texture meshes and other non-cube elements | Skipped. The converter writes cubes only. |
| Elements and groups inside an armature | Skipped. |
| Cube stretch | Dropped; it is not a Bedrock property. |
| Effect keyframes (sound, particle, timeline) | Skipped. |
| Animators of anything other than a bone | Skipped. |
| Keyframe channels other than rotation, position and scale | Skipped. |
| Bezier keyframes | Written linear. |
| Quaternion rotation interpolation | Written as ordinary angles. |
| `override_previous_animation`, `anim_time_update`, `blend_weight`, `start_delay`, `loop_delay`, global rotation (`relative_to`) and bone bindings | Written as Blockbench writes them; GeckoLib 5.5.5 reads them but does not use them. |

The converter stops with `bbmodel: <reason>` and writes nothing for: a compressed project, a file that is not JSON or
not a Blockbench project, a project in which no exported face uses a texture, an embedded texture that is not a
readable base64 PNG, a texture with no embedded image and no readable file on disk, and a `<namespace>` or `<name>`
that breaks the rules in the argument table.

## From .bbmodel to a spawned NPC

1. Build the model in Blockbench with its feet at y 0, its front facing north, the skull in a bone named `head`, and
   clips named `idle` and `talk` (see [Tips](#tips)). Save the project uncompressed, for example as
   `C:/art/mira.bbmodel`.
2. Convert it into a resource pack folder:

   ```
   ./gradlew bbmodel --args='C:/art/mira.bbmodel C:/packs/shop-npcs shop mira'
   ```

   This writes `C:/packs/shop-npcs/assets/shop/geckolib/models/mira.geo.json`,
   `.../geckolib/animations/mira.animation.json` and `.../textures/entity/mira.png`. Fix what the warnings report and
   convert again until they are what you expect.
3. Add a `pack.mcmeta` to `C:/packs/shop-npcs`, copy the folder into the client's `resourcepacks/` folder and enable
   it, or have the server send it as its resource pack.
4. Paste the printed fields into the NPC's profile in the content bundle:

   ```json
   "shop:mira": {
     "name": "Mira",
     "model": "shop:mira",
     "animations": "shop:mira",
     "texture": "shop:textures/entity/mira.png",
     "dialogue": "shop:mira"
   }
   ```

5. Put the content live with `/haruhi content validate`, `import` and `activate`, as the [content
   guide](content-guide.md#workflow) describes. NPCs already standing with that profile change look at once.
6. `/haruhi npc spawn shop:mira ^ ^ ^3` places the NPC three blocks in front of you, facing you.
7. Walk around it and right-click it. Its feet should touch the ground, its face should point at you, the name tag
   should sit just above its head, the headshot should frame its face, and `talk` should play while it speaks. After
   changing the model, convert again and press F3+T.

## Tips

- Put the feet at y 0. The entity's position is the model's origin, so cubes below 0 sink into the ground and a model
  built higher floats.
- Face the model north (-Z), which is its front in game. In the default humanoid the face texture is on the head
  cube's north face.
- Name the skull bone `head` and keep only the skull's cubes in it. Hair, hats, ears and horns go in child bones so the
  headshot frames the face.
- Players click the 0.6 x 1.8 block hitbox (times `scale`) to talk. If the model is much taller, wider or smaller than
  that, adjust the model or `scale` so the box covers the body.
- Give the model its own `idle` and `talk` clips. Without them it falls back to the humanoid's, which only move bones
  with the humanoid's names.
- Give a pose clip the `hold` loop mode when it should stay after it plays; any other clip plays once when triggered.
- Keep the texture's proportions equal to its UV size (64x64 UV wants a square image). With one texture, a
  higher-resolution image of the same proportions works, because the model keeps its UV size.
- Give box UV cubes whole-number sizes. Box UV lays the texture out from the size rounded down, and in the
  `modded_entity` format a fractional size makes the converter write the cube per face.
- Make portraits square, because they are stretched to the square frame.

## Limitations

- The hitbox is 0.6 x 1.8 blocks times `scale`, whatever the model's size or shape.
- Meshes, locators and null objects are not converted; the converter writes cubes only.
- Bezier keyframes become linear, and quaternion interpolation becomes ordinary angles.
- Molang is only checked statically by the converter (characters, function names and query names). It is never
  evaluated, so a mistake in what an expression computes only shows in game.
- Effect keyframes, texture render modes, cube stretch and texture animation (beyond the first frame in the formats
  that animate textures) are not carried over.
- A client without GeckoLib draws every NPC as the vanilla player model, and none of Haruhi's animations play on it.
- The `talk` loop and a node's `animation` show only on the talking player's client.
- The headshot frame and the name tag height come from the rest pose; animations do not move them.
