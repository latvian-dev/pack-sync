package dev.latvian.mods.packsync.repackaged.nbt;

public interface NBTNumber extends NBTPrimitive {
	@Override
	default NBTNumber copy() {
		return this;
	}
}
