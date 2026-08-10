package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.DataOutput;
import java.io.IOException;

public record NBTByte(byte value) implements NBTNumber {
	public static final NBTByte ZERO = new NBTByte((byte) 0);
	public static final NBTByte ONE = new NBTByte((byte) 1);

	public static NBTByte of(byte value) {
		return switch (value) {
			case 0 -> ZERO;
			case 1 -> ONE;
			default -> new NBTByte(value);
		};
	}

	@Override
	public NBTType getType() {
		return NBTType.BYTE;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeByte(value);
	}

	@Override
	@NotNull
	public String toString() {
		return String.valueOf(value);
	}
}
