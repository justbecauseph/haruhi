package town.lampas.haruhi.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.binding.Binding;
import town.lampas.haruhi.binding.BlockBindings;
import town.lampas.haruhi.binding.EntityBindings;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.DialogueModule;
import town.lampas.haruhi.dialogue.api.DialogueSpeaker;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.npc.NpcEntities;
import town.lampas.haruhi.npc.NpcEntity;

/**
 * Server GameTests for block and entity bindings. Bindings are made and removed with the real commands, and a mock player
 * uses the bound block or entity the way the server handles a client's use, so the Fabric use callbacks run before
 * vanilla and the tests see whether the vanilla interaction (a chest's inventory, a villager's trades) still happens.
 *
 * <p>Each step that binds or uses activates the fixture it needs first, in the same step, after the startup activation
 * has finished, so no other test's activation can replace it in between (see {@link DialogueGameTests}).
 */
@SuppressWarnings("removal")
public final class BindingGameTests {
    private static final Identifier BOUND = Identifier.fromNamespaceAndPath("haruhi", "fixture/bound");
    private static final Identifier CLERK = Identifier.fromNamespaceAndPath("haruhi", "fixture/bound_clerk");
    private static final String FIXTURE = "/haruhi/fixtures/binding/bundle.json";
    private static final String WITHOUT_GRAPH = "/haruhi/fixtures/binding/bundle_without_graph.json";
    private static final BlockPos CHEST_AT = new BlockPos(2, 1, 2);
    private static final Vec3 VILLAGER_AT = new Vec3(2.5, 1.0, 4.5);

