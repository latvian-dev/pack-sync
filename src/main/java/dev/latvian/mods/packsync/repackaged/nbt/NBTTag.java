package dev.latvian.mods.packsync.repackaged.nbt;

import java.io.BufferedOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

public interface NBTTag {
	NBTType getType();

	void write(DataOutput out) throws IOException;

	default NBTTag copy() {
		return this;
	}

	default NBTTag unwrap() {
		return this;
	}

	default void toString(StringBuilder builder) {
		builder.append(this);
	}

	default void writeFully(OutputStream out) throws IOException {
		var data = new DataOutputStream(out);
		data.writeByte(getType().id);
		data.writeUTF("");
		write(data);
	}

	default void writeFullyCompressed(OutputStream out) throws IOException {
		writeFully(new BufferedOutputStream(new GZIPOutputStream(out)));
	}

	default void write(Path path) throws IOException {
		try (var out = new BufferedOutputStream(Files.newOutputStream(path))) {
			writeFully(out);
		}
	}

	default void writeCompressed(Path path) throws IOException {
		try (var out = new BufferedOutputStream(Files.newOutputStream(path))) {
			writeFullyCompressed(out);
		}
	}
}
