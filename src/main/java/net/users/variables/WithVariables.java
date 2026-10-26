package net.users.variables;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.factory.action.ActionFactory;
import io.github.apace100.apoli.power.factory.condition.ConditionFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.users.OriginsPowerfulPowers;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * origins-powerful-powers:with_variables: any Origins action or condition, written as normal JSON, whose
 * string values may contain ${expression}. Each time it runs, the placeholders are filled in for
 * this entity (Template) and Apoli loads the result, the same as if the values had been typed in.
 * This is how variables reach fields of other Origins types: a command, a velocity, a duration.
 *
 * Apoli can only check the filled-in JSON when it runs, so a mistake in the wrapped action itself (a wrong field
 * name) is logged the first time it runs.
 */
public final class WithVariables {
	private static final ResourceLocation ID = OriginsPowerfulPowers.id("with_variables");
	private static final int MAX_SOURCE_LENGTH = 262_144;

	private WithVariables() {
	}

	public static ActionFactory<Entity> createEntityAction() {
		return new ActionFactory<>(ID, new SerializableData().add("action", templateType(false)), (data, entity) -> {
			Consumer<Entity> action = load((Template) data.get("action"), ApoliDataTypes.ENTITY_ACTION, entity, null);
			if (action != null) {
				action.accept(entity);
			}
		});
	}

	public static ActionFactory<Tuple<Entity, Entity>> createBientityAction() {
		return new ActionFactory<>(ID, new SerializableData().add("action", templateType(true)), (data, pair) -> {
			Consumer<Tuple<Entity, Entity>> action = load((Template) data.get("action"), ApoliDataTypes.BIENTITY_ACTION, pair.getA(), pair.getB());
			if (action != null) {
				action.accept(pair);
			}
		});
	}

	public static ConditionFactory<Entity> createEntityCondition() {
		return new ConditionFactory<>(ID, new SerializableData().add("condition", templateType(false)), (data, entity) -> {
			Predicate<Entity> condition = load((Template) data.get("condition"), ApoliDataTypes.ENTITY_CONDITION, entity, null);
			return condition != null && condition.test(entity);
		});
	}

	public static ConditionFactory<Tuple<Entity, Entity>> createBientityCondition() {
		return new ConditionFactory<>(ID, new SerializableData().add("condition", templateType(true)), (data, pair) -> {
			Predicate<Tuple<Entity, Entity>> condition = load((Template) data.get("condition"), ApoliDataTypes.BIENTITY_CONDITION, pair.getA(), pair.getB());
			return condition != null && condition.test(pair);
		});
	}

	/**
	 * For /opp with: fills ${expression} in a command from this entity's variables and lists.
	 * Throws ScriptException with the reason if a placeholder can't be parsed or evaluated.
	 */
	public static String fillCommand(String command, Entity entity) {
		return Template.fillText(command, new EntityContext(entity, null));
	}

	/** The wrapped JSON, with its placeholders parsed when the pack loads. Only the source text travels to clients. */
	private static SerializableDataType<Template> templateType(boolean bientity) {
		return new SerializableDataType<>(
			Template.class,
			(buf, template) -> buf.writeUtf(template.source(), MAX_SOURCE_LENGTH),
			buf -> Template.parse(JsonParser.parseString(buf.readUtf(MAX_SOURCE_LENGTH)), bientity),
			json -> Template.parse(json, bientity)
		);
	}

	/**
	 * Fills the placeholders for these entities and has Apoli load the result. Each distinct filled-in
	 * JSON is loaded once and cached. Returns null if either step fails; the error is logged once.
	 */
	@SuppressWarnings("unchecked")
	private static <T> T load(Template template, SerializableDataType<?> type, Entity self, Entity other) {
		JsonElement filled;
		try {
			filled = template.fill(new EntityContext(self, other));
		} catch (RuntimeException e) {
			Script.warnOnce("with_variables: " + e.getMessage() + " in: " + template.source());
			return null;
		}
		String key = filled.toString();
		Object result = template.cached(key);
		if (result == null) {
			try {
				result = type.read(filled);
			} catch (RuntimeException e) {
				Script.warnOnce("with_variables: Apoli couldn't load the filled-in JSON (" + e.getMessage() + "): " + key);
				return null;
			}
			template.cache(key, result);
		}
		return (T) result;
	}
}
