package net.users.variables;

/**
 * The value types a variable can have, spelled as in Java. At run time a value is held as the
 * matching boxed Java type (Integer, Long, Float, Double, Boolean, String).
 * Numeric types are declared in widening order, so ordinal() doubles as their promotion rank.
 */
public enum VarType {
	INT("int", 0),
	LONG("long", 0L),
	FLOAT("float", 0.0F),
	DOUBLE("double", 0.0),
	BOOLEAN("boolean", false),
	STRING("String", "");

	private final String keyword;
	private final Object defaultValue;

	VarType(String keyword, Object defaultValue) {
		this.keyword = keyword;
		this.defaultValue = defaultValue;
	}

	public String keyword() {
		return keyword;
	}

	/** Java's field default, except String, which defaults to "" because there's no null. */
	public Object defaultValue() {
		return defaultValue;
	}

	public boolean isNumeric() {
		return ordinal() <= DOUBLE.ordinal();
	}

	public static VarType byKeyword(String keyword) {
		for (VarType type : values()) {
			if (type.keyword.equals(keyword)) {
				return type;
			}
		}
		return null;
	}

	public static VarType of(Object value) {
		if (value == null) {
			throw new ScriptException("a void method's result can't be used as a value");
		}
		if (value instanceof Integer) {
			return INT;
		}
		if (value instanceof Long) {
			return LONG;
		}
		if (value instanceof Float) {
			return FLOAT;
		}
		if (value instanceof Double) {
			return DOUBLE;
		}
		if (value instanceof Boolean) {
			return BOOLEAN;
		}
		if (value instanceof String) {
			return STRING;
		}
		throw new IllegalStateException("not a variable value: " + value);
	}
}
