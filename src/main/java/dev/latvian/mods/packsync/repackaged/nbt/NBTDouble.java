package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.DataOutput;
import java.io.IOException;

public record NBTDouble(double value) implements NBTNumber {
	public static final NBTDouble ZERO = new NBTDouble(0D);
	public static final NBTDouble ONE = new NBTDouble(1D);

	public static NBTDouble of(double value) {
		if (value == 0D) {
			return ZERO;
		} else if (value == 1D) {
			return ONE;
		} else {
			return new NBTDouble(value);
		}
	}

	@Override
	public NBTType getType() {
		return NBTType.DOUBLE;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeDouble(value);
	}

	@Override
	@NotNull
	public String toString() {
		return String.valueOf(value);
	}
}
