package net.users.variables;

import java.util.ArrayDeque;

/**
 * The loops currently running on this thread. value, index and size in code refer to the innermost
 * one, which also lets actions nested inside for_each_read (run by Apoli, parsed without knowing
 * they're in a loop) see the current element.
 */
final class LoopStack {
	record Frame(ListData list, int index, boolean readOnly) {
	}

	private static final ThreadLocal<ArrayDeque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

	private LoopStack() {
	}

	static void push(Frame frame) {
		FRAMES.get().push(frame);
	}

	static void pop() {
		FRAMES.get().pop();
	}

	private static Frame top(String name) {
		Frame frame = FRAMES.get().peek();
		if (frame == null) {
			throw new ScriptException(name + " only exists inside origins-powerful-powers:for_each and for_each_read");
		}
		return frame;
	}

	static Object read(String name) {
		Frame frame = top(name);
		switch (name) {
			case "value":
				return frame.list().get(frame.index());
			case "index":
				return Integer.valueOf(frame.index());
			default:
				return Integer.valueOf(frame.list().size());
		}
	}

	static VarType typeOf(String name) {
		return name.equals("value") ? top(name).list().elementType() : VarType.INT;
	}

	/** Only value is writable, and only in for_each; the parser already rejects the rest, this is the backstop. */
	static void write(String name, Object value) {
		Frame frame = top(name);
		if (!name.equals("value") || frame.readOnly()) {
			throw new ScriptException(name + " can't be changed here");
		}
		frame.list().set(frame.index(), value);
	}
}
