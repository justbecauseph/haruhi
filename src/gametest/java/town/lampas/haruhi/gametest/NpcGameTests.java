package town.lampas.haruhi.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.npc.NpcEntities;
import town.lampas.haruhi.npc.NpcEntity;

/** Server GameTests owned by the npc component. They need every module, so they run after integration. */
public final class NpcGameTests {
    private static final String FIXTURE = "/haruhi/fixtures/npc/npc_bundle.json";
    private static final Identifier GREETER = Identifier.fromNamespaceAndPath("haruhi", "fixture/greeter");

    @GameTest(maxTicks = 120)
    public void npcDoesNotMoveOverOneHundredTicks(GameTestHelper helper) {
        activateFixtures();
        NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
        Vec3 start = npc.position();
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(npc.position().distanceToSqr(start) < 1.0E-9, "the NPC moved on its own");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 1)
    public void npcIgnoresPlayerDamageButNotKill(GameTestHelper helper) {
        activateFixtures();
        ServerPlayer player = mockPlayer(helper);
        NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
        ServerLevel level = helper.getLevel();
        DamageSource attack = level.damageSources().playerAttack(player);

        float health = npc.getHealth();
        helper.assertFalse(npc.hurtServer(level, attack, 10.0F), "the NPC accepted player damage");
        helper.assertValueEqual(npc.getHealth(), health, "the NPC lost health to player damage");

        npc.kill(level);
        helper.assertFalse(npc.isAlive(), "/kill did not remove the NPC");
        helper.succeed();
    }

    @GameTest(maxTicks = 1)
    public void profileSurvivesSaveAndLoad(GameTestHelper helper) {
        activateFixtures();
        NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));

        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        npc.saveWithoutId(output);
        CompoundTag saved = output.buildResult();
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved);

        NpcEntity loaded = NpcEntities.NPC.create(helper.getLevel(), EntitySpawnReason.LOAD);
        helper.assertTrue(loaded != null, "the NPC type could not create an entity");
        loaded.load(input);
        helper.assertValueEqual(loaded.profileId().orElse(null), GREETER, "the profile was lost on load");
        helper.succeed();
    }

    @GameTest(maxTicks = 1)
    public void rightClickOpensTheDialogue(GameTestHelper helper) {
        activateFixtures();
        ServerPlayer player = mockPlayer(helper);
        NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));

        npc.interact(player, InteractionHand.MAIN_HAND, npc.position());
        helper.assertTrue(HaruhiServices.dialogue().isInDialogue(player), "right-click did not open the dialogue");
        helper.succeed();
    }

    /** Validates and activates the fixture bundle, so the NPC profiles and their dialogue exist for the tests. */
    private static void activateFixtures() {
        ContentBundle bundle = HaruhiServices.content().parseAndValidate(readFixture()).getOrThrow();
        HaruhiServices.content().activateUnstored(bundle);
    }

    /** A survival player in the test level. The helper's own factory is deprecated for removal. */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        return (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
    }

    private static NpcEntity spawn(GameTestHelper helper, Identifier profile, Vec3 relative) {
        ServerLevel level = helper.getLevel();
        NpcEntity npc = NpcEntities.NPC.create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(npc != null, "the NPC type could not create an entity");
        Vec3 world = helper.absoluteVec(relative);
        npc.setPos(world.x, world.y, world.z);
        npc.setProfile(profile);
        level.addFreshEntity(npc);
        return npc;
    }

    private static String readFixture() {
        try (InputStream in = NpcGameTests.class.getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new IllegalStateException("missing GameTest fixture " + FIXTURE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