    @GameTest(maxTicks = 200)
    public void aBoundChestOpensItsConversationInsteadOfItsInventory(GameTestHelper helper) {
        Scene scene = new Scene();
        join(helper, scene)
                .thenExecute(() -> {
                    BlockPos chest = placeChest(helper);
                    useBlock(scene.player, chest, InteractionHand.MAIN_HAND);
                    helper.assertTrue(scene.player.containerMenu instanceof ChestMenu,
                            "an unbound chest opens its inventory");
                    scene.player.closeContainer();

                    activate(helper, FIXTURE);
                    command(helper, "haruhi bind block " + coords(chest) + " " + BOUND);
                    useBlock(scene.player, chest, InteractionHand.MAIN_HAND);
                    helper.assertTrue(scene.player.containerMenu == scene.player.inventoryMenu,
                            "a bound chest must not open its inventory");
                    DialogueSession session = requireSession(helper, scene.player);
                    helper.assertTrue(session.anchor() != null, "a block conversation is anchored to the block");
                    helper.assertValueEqual(session.anchor().position(), Vec3.atCenterOf(chest), "the anchor");
                    helper.assertValueEqual(session.speaker(),
                            DialogueSpeaker.of(Optional.empty(), Blocks.CHEST.getName()),
                            "without a speaker profile the chest speaks with its own name");

                    HaruhiServices.dialogue().close(scene.player);
                    useBlock(scene.player, chest, InteractionHand.OFF_HAND);
                    helper.assertFalse(HaruhiServices.dialogue().isInDialogue(scene.player),
                            "the off hand opens nothing");
                    helper.assertTrue(scene.player.containerMenu == scene.player.inventoryMenu,
                            "the off hand does not reach the chest either");

                    command(helper, "haruhi unbind block " + coords(chest));
                    useBlock(scene.player, chest, InteractionHand.MAIN_HAND);
                    helper.assertTrue(scene.player.containerMenu instanceof ChestMenu,
                            "an unbound chest opens its inventory again");
                    helper.assertFalse(HaruhiServices.dialogue().isInDialogue(scene.player),
                            "an unbound chest opens no conversation");
                    scene.player.closeContainer();
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aBoundVillagerOpensItsConversationInsteadOfTrading(GameTestHelper helper) {
        Scene scene = new Scene();
        join(helper, scene)
                .thenExecute(() -> {
                    Villager villager = spawnLibrarian(helper);
                    useEntity(scene.player, villager, InteractionHand.MAIN_HAND);
                    helper.assertTrue(scene.player.containerMenu instanceof MerchantMenu,
                            "an unbound librarian opens its trades");
                    scene.player.closeContainer();

                    activate(helper, FIXTURE);
                    command(helper, "haruhi bind entity " + villager.getStringUUID() + " " + BOUND + " " + CLERK);
                    useEntity(scene.player, villager, InteractionHand.MAIN_HAND);
                    helper.assertFalse(scene.player.containerMenu instanceof MerchantMenu,
                            "a bound villager must not open its trades");
                    DialogueSession session = requireSession(helper, scene.player);
                    helper.assertTrue(session.interlocutor() == villager, "the villager is the interlocutor");
                    helper.assertValueEqual(session.speaker(),
                            DialogueSpeaker.of(Optional.of(CLERK), villager.getName()), "the binding's speaker");

                    HaruhiServices.dialogue().close(scene.player);
                    command(helper, "haruhi unbind entity " + villager.getStringUUID());
                    helper.assertTrue(EntityBindings.get(villager).isEmpty(), "the villager has no binding left");
                    useEntity(scene.player, villager, InteractionHand.MAIN_HAND);
                    helper.assertTrue(scene.player.containerMenu instanceof MerchantMenu,
                            "an unbound villager trades again");
                    scene.player.closeContainer();
                })
                .thenSucceed();
    }

    /**
     * A binding whose graph the active revision lacks gives the player nothing, neither a conversation nor the vanilla
     * interaction, and {@code /haruhi bindings} marks it.
     */
    @GameTest(maxTicks = 200)
    public void aBindingWhoseGraphIsMissingOpensNothingAndIsListedAsBroken(GameTestHelper helper) {
        Scene scene = new Scene();
        join(helper, scene)
                .thenExecute(() -> {
                    BlockPos chest = placeChest(helper);
                    Villager villager = spawnLibrarian(helper);
                    activate(helper, FIXTURE);
                    command(helper, "haruhi bind block " + coords(chest) + " " + BOUND);
                    command(helper, "haruhi bind entity " + villager.getStringUUID() + " " + BOUND);

                    activate(helper, WITHOUT_GRAPH);
                    useBlock(scene.player, chest, InteractionHand.MAIN_HAND);
                    helper.assertTrue(scene.player.containerMenu == scene.player.inventoryMenu,
                            "the chest stays shut");
                    useEntity(scene.player, villager, InteractionHand.MAIN_HAND);
                    helper.assertFalse(scene.player.containerMenu instanceof MerchantMenu, "the villager does not trade");
                    helper.assertFalse(HaruhiServices.dialogue().isInDialogue(scene.player),
                            "no conversation opens for a graph the revision lacks");

                    List<String> listing = commandOutput(helper, "haruhi bindings", Vec3.atCenterOf(chest));
                    String blockLine = "block minecraft:chest at " + coords(chest) + " in ";
                    helper.assertTrue(listing.stream().anyMatch(line -> line.startsWith(blockLine)
                                    && line.endsWith("[missing graph]")),
                            "the chest is listed with a missing graph: " + listing);
                    helper.assertTrue(listing.stream().anyMatch(line -> line.contains(villager.getStringUUID())
                                    && line.endsWith("[missing graph]")),
                            "the villager is listed with a missing graph: " + listing);

                    command(helper, "haruhi unbind block " + coords(chest));
                    command(helper, "haruhi unbind entity " + villager.getStringUUID());
                })
                .thenSucceed();
    }

    /**
     * A client that does not know the binding goes on to use the held item when its own guess of the block use passes, as
     * it does for stone. The server refuses that item use in the same and the next tick, and lets it through after.
     */
    @GameTest(maxTicks = 200)
    public void aHeldItemIsNotUsedRightAfterABindingTakesTheClick(GameTestHelper helper) {
        Scene scene = new Scene();
        join(helper, scene)
                .thenExecute(() -> {
                    helper.setBlock(CHEST_AT, Blocks.STONE);
                    BlockPos stone = helper.absolutePos(CHEST_AT);
                    scene.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SNOWBALL, 16));
                    activate(helper, FIXTURE);
                    command(helper, "haruhi bind block " + coords(stone) + " " + BOUND);

                    useBlock(scene.player, stone, InteractionHand.MAIN_HAND);
                    requireSession(helper, scene.player);
                    InteractionResult thrown = useItem(scene.player, InteractionHand.MAIN_HAND);
                    helper.assertTrue(thrown instanceof InteractionResult.Fail, "the item use is refused: " + thrown);
                    helper.assertTrue(snowballsNear(helper, scene.player).isEmpty(), "no snowball was thrown");
                    HaruhiServices.dialogue().close(scene.player);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    useItem(scene.player, InteractionHand.MAIN_HAND);
                    helper.assertFalse(snowballsNear(helper, scene.player).isEmpty(),
                            "a later item use is not refused");
                    command(helper, "haruhi unbind block " + coords(helper.absolutePos(CHEST_AT)));
                })
                .thenSucceed();
    }

    /**
     * Breaking a bound block removes its binding. Replacing it any other way leaves the binding inactive until the same
     * block is back, and the listing names the block that is there now.
     */
    @GameTest(maxTicks = 200)
    public void aBrokenBlockLosesItsBindingAndAReplacedOneWaitsForItsBlock(GameTestHelper helper) {
        Scene scene = new Scene();
        join(helper, scene)
                .thenExecute(() -> {
                    BlockPos chest = placeChest(helper);
                    ServerLevel level = helper.getLevel();
                    activate(helper, FIXTURE);
                    command(helper, "haruhi bind block " + coords(chest) + " " + BOUND);

                    level.setBlockAndUpdate(chest, Blocks.STONE.defaultBlockState());
                    helper.assertTrue(BlockBindings.of(level).get(chest).isPresent(),
                            "a block replaced without a player breaking it keeps its binding");
                    useBlock(scene.player, chest, InteractionHand.MAIN_HAND);
                    helper.assertFalse(HaruhiServices.dialogue().isInDialogue(scene.player),
                            "the binding does not apply to the stone");
                    List<String> listing = commandOutput(helper, "haruhi bindings", Vec3.atCenterOf(chest));
                    helper.assertTrue(listing.stream().anyMatch(line -> line.contains(coords(chest))
                                    && line.endsWith("[block is now minecraft:stone]")),
                            "the listing names the block now there: " + listing);
                    helper.assertTrue(scene.player.gameMode.destroyBlock(chest), "the player breaks the stone");
                    helper.assertTrue(BlockBindings.of(level).get(chest).isPresent(),
                            "breaking a block that is not the bound one keeps the binding");

                    level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
                    useBlock(scene.player, chest, InteractionHand.MAIN_HAND);
                    requireSession(helper, scene.player);
                    HaruhiServices.dialogue().close(scene.player);

                    helper.assertTrue(scene.player.gameMode.destroyBlock(chest), "the player breaks the chest");
                    helper.assertTrue(BlockBindings.of(level).get(chest).isEmpty(),
                            "breaking the block removes its binding");
                })
                .thenSucceed();
    }

    /** An entity binding is saved with the entity; a player and a Haruhi NPC cannot be bound. */
    @GameTest(maxTicks = 200)
    public void anEntityBindingIsSavedWithTheEntityAndNpcsAreRefused(GameTestHelper helper) {
        Scene scene = new Scene();
        join(helper, scene)
                .thenExecute(() -> {
                    Villager villager = spawnLibrarian(helper);
                    activate(helper, FIXTURE);
                    command(helper, "haruhi bind entity " + villager.getStringUUID() + " " + BOUND + " " + CLERK);

                    Villager loaded = saveAndLoad(helper, villager);
                    helper.assertValueEqual(EntityBindings.get(loaded), Optional.of(new Binding(BOUND, Optional.of(CLERK))),
                            "the binding read back from the saved entity");

                    NpcEntity npc = NpcEntities.NPC.create(helper.getLevel(), EntitySpawnReason.COMMAND);
                    helper.assertTrue(npc != null, "the NPC type could not create an entity");
                    npc.setPos(helper.absoluteVec(new Vec3(5.5, 1.0, 5.5)));
                    helper.getLevel().addFreshEntity(npc);
                    String refused = refusal(helper, "haruhi bind entity " + npc.getStringUUID() + " " + BOUND);
                    helper.assertTrue(refused.contains("Haruhi NPC"), "the NPC is refused as an NPC: " + refused);
                    String player = refusal(helper, "haruhi bind entity " + scene.player.getStringUUID() + " " + BOUND);
                    helper.assertTrue(player.contains("player"), "the player is refused as a player: " + player);
                    helper.assertTrue(EntityBindings.get(npc).isEmpty() && EntityBindings.get(scene.player).isEmpty(),
                            "neither was bound");
                    String unknown = refusal(helper, "haruhi bind entity " + villager.getStringUUID()
                            + " haruhi:fixture/no_such_graph");
                    helper.assertTrue(unknown.contains("Unknown dialogue graph"), "an unknown graph is refused");
                    npc.discard();
                    villager.discard();
                })
                .thenSucceed();
    }

    /** The test's player, joined and standing in the test structure. */
    private static final class Scene {
        ServerPlayer player;
    }

    /**
     * Waits for the startup activation, joins a mock player whose client counts as loaded (the packet handlers drop
     * uses from a client that is not), stands it in the structure, and waits for its dialogue flags.
     */
    private static GameTestSequence join(GameTestHelper helper, Scene scene) {
        return helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(HaruhiServices.content().isStartupComplete(),
                        "the startup content activation has not finished"))
                .thenExecute(() -> {
                    scene.player = helper.makeMockServerPlayerInLevel();
                    scene.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                    Vec3 standing = helper.absoluteVec(new Vec3(3.5, 1.0, 3.5));
                    scene.player.setPos(standing.x, standing.y, standing.z);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        DialogueModule.sessions().loadedFlags(scene.player).isPresent(),
                        "the player's dialogue flags have not loaded"));
    }

