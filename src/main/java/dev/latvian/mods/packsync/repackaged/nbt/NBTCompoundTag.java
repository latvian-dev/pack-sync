package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class NBTCompoundTag extends LinkedHashMap<String, NBTTag> implements NBTTag {
	public static NBTCompoundTag read(DataInput in) throws IOException {
		var map = new NBTCompoundTag();
		NBTType type;

		while ((type = NBTType.LOOKUP[in.readUnsignedByte()]) != null) {
			var key = in.readUTF();
			var tag = type.factory.read(in);
			map.put(key, tag);
		}

		return new NBTCompoundTag(map);
	}

	public static NBTCompoundTag readFully(InputStream in) throws IOException {
		var data = new DataInputStream(in);
		data.readByte();
		data.readUTF();
		return read(data);
	}

	public static NBTCompoundTag readFullyCompressed(InputStream in) throws IOException {
		return readFully(new BufferedInputStream(new GZIPInputStream(in)));
	}

	public static NBTCompoundTag read(Path path) throws IOException {
		try (var in = new BufferedInputStream(Files.newInputStream(path))) {
			return readFully(in);
		}
	}

	public static NBTCompoundTag readCompressed(Path path) throws IOException {
		try (var in = new BufferedInputStream(Files.newInputStream(path))) {
			return readFullyCompressed(in);
		}
	}

	public NBTCompoundTag() {
		super(3);
	}

	public NBTCompoundTag(int size) {
		super(size);
	}

	public NBTCompoundTag(Map<String, ? extends NBTTag> entries) {
		super(entries);
	}

	@Override
	public NBTType getType() {
		return NBTType.COMPOUND;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		for (var entry : entrySet()) {
			var key = entry.getKey();
			var tag = entry.getValue();

			if (tag != null) {
				out.writeByte(tag.getType().id);
				out.writeUTF(key);
				tag.write(out);
			} else {
				out.writeByte(0);
			}
		}

		out.writeByte(0);
	}

	@Override
	public NBTCompoundTag copy() {
		var copy = new NBTCompoundTag(size());

		for (var entry : entrySet()) {
			copy.put(entry.getKey(), entry.getValue().copy());
		}

		return copy;
	}

	@Override
	public NBTTag unwrap() {
		if (size() == 1) {
			var first = firstEntry();

			if (first.getKey().isEmpty()) {
				return first.getValue();
			}
		}

		return this;
	}

	@Override
	public void toString(StringBuilder builder) {
		boolean first = true;
		builder.append('{');

		for (var entry : entrySet()) {
			if (first) {
				first = false;
			} else {
				builder.append(',');
			}

			NBTString.escape(builder, entry.getKey());
			builder.append(':');
			entry.getValue().toString(builder);
		}

		builder.append('}');
	}

	@Override
	@NotNull
	public String toString() {
		var builder = new StringBuilder();
		toString(builder);
		return builder.toString();
	}
}
