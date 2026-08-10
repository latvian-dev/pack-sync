package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record NBTIntArray(int[] array) implements NBTTag {
	public static final NBTIntArray EMPTY = new NBTIntArray(new int[0]);

	public static NBTIntArray read(DataInput in) throws IOException {
		int size = in.readInt();

		if (size == 0) {
			return EMPTY;
		} else if (size < 0) {
			throw new NBTException("Negative int array size: " + size);
		}

		var array = new int[size];

		for (int i = 0; i < size; i++) {
			array[i] = in.readInt();
		}

		return new NBTIntArray(array);
	}

	@Override
	public NBTType getType() {
		return NBTType.INT_ARRAY;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeInt(array.length);

		for (var value : array) {
			out.writeInt(value);
		}
	}

	@Override
	public NBTIntArray copy() {
		if (array.length == 0) {
			return this;
		}

		var copy = new NBTIntArray(new int[array.length]);
		System.arraycopy(array, 0, copy.array, 0, array.length);
		return copy;
	}

	@Override
	public void toString(StringBuilder builder) {
		builder.append("[I;");

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
