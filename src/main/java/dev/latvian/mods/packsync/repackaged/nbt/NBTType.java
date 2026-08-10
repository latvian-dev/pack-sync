package dev.latvian.mods.packsync.repackaged.nbt;

import java.io.DataInput;
import java.io.IOException;

public enum NBTType {
	BYTE(1, in -> NBTByte.of(in.readByte())),
	SHORT(2, in -> NBTShort.of(in.readShort())),
	INT(3, in -> NBTInt.of(in.readInt())),
	LONG(4, in -> NBTLong.of(in.readLong())),
	FLOAT(5, in -> NBTFloat.of(in.readFloat())),
	DOUBLE(6, in -> NBTDouble.of(in.readDouble())),
	BYTE_ARRAY(7, NBTByteArray::read),
	STRING(8, in -> NBTString.of(in.readUTF())),
	LIST(9, NBTList::read),
	COMPOUND(10, NBTCompoundTag::read),
	INT_ARRAY(11, NBTIntArray::read),
	LONG_ARRAY(12, NBTLongArray::read);

	@FunctionalInterface
	public interface Factory {
		NBTTag read(DataInput in) throws IOException;
	}

	public static final NBTType[] VALUES = values();
	public static final NBTType[] LOOKUP = new NBTType[256];

	static {
		for (var type : VALUES) {
			LOOKUP[type.id & 0xFF] = type;
		}
	}

	public final byte id;
	public final Factory factory;

	NBTType(int id, Factory factory) {
		this.id = (byte) id;
		this.factory = factory;
	}
}
