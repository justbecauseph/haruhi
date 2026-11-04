package town.lampas.haruhi.dialogue.builtin;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.ParseContext;

/** The built-in conditions that read the player and the world: items, advancements, tags, permission, time, weather. */
final class WorldConditions {
    private static final int TICKS_PER_DAY = 24000;

    private WorldConditions() {}

    static DataResult<DialogueCondition> predicate(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, "haruhi:predicate", json, Set.of("predicate"), args -> {
            Identifier predicate = args.id("predicate");
            return ctx -> {
                var server = ctx.player().level().getServer();
                var condition = Lookups.predicate(server, predicate);
                if (condition.isEmpty()) {
                    Haruhi.LOGGER.warn("haruhi:predicate {} is not in the server's predicate registry; test is false",
                            predicate);
                    return false;
                }
                return condition.get().test(Lookups.playerContext(ctx.player()));
            };
        });
    }

    static DataResult<DialogueCondition> hasItem(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, "haruhi:has_item", json, Set.of("item", "count"), args -> {
            String item = args.string("item");
            int count = args.integer("count", 1);
            if (count < 1) {
                throw Args.invalid("'count' must be at least 1");
            }
            boolean tag = item.startsWith("#");
            Identifier id = Identifier.tryParse(tag ? item.substring(1) : item);
            if (id == null) {
                throw Args.invalid("'item' is not an item id or #tag: " + item);
            }
            Predicate<Holder<Item>> matches = tag
                    ? holder -> holder.is(TagKey.create(Registries.ITEM, id))
                    : holder -> holder.is(ResourceKey.create(Registries.ITEM, id));
            return ctx -> countMatching(ctx.player().getInventory(), matches) >= count;
        });
    }

    static DataResult<DialogueCondition> advancement(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, "haruhi:advancement", json,
                Set.of("advancement", "criterion"), args -> {
                    Identifier advancement = args.id("advancement");
                    var criterion = args.optionalString("criterion");
                    return ctx -> Lookups.advancementDone(ctx.player(), advancement, criterion);
                });
    }

    static DataResult<DialogueCondition> tag(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, "haruhi:tag", json, Set.of("tag"), args -> {
            String tag = args.string("tag");
            if (tag.chars().anyMatch(Character::isWhitespace)) {
                throw Args.invalid("'tag' must not contain whitespace");
            }
            return ctx -> ctx.player().entityTags().contains(tag);
        });
    }

    static DataResult<DialogueCondition> permission(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, "haruhi:permission", json, Set.of("level"), args -> {
            PermissionLevel level = PermissionLevel.byId(args.ranged("level", 0, 4));
            Permission required = new Permission.HasCommandLevel(level);
            return ctx -> ctx.player().permissions().hasPermission(required);
        });
    }

    /** Day-time ticks in the player's level. A range with {@code from} above {@code to} wraps past midnight. */
    static DataResult<DialogueCondition> time(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, "haruhi:time", json, Set.of("from", "to"), args -> {
            int from = args.ranged("from", 0, TICKS_PER_DAY - 1);
            int to = args.ranged("to", 0, TICKS_PER_DAY - 1);
            return ctx -> {
                long time = Math.floorMod(ctx.player().level().getDefaultClockTime(), (long) TICKS_PER_DAY);
                return inTimeRange(time, from, to);
            };
        });
    }

    static DataResult<DialogueCondition> weather(JsonObject json, ParseContext context) {
        return Args.<DialogueCondition>parse(context, "haruhi:weather", json, Set.of("weather"), args -> {
            String weather = args.string("weather");
            return switch (weather) {
                case "clear" -> ctx -> !ctx.player().level().isRaining();
                case "rain" -> ctx -> ctx.player().level().isRaining() && !ctx.player().level().isThundering();
                case "thunder" -> ctx -> ctx.player().level().isThundering();
                default -> throw Args.invalid("'weather' must be clear, rain or thunder, not '" + weather + "'");
            };
        });
    }

    static boolean inTimeRange(long time, int from, int to) {
        if (from <= to) {
            return time >= from && time <= to;
        }
        return time >= from || time <= to;
    }

    private static int countMatching(Inventory inventory, Predicate<Holder<Item>> matches) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(matches)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
