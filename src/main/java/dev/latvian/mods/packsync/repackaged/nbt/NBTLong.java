package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.DataOutput;
import java.io.IOException;

public record NBTLong(long value) implements NBTNumber {
	public static final NBTLong ZERO = new NBTLong(0L);
	public static final NBTLong ONE = new NBTLong(1L);

	public static NBTLong of(long value) {
		if (value == 0L) {
			return ZERO;
		} else if (value == 1L) {
			return ONE;
		} else {
			return new NBTLong(value);
		}
	}

	@Override
	public NBTType getType() {
		return NBTType.LONG;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeLong(value);
	}

	@Override
	@NotNull
	public String toString() {
		return String.valueOf(value);
	}
}
