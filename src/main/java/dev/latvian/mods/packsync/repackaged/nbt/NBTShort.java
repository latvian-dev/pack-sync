package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.DataOutput;
import java.io.IOException;

public record NBTShort(short value) implements NBTNumber {
	public static final NBTShort ZERO = new NBTShort((short) 0);
	public static final NBTShort ONE = new NBTShort((short) 1);

	public static NBTShort of(short value) {
		return switch (value) {
			case 0 -> ZERO;
			case 1 -> ONE;
			default -> new NBTShort(value);
		};
	}

	@Override
	public NBTType getType() {
		return NBTType.SHORT;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeShort(value);
	}

	@Override
	@NotNull
	public String toString() {
		return String.valueOf(value);
	}
}
