package dev.latvian.mods.packsync.repackaged.nbt;

public interface NBTPrimitive extends NBTTag {
	@Override
	default NBTPrimitive copy() {
		return this;
	}
}
