package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public final class NBTList extends ArrayList<NBTTag> implements NBTTag {
	public static NBTList read(DataInput in) throws IOException {
		var type = NBTType.LOOKUP[in.readByte() & 0xFF];
		int size = in.readInt();

		if (type == null && size > 0) {
			throw new NBTException("Invalid NBT type");
		} else if (size < 0) {
			throw new NBTException("Negative list size: " + size);
		}

		var list = new ArrayList<NBTTag>(size);

		for (int i = 0; i < size; i++) {
			list.add(type.factory.read(in).unwrap());
		}

		return new NBTList(list);
	}

	public NBTList() {
		super(3);
	}

	public NBTList(int size) {
		super(size);
	}

	public NBTList(Collection<? extends NBTTag> values) {
		super(values);
	}

	@Override
	public NBTType getType() {
		return NBTType.LIST;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		if (isEmpty()) {
			out.writeByte(0);
			out.writeInt(0);
			return;
		}

		NBTType type = null;

		for (var tag : this) {
			var t = tag.getType();

			if (type == null) {
				type = t;
			} else if (type != t) {
				type = NBTType.COMPOUND;
				break;
			}
		}

		out.writeByte(type == null ? 0 : type.id);
		out.writeInt(size());

		for (var tag : this) {
			if (type == tag.getType()) {
				tag.write(out);
			} else {
				new NBTCompoundTag(Map.of("", tag)).write(out);
			}
		}
	}

	@Override
	public NBTList copy() {
		var copy = new NBTList(size());

		for (var value : this) {
			copy.add(value.copy());
		}

		return copy;
	}

	@Override
	public void toString(StringBuilder builder) {
		boolean first = true;
		builder.append('[');

		for (var value : this) {
			if (first) {
				first = false;
			} else {
				builder.append(',');
			}

			value.toString(builder);
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
