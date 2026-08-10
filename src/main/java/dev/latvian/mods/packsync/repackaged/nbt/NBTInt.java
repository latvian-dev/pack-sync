package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.DataOutput;
import java.io.IOException;

public record NBTInt(int value) implements NBTNumber {
	public static final NBTInt ZERO = new NBTInt(0);
	public static final NBTInt ONE = new NBTInt(1);

	public static NBTInt of(int value) {
		return switch (value) {
			case 0 -> ZERO;
			case 1 -> ONE;
			default -> new NBTInt(value);
		};
	}

	@Override
	public NBTType getType() {
		return NBTType.INT;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeInt(value);
	}

	@Override
	@NotNull
	public String toString() {
		return String.valueOf(value);
	}
}
