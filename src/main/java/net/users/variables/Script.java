package net.users.variables;

import io.github.apace100.calio.data.SerializableDataType;
import net.minecraft.world.entity.Entity;
import net.users.OriginsPowerfulPowers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Parsed "code" (statements) or "expression" (one boolean expression). Parsing happens when the pack
 * loads, so syntax errors fail the power with a column number. One Script is shared by every entity
 * that runs it; each run gets its own EntityContext.
 */
public final class Script {
	public static final SerializableDataType<Script> ENTITY_ACTION = dataType(Parser.Mode.ACTION, false);
	public static final SerializableDataType<Script> ENTITY_CONDITION = dataType(Parser.Mode.CONDITION, false);
	public static final SerializableDataType<Script> BIENTITY_ACTION = dataType(Parser.Mode.ACTION, true);
	public static final SerializableDataType<Script> BIENTITY_CONDITION = dataType(Parser.Mode.CONDITION, true);
	static final SerializableDataType<Script> FOR_EACH_CODE = dataType(Parser.Mode.FOR_EACH, false);
	static final SerializableDataType<Script> BIENTITY_FOR_EACH_CODE = dataType(Parser.Mode.FOR_EACH, true);

	private static final int MAX_SOURCE_LENGTH = 262_144;
	private static final int MAX_REPORTED = 512;
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	private final String source;
	private final List<Node> body;

	private Script(String source, List<Node> body) {
		this.source = source;
		this.body = body;
	}

	private static SerializableDataType<Script> dataType(Parser.Mode mode, boolean bientity) {
		return sourceType(Script.class, Script::source, code -> parse(code, mode, bientity));
	}

	/**
	 * A Calio data type for code: reads a string or an array of lines (SourceLines) and parses it when
	 * the pack loads. Over the network only the joined source travels, and the client parses it again.
	 */
	static <T> SerializableDataType<T> sourceType(Class<T> type, Function<T, String> toSource, Function<String, T> parse) {
		return new SerializableDataType<>(
			type,
			(buf, value) -> buf.writeUtf(toSource.apply(value), MAX_SOURCE_LENGTH),
			buf -> parse.apply(buf.readUtf(MAX_SOURCE_LENGTH)),
			json -> parse.apply(SourceLines.join(json))
		);
	}

	static Script parse(String source, Parser.Mode mode, boolean bientity) {
		Parser parser = new Parser(source, mode, bientity);
		return new Script(source, mode == Parser.Mode.CONDITION ? List.of(parser.parseCondition()) : parser.parseStatements());
	}

	public String source() {
		return source;
	}

	/** One statement built from JSON (modify_variable). description names it in errors. */
	static void runStatement(Node statement, String description, Entity self, Entity other) {
		new Script(description, List.of(statement)).run(self, other);
	}

	/**
	 * Runs the statements in order. A failing statement stops the rest; changes made before it are kept, as in Java.
	 * Returns false if a statement failed.
	 */
	public boolean run(Entity self, Entity other) {
		EntityContext context = new EntityContext(self, other);
		try {
			for (Node statement : body) {
				statement.eval(context);
			}
			return true;
		} catch (RuntimeException e) {
			report(e);
			return false;
		} finally {
			context.flush();
		}
	}

	/** False when the expression can't be evaluated (the error goes to the log). */
	public boolean test(Entity self, Entity other) {
		try {
			return Ops.bool(body.get(0).eval(new EntityContext(self, other)), "a condition");
		} catch (RuntimeException e) {
			report(e);
			return false;
		}
	}

	private void report(RuntimeException e) {
		String message = e instanceof ScriptException ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
		warnOnce(message + " in: " + source);
	}

	/** Code that runs every tick would otherwise flood the log with the same error. */
	static void warnOnce(String message) {
		if (REPORTED.size() < MAX_REPORTED && REPORTED.add(message)) {
			OriginsPowerfulPowers.LOGGER.warn("[variables] {}", message);
		}
	}
}
