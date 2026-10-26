package net.users.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.Dynamic3CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.apace100.apoli.command.PowerTypeArgumentType;
import io.github.apace100.apoli.power.InventoryPower;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.PowerTypeRegistry;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.SlotArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.ItemStack;

import java.util.function.UnaryOperator;

/**
 * /opp powerinv ... reads and writes the contents of origins:inventory powers from commands. Those items live in
 * Apoli's power component, which vanilla /item and /data can't reach. Power slots use the container.<n> names of
 * Apoli's item_slot data type.
 *
 *   get   <target> <power> <slot>                 -> item count (for execute store)
 *   id    <target> <power> <slot>                 -> prints the item id; returns its registry number
 *   set   <target> <power> <slot> <item> [count]
 *   clear <target> <power> [slot]
 *   copy  <from> <to>
 *   move  <from> <to>
 *
 * <from>/<to> is "entity <target> <slot>" (any vanilla slot) or "power <target> <power> <slot>".
 * move empties the source and refuses to overwrite a non-empty destination, so it never dupes
 * or deletes items.
 */
public final class PowerInvCommand {
	private static final DynamicCommandExceptionType UNKNOWN_POWER = new DynamicCommandExceptionType(
		id -> Component.literal("Unknown power: " + id));
	private static final Dynamic2CommandExceptionType MISSING_POWER = new Dynamic2CommandExceptionType(
		(entity, id) -> Component.literal(entity + " doesn't have the power " + id));
	private static final DynamicCommandExceptionType NOT_INVENTORY = new DynamicCommandExceptionType(
		id -> Component.literal(id + " isn't an origins:inventory power"));
	private static final Dynamic3CommandExceptionType SLOT_OUT_OF_RANGE = new Dynamic3CommandExceptionType(
		(slot, id, size) -> Component.literal(
			"Slot " + slot + " is outside " + id + ", which has slots container.0 to container." + ((Integer) size - 1)));
	private static final Dynamic2CommandExceptionType NO_SUCH_SLOT = new Dynamic2CommandExceptionType(
		(entity, slot) -> Component.literal(entity + " has no slot " + slot));
	private static final SimpleCommandExceptionType SLOT_REJECTED = new SimpleCommandExceptionType(
		Component.literal("That item can't go in the destination slot"));
	private static final SimpleCommandExceptionType DESTINATION_OCCUPIED = new SimpleCommandExceptionType(
		Component.literal("Destination slot isn't empty; move won't overwrite items"));

	private PowerInvCommand() {
	}

	/** One slot: a vanilla entity slot, or a slot in one of the entity's inventory powers. */
	private record Endpoint(Entity target, SlotAccess access, String label) {
		ItemStack get() {
			return access.get();
		}

