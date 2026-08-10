package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.DataOutput;
import java.io.IOException;

public record NBTFloat(float value) implements NBTNumber {
	public static final NBTFloat ZERO = new NBTFloat(0F);
	public static final NBTFloat ONE = new NBTFloat(1F);

	public static NBTFloat of(float value) {
		if (value == 0F) {
			return ZERO;
		} else if (value == 1F) {
			return ONE;
		} else {
			return new NBTFloat(value);
		}
	}

	@Override
	public NBTType getType() {
		return NBTType.FLOAT;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeFloat(value);
	}

	@Override
	@NotNull
	public String toString() {
		return String.valueOf(value);
	}
}
