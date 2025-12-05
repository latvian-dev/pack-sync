package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record NBTLongArray(long[] array) implements NBTTag {
	public static final NBTLongArray EMPTY = new NBTLongArray(new long[0]);

	public static NBTLongArray read(DataInput in) throws IOException {
		int size = in.readInt();

		if (size == 0) {
			return EMPTY;
		} else if (size < 0) {
			throw new NBTException("Negative long array size: " + size);
		}

		var array = new long[size];

		for (int i = 0; i < size; i++) {
			array[i] = in.readLong();
		}

		return new NBTLongArray(array);
	}

	@Override
	public NBTType getType() {
		return NBTType.LONG_ARRAY;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeInt(array.length);

		for (var value : array) {
			out.writeLong(value);
		}
	}

	@Override
	public NBTLongArray copy() {
		if (array.length == 0) {
			return this;
		}

		var copy = new NBTLongArray(new long[array.length]);
		System.arraycopy(array, 0, copy.array, 0, array.length);
		return copy;
	}

	@Override
	public void toString(StringBuilder builder) {
		builder.append("[L;");

		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				builder.append(',');
			}

			builder.append(array[i]);
		}

		builder.append(']');
	}

	@Override
	@NotNull
	public String toString() {
		var builder = new StringBuilder();
		toString(builder);
		return builder.toString();
	}
}