		void set(ItemStack stack) throws CommandSyntaxException {
			if (!access.set(stack)) {
				throw SLOT_REJECTED.create();
			}
			afterWrite(target);
		}
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("opp")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("powerinv")
				.then(Commands.literal("get")
					.then(powerSlot("target", slot -> slot.executes(PowerInvCommand::get))))
				.then(Commands.literal("id")
					.then(powerSlot("target", slot -> slot.executes(PowerInvCommand::id))))
				.then(Commands.literal("set")
					.then(powerSlot("target", slot -> slot
						.then(Commands.argument("item", ItemArgument.item(buildContext))
							.executes(context -> set(context, 1))
							.then(Commands.argument("count", IntegerArgumentType.integer(1))
								.executes(context -> set(context, IntegerArgumentType.getInteger(context, "count"))))))))
				.then(Commands.literal("clear")
					.then(Commands.argument("target", EntityArgument.entity())
						.then(Commands.argument("target_power", PowerTypeArgumentType.power())
							.executes(PowerInvCommand::clearAll)
							.then(Commands.argument("target_slot", SlotArgument.slot())
								.executes(PowerInvCommand::clearOne)))))
				.then(transfer("copy", false))
				.then(transfer("move", true))
			)
		);
	}

	private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> powerSlot(
		String prefix, UnaryOperator<RequiredArgumentBuilder<CommandSourceStack, Integer>> slotNode
	) {
		return Commands.argument(prefix, EntityArgument.entity())
			.then(Commands.argument(prefix + "_power", PowerTypeArgumentType.power())
				.then(slotNode.apply(Commands.argument(prefix + "_slot", SlotArgument.slot()))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> entitySlot(
		String prefix, UnaryOperator<RequiredArgumentBuilder<CommandSourceStack, Integer>> slotNode
	) {
		return Commands.argument(prefix, EntityArgument.entity())
			.then(slotNode.apply(Commands.argument(prefix + "_slot", SlotArgument.slot())));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> transfer(String name, boolean move) {
		return Commands.literal(name)
			.then(Commands.literal("entity").then(entitySlot("from", slot -> destinations(slot, false, move))))
			.then(Commands.literal("power").then(powerSlot("from", slot -> destinations(slot, true, move))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, Integer> destinations(
		RequiredArgumentBuilder<CommandSourceStack, Integer> fromSlot, boolean fromPower, boolean move
	) {
		return fromSlot
			.then(Commands.literal("entity")
				.then(entitySlot("to", slot -> slot.executes(context -> transfer(context, fromPower, false, move)))))
			.then(Commands.literal("power")
				.then(powerSlot("to", slot -> slot.executes(context -> transfer(context, fromPower, true, move)))));
	}

	private static int get(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Endpoint slot = endpoint(context, "target", true);
		ItemStack stack = slot.get();
		context.getSource().sendSuccess(() -> Component.literal(slot.label() + ": ")
			.append(stack.isEmpty() ? Component.literal("empty") : Component.literal(stack.getCount() + " x ").append(stack.getDisplayName())), false);
		return stack.getCount();
	}

	/**
	 * Prints the item's id as plain text: "minecraft:spruce_log", or "minecraft:air" for an empty slot. The result is
	 * the item's registry number, which depends only on the item type, so "execute store result" can watch a slot for
	 * a change of item.
	 */
	private static int id(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Endpoint slot = endpoint(context, "target", true);
		ItemStack stack = slot.get();
		ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		context.getSource().sendSuccess(() -> Component.literal(id.toString()), false);
		return BuiltInRegistries.ITEM.getId(stack.getItem());
	}

	private static int set(CommandContext<CommandSourceStack> context, int count) throws CommandSyntaxException {
		Endpoint slot = endpoint(context, "target", true);
		ItemStack stack = ItemArgument.getItem(context, "item").createItemStack(count, false);
		slot.set(stack);
		context.getSource().sendSuccess(() -> Component.literal("Set " + slot.label() + " to " + stack.getCount() + " x ")
			.append(stack.getDisplayName()), true);
		return stack.getCount();
	}

	private static int clearOne(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Endpoint slot = endpoint(context, "target", true);
		int count = slot.get().getCount();
		slot.set(ItemStack.EMPTY);
		context.getSource().sendSuccess(() -> Component.literal("Cleared " + slot.label()), true);
		return count;
	}

	private static int clearAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Entity target = EntityArgument.getEntity(context, "target");
		PowerType<?> type = powerType(context, "target");
		InventoryPower inventory = inventoryPower(target, type);
		int cleared = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (!inventory.getItem(i).isEmpty()) {
				cleared++;
			}
		}
		inventory.clearContent();
		afterWrite(target);
		int finalCleared = cleared;
		context.getSource().sendSuccess(() -> Component.literal(
			"Cleared " + finalCleared + " stack(s) from " + target.getDisplayName().getString() + "'s " + type.getIdentifier()), true);
		return finalCleared;
	}

	private static int transfer(CommandContext<CommandSourceStack> context, boolean fromPower, boolean toPower, boolean move)
		throws CommandSyntaxException {
		Endpoint from = endpoint(context, "from", fromPower);
		Endpoint to = endpoint(context, "to", toPower);
		ItemStack stack = from.get().copy();
		if (move) {
			if (stack.isEmpty()) {
				context.getSource().sendSuccess(() -> Component.literal("Nothing to move: " + from.label() + " is empty"), false);
				return 0;
			}
			if (!to.get().isEmpty()) {
				throw DESTINATION_OCCUPIED.create();
			}
		}
		to.set(stack);
		if (move) {
			from.set(ItemStack.EMPTY);
		}
		context.getSource().sendSuccess(() -> Component.literal(
			(move ? "Moved " : "Copied ") + from.label() + " to " + to.label()), true);
		return stack.getCount();
	}

	private static Endpoint endpoint(CommandContext<CommandSourceStack> context, String prefix, boolean power)
		throws CommandSyntaxException {
		Entity target = EntityArgument.getEntity(context, prefix);
		int slot = SlotArgument.getSlot(context, prefix + "_slot");
		String name = target.getDisplayName().getString();
		if (power) {
			PowerType<?> type = powerType(context, prefix);
			InventoryPower inventory = inventoryPower(target, type);
			if (slot < 0 || slot >= inventory.getContainerSize()) {
				throw SLOT_OUT_OF_RANGE.create(slot, type.getIdentifier(), inventory.getContainerSize());
			}
			return new Endpoint(target, SlotAccess.forContainer(inventory, slot),
				name + "'s " + type.getIdentifier() + " container." + slot);
		}
		SlotAccess access = target.getSlot(slot);
		if (access == SlotAccess.NULL) {
			throw NO_SUCH_SLOT.create(name, slot);
		}
		return new Endpoint(target, access, name + "'s slot " + slot);
	}

	private static PowerType<?> powerType(CommandContext<CommandSourceStack> context, String prefix) throws CommandSyntaxException {
		ResourceLocation id = context.getArgument(prefix + "_power", ResourceLocation.class);
		if (!PowerTypeRegistry.contains(id)) {
			throw UNKNOWN_POWER.create(id);
		}
		return PowerTypeRegistry.get(id);
	}

	private static InventoryPower inventoryPower(Entity target, PowerType<?> type) throws CommandSyntaxException {
		// PowerType#get keeps Cardinal Components off the compile classpath, which PowerHolderComponent would need.
		// Returns null for non-living entities and for entities without the power.
		Power power = type.get(target);
		if (power == null) {
			throw MISSING_POWER.create(target.getDisplayName().getString(), type.getIdentifier());
		}
		if (!(power instanceof InventoryPower inventory)) {
			throw NOT_INVENTORY.create(type.getIdentifier());
		}
		return inventory;
	}

	/**
	 * Push the change to any open container screen. Apoli's own inventory actions don't sync the
	 * power component to the client either, so neither do we.
	 */
	private static void afterWrite(Entity target) {
		if (target instanceof ServerPlayer player) {
			player.containerMenu.broadcastChanges();
		}
	}
}
