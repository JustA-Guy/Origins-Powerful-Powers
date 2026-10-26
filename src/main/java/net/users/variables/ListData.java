package net.users.variables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The contents of one origins-powerful-powers:list, with Java List semantics: elements keep the
 * declared type (Java's assignment rules), indexes are bounds-checked, and a list can't be
 * resized while a loop is going through it (Java's ConcurrentModificationException).
 */
public final class ListData {
	private final String name;
	private final VarType elementType;
	private final int maxSize;
	private final boolean dropOldest;
	private final boolean readOnly;
	private final List<Object> values = new ArrayList<>();
	private int iterating;
	private int reading;
	private boolean dirty;

	ListData(String name, VarType elementType, int maxSize, boolean dropOldest, List<Object> initial) {
		this(name, elementType, maxSize, dropOldest, initial, false);
	}

	private ListData(String name, VarType elementType, int maxSize, boolean dropOldest, List<Object> initial, boolean readOnly) {
		this.name = name;
		this.elementType = elementType;
		this.maxSize = maxSize;
		this.dropOldest = dropOldest;
		this.readOnly = readOnly;
		values.addAll(initial);
	}

	/** A computed list code can read and loop over but never change, like the one power_list builds. */
	static ListData readOnly(String name, VarType elementType, List<Object> values) {
		return new ListData(name, elementType, Math.max(1, values.size()), false, values, true);
	}

	public String name() {
		return name;
	}

	public VarType elementType() {
		return elementType;
	}

	public int size() {
		return values.size();
	}

	Object get(int index) {
		checkIndex(index, values.size());
		return values.get(index);
	}

	/** Returns the element that was replaced, as List#set does. */
	Object set(int index, Object value) {
		checkWritable();
		checkNotReading();
		checkIndex(index, values.size());
		Object converted = convert(value);
		Object old = values.set(index, converted);
		dirty |= !old.equals(converted);
		return old;
	}

	void add(Object value) {
		checkNotIterating("add to");
		Object converted = convert(value);
		makeRoom();
		values.add(converted);
		dirty = true;
	}

	void add(int index, Object value) {
		checkNotIterating("add to");
		checkIndex(index, values.size() + 1);
		Object converted = convert(value);
		if (makeRoom()) {
			index = Math.max(0, index - 1);
		}
		values.add(index, converted);
		dirty = true;
	}

	Object removeAt(int index) {
		checkNotIterating("remove from");
		checkIndex(index, values.size());
		dirty = true;
		return values.remove(index);
	}

	boolean removeValue(Object value) {
		checkNotIterating("remove from");
		int index = indexOf(value);
		if (index < 0) {
			return false;
		}
		values.remove(index);
		dirty = true;
		return true;
	}

	void clear() {
		checkNotIterating("clear");
		dirty |= !values.isEmpty();
		values.clear();
	}

	int indexOf(Object value) {
		for (int i = 0; i < values.size(); i++) {
			if (matches(values.get(i), value)) {
				return i;
			}
		}
		return -1;
	}

	boolean contains(Object value) {
		return indexOf(value) >= 0;
	}

	void beginLoop(boolean readOnly) {
		iterating++;
		if (readOnly) {
			reading++;
		}
	}

	void endLoop(boolean readOnly) {
		iterating--;
		if (readOnly) {
			reading--;
		}
	}

	/** True once after any change, so the owner syncs the list to the client once per run. */
	boolean takeDirty() {
		boolean wasDirty = dirty;
		dirty = false;
		return wasDirty;
	}

	List<Object> values() {
		return Collections.unmodifiableList(values);
	}

	/** Saved contents; anything past max_size (the pack lowered it) is dropped. */
	void load(List<Object> loaded) {
		values.clear();
		values.addAll(loaded.subList(0, Math.min(loaded.size(), maxSize)));
	}

	/** True if the oldest element was dropped to make room. */
	private boolean makeRoom() {
		if (values.size() < maxSize) {
			return false;
		}
		if (!dropOldest) {
			throw new ScriptException(name + " is full (max_size " + maxSize + "); set \"when_full\": \"drop_oldest\" to drop the oldest element instead");
		}
		values.remove(0);
		return true;
	}

	private Object convert(Object value) {
		return Ops.assign(value, elementType, "an element of " + name);
	}

	/** Java's List#contains/indexOf use equals(); here 3 also matches 3L, the way == would. */
	private static boolean matches(Object element, Object value) {
		try {
			return Ops.equal(element, value);
		} catch (ScriptException e) {
			return false;
		}
	}

	private static void checkIndex(int index, int length) {
		if (index < 0 || index >= length) {
			throw new ScriptException("IndexOutOfBoundsException: Index " + index + " out of bounds for length " + length);
		}
	}

	private void checkWritable() {
		if (readOnly) {
			throw new ScriptException(name + " is read-only: it's computed fresh each time it's read (origins-powerful-powers:power_list)");
		}
	}

	/** Every structural change goes through here, so it also enforces read-only lists. */
	private void checkNotIterating(String what) {
		checkWritable();
		if (iterating > 0) {
			throw new ScriptException("ConcurrentModificationException: can't " + what + " " + name + " while a loop is going through it");
		}
	}

	private void checkNotReading() {
		if (reading > 0) {
			throw new ScriptException("can't change " + name + " while for_each_read is going through it; use origins-powerful-powers:for_each to change elements");
		}
	}
}
