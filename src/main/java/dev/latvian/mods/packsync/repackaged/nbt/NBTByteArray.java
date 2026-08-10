package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record NBTByteArray(byte[] array) implements NBTTag {
	public static final NBTByteArray EMPTY = new NBTByteArray(new byte[0]);

	public static NBTByteArray read(DataInput in) throws IOException {
		int size = in.readInt();

		if (size == 0) {
			return EMPTY;
		} else if (size < 0) {
			throw new NBTException("Negative byte array size: " + size);
		}

		var array = new byte[size];
		in.readFully(array);
		return new NBTByteArray(array);
	}

	@Override
	public NBTType getType() {
		return NBTType.BYTE_ARRAY;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeInt(array.length);
		out.write(array);
	}

	@Override
	public NBTByteArray copy() {
		if (array.length == 0) {
			return this;
		}

		var copy = new NBTByteArray(new byte[array.length]);
		System.arraycopy(array, 0, copy.array, 0, array.length);
		return copy;
	}

	@Override
	public void toString(StringBuilder builder) {
		builder.append("[B;");

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
