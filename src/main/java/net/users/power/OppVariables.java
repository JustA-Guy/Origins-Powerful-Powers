package net.users.power;

import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.apoli.power.factory.action.ActionFactory;
import io.github.apace100.apoli.power.factory.condition.ConditionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.users.OriginsPowerfulPowers;
import net.users.variables.GlobalActions;
import net.users.variables.GlobalVariablesPower;
import net.users.variables.ItemListPower;
import net.users.variables.ListPower;
import net.users.variables.LoopActions;
import net.users.variables.ModifyVariableAction;
import net.users.variables.PowerListPower;
import net.users.variables.Script;
import net.users.variables.VariableBarPower;
import net.users.variables.VariablesPower;
import net.users.variables.WithVariables;

/**
 * Per-entity typed variables and lists, operated on with Java expressions.
 *   power types: origins-powerful-powers:variables, origins-powerful-powers:variable_bar, origins-powerful-powers:list,
 *                origins-powerful-powers:power_list (read-only list of the holder's current power ids),
 *                origins-powerful-powers:item_list (read-only list of the item ids in an origins:inventory power)
 *   loops:       origins-powerful-powers:for_each (code per element), origins-powerful-powers:for_each_read (action per element),
 *                both as entity and bientity actions
 *   entity:      origins-powerful-powers:execute (action, "code"), origins-powerful-powers:modify_variable (action,
 *                one assignment as JSON), origins-powerful-powers:expression (condition)
 *   bientity:    the same three ids; code can also use other.<name> (the target) and self.<name> (the actor)
 */
public final class OppVariables {
	private OppVariables() {
	}

	public static void register() {
		registerPower(VariablesPower.createFactory());
		registerPower(VariableBarPower.createFactory());
		registerPower(ListPower.createFactory());
		registerPower(PowerListPower.createFactory());
		registerPower(ItemListPower.createFactory());
		registerPower(GlobalVariablesPower.createFactory());
		Registry.register(ApoliRegistries.ENTITY_ACTION, OriginsPowerfulPowers.id("modify_global"), GlobalActions.modifyEntity());
		Registry.register(ApoliRegistries.BIENTITY_ACTION, OriginsPowerfulPowers.id("modify_global"), GlobalActions.modifyBientity());
		Registry.register(ApoliRegistries.ENTITY_ACTION, OriginsPowerfulPowers.id("delete_global"), GlobalActions.deleteEntity());
		Registry.register(ApoliRegistries.BIENTITY_ACTION, OriginsPowerfulPowers.id("delete_global"), GlobalActions.deleteBientity());

		Registry.register(ApoliRegistries.ENTITY_ACTION, OriginsPowerfulPowers.id("for_each"), LoopActions.forEach());
		Registry.register(ApoliRegistries.ENTITY_ACTION, OriginsPowerfulPowers.id("for_each_read"), LoopActions.forEachRead());
		Registry.register(ApoliRegistries.BIENTITY_ACTION, OriginsPowerfulPowers.id("for_each"), LoopActions.bientityForEach());
		Registry.register(ApoliRegistries.BIENTITY_ACTION, OriginsPowerfulPowers.id("for_each_read"), LoopActions.bientityForEachRead());

		ResourceLocation execute = OriginsPowerfulPowers.id("execute");
		ResourceLocation expression = OriginsPowerfulPowers.id("expression");

		Registry.register(ApoliRegistries.ENTITY_ACTION, execute, new ActionFactory<Entity>(
			execute,
			new SerializableData().add("code", Script.ENTITY_ACTION),
			(data, entity) -> ((Script) data.get("code")).run(entity, null)
		));
		Registry.register(ApoliRegistries.ENTITY_CONDITION, expression, new ConditionFactory<Entity>(
			expression,
			new SerializableData().add("expression", Script.ENTITY_CONDITION),
			(data, entity) -> ((Script) data.get("expression")).test(entity, null)
		));
		Registry.register(ApoliRegistries.BIENTITY_ACTION, execute, new ActionFactory<Tuple<Entity, Entity>>(
			execute,
			new SerializableData().add("code", Script.BIENTITY_ACTION),
			(data, pair) -> ((Script) data.get("code")).run(pair.getA(), pair.getB())
		));
		ResourceLocation withVariables = OriginsPowerfulPowers.id("with_variables");
		Registry.register(ApoliRegistries.ENTITY_ACTION, withVariables, WithVariables.createEntityAction());
		Registry.register(ApoliRegistries.BIENTITY_ACTION, withVariables, WithVariables.createBientityAction());
		Registry.register(ApoliRegistries.ENTITY_CONDITION, withVariables, WithVariables.createEntityCondition());
		Registry.register(ApoliRegistries.BIENTITY_CONDITION, withVariables, WithVariables.createBientityCondition());

		Registry.register(ApoliRegistries.ENTITY_ACTION, OriginsPowerfulPowers.id("modify_variable"), ModifyVariableAction.createEntityFactory());
		Registry.register(ApoliRegistries.BIENTITY_ACTION, OriginsPowerfulPowers.id("modify_variable"), ModifyVariableAction.createBientityFactory());
		Registry.register(ApoliRegistries.BIENTITY_CONDITION, expression, new ConditionFactory<Tuple<Entity, Entity>>(
			expression,
			new SerializableData().add("expression", Script.BIENTITY_CONDITION),
			(data, pair) -> ((Script) data.get("expression")).test(pair.getA(), pair.getB())
		));
	}

	private static void registerPower(PowerFactory<?> factory) {
		Registry.register(ApoliRegistries.POWER_FACTORY, factory.getSerializerId(), factory);
	}
}
