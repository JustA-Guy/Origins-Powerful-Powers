package net.users.variables;

/** The "operation" of origins-powerful-powers:modify_variable, and the Java statement each one stands for. */
public enum ModifyOperation {
	SET("set", null),
	ADD("add", "+"),
	SUBTRACT("subtract", "-"),
	MULTIPLY("multiply", "*"),
	DIVIDE("divide", "/"),
	MODULO("modulo", "%"),
	MIN("min", "min"),
	MAX("max", "max"),
	AND("and", "&"),
	OR("or", "|"),
	XOR("xor", "^"),
	SHIFT_LEFT("shift_left", "<<"),
	SHIFT_RIGHT("shift_right", ">>"),
	UNSIGNED_SHIFT_RIGHT("unsigned_shift_right", ">>>");

	private final String id;
	private final String op;

	ModifyOperation(String id, String op) {
		this.id = id;
		this.op = op;
	}

	public String id() {
		return id;
	}

	/** The operator for Node.Assign: null means plain '=', anything else behaves like compound assignment. */
	String op() {
		return op;
	}

	public static ModifyOperation byId(String id) {
		for (ModifyOperation operation : values()) {
			if (operation.id.equals(id)) {
				return operation;
			}
		}
		return null;
	}

	/** The equivalent Java statement, used in error messages: "coins += coins / 100". */
	String java(String target, String value) {
		if (this == SET) {
			return target + " = " + value;
		}
		if (this == MIN || this == MAX) {
			return target + " = Math." + id + "(" + target + ", " + value + ")";
		}
		return target + " " + op + "= " + value;
	}
}