    private static BlockPos placeChest(GameTestHelper helper) {
        helper.setBlock(CHEST_AT, Blocks.CHEST);
        return helper.absolutePos(CHEST_AT);
    }

    /** A librarian without AI, so it keeps its profession and offers trades to a player who uses it. */
    private static Villager spawnLibrarian(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityTypes.VILLAGER, VILLAGER_AT);
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(helper.getLevel().registryAccess(), VillagerProfession.LIBRARIAN)
                .withLevel(1));
        return villager;
    }

    private static DialogueSession requireSession(GameTestHelper helper, ServerPlayer player) {
        Optional<DialogueSession> session = DialogueModule.sessions().session(player);
        helper.assertTrue(session.isPresent(), "no conversation has started");
        helper.assertValueEqual(session.get().graphId(), BOUND, "the graph of the conversation");
        return session.get();
    }

    /**
     * Uses the block on its top face, at its centre, through {@code ServerPlayerGameMode.useItemOn}: the call the
     * use-item-on packet handler makes after its reach checks, and the one Fabric's {@code UseBlockCallback} hooks. The
     * handler itself drops block uses until the client confirms its join teleport, which a mock player never does.
     */
    private static void useBlock(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand, hit);
    }

    /** Uses the held item through {@code ServerPlayerGameMode.useItem}, which Fabric's {@code UseItemCallback} hooks. */
    private static InteractionResult useItem(ServerPlayer player, InteractionHand hand) {
        return player.gameMode.useItem(player, player.level(), player.getItemInHand(hand), hand);
    }

    private static List<Snowball> snowballsNear(GameTestHelper helper, ServerPlayer player) {
        return helper.getLevel().getEntities(EntityTypes.SNOWBALL, player.getBoundingBox().inflate(4.0),
                snowball -> true);
    }

    /** Uses the entity as a client does: an interact packet for the hand. */
    private static void useEntity(ServerPlayer player, Entity entity, InteractionHand hand) {
        player.connection.handleInteract(new ServerboundInteractPacket(entity.getId(), hand, Vec3.ZERO, false));
    }

    private static Villager saveAndLoad(GameTestHelper helper, Villager villager) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess());
        villager.saveWithoutId(output);
        CompoundTag saved = output.buildResult();
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved);
        Villager loaded = EntityTypes.VILLAGER.create(helper.getLevel(), EntitySpawnReason.LOAD);
        helper.assertTrue(loaded != null, "the villager type could not create an entity");
        loaded.load(input);
        return loaded;
    }

    private static String coords(BlockPos pos) {
        return String.format(Locale.ROOT, "%d %d %d", pos.getX(), pos.getY(), pos.getZ());
    }

    private static CommandSourceStack source(GameTestHelper helper) {
        return helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel())
                .withSuppressedOutput();
    }

    /** Runs a command as the server, failing the test if it is refused. */
    private static void command(GameTestHelper helper, String command) {
        try {
            helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source(helper));
        } catch (CommandSyntaxException e) {
            helper.fail("/" + command + " was refused: " + e.getMessage());
        }
    }

    /** Runs a command that must be refused, and returns the refusal. */
    private static String refusal(GameTestHelper helper, String command) {
        try {
            helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source(helper));
        } catch (CommandSyntaxException e) {
            return e.getMessage();
        }
        helper.fail("/" + command + " should have been refused");
        return "";
    }

    /** Runs a command at {@code position} as the server and returns the lines it replied with. */
    private static List<String> commandOutput(GameTestHelper helper, String command, Vec3 position) {
        List<String> lines = new ArrayList<>();
        CommandSource recorder = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                lines.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack()
                .withLevel(helper.getLevel()).withPosition(position).withSource(recorder);
        try {
            helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            helper.fail("/" + command + " was refused: " + e.getMessage());
        }
        return lines;
    }

    /** Parses a fixture with the content service and makes it the active content, unstored. */
    private static void activate(GameTestHelper helper, String fixture) {
        DataResult<ContentBundle> bundle = HaruhiServices.content().parseAndValidate(read(helper, fixture));
        helper.assertTrue(bundle.result().isPresent(),
                fixture + " must validate: " + bundle.error().map(error -> error.message()).orElse("?"));
        HaruhiServices.content().activateUnstored(bundle.result().orElseThrow());
    }

    private static String read(GameTestHelper helper, String fixture) {
        try (InputStream stream = BindingGameTests.class.getResourceAsStream(fixture)) {
            helper.assertTrue(stream != null, "the fixture " + fixture + " is missing from the test resources");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            helper.fail("could not read " + fixture + ": " + e.getMessage());
            return "";
        }
    }
}
