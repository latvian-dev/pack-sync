package dev.latvian.mods.packsync.repackaged.nbt;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataOutput;
import java.io.IOException;
import java.util.HexFormat;

public record NBTString(String value) implements NBTNumber {
	public static final HexFormat HEX_FORMAT = HexFormat.of().withUpperCase();

	@Nullable
	public static String escapeControlCharacters(char c) {
		return switch (c) {
			case '\b' -> "b";
			case '\t' -> "t";
			case '\n' -> "n";
			case '\f' -> "f";
			case '\r' -> "r";
			default -> c < ' ' ? "x" + HEX_FORMAT.toHexDigits((byte) c) : null;
		};
	}

	public static void escape(StringBuilder builder, String value) {
		int start = builder.length();
		builder.append(' ');
		char escape = 0;

		for (int i = 0; i < value.length(); i++) {
			var c = value.charAt(i);

			if (c == '\\') {
				builder.append("\\\\");
			} else if (c != '"' && c != '\'') {
				var s = escapeControlCharacters(c);

				if (s != null) {
					builder.append('\\');
					builder.append(s);
				} else {
					builder.append(c);
				}
			} else {
				if (escape == 0) {
					escape = c == '"' ? '\'' : '"';
				}

				if (escape == c) {
					builder.append('\\');
				}

				builder.append(c);
			}
		}

		if (escape == 0) {
			escape = '"';
		}

		builder.setCharAt(start, escape);
		builder.append(escape);
	}

	public static final NBTString EMPTY = new NBTString("");

	public static NBTString of(String value) {
		return value.isEmpty() ? EMPTY : new NBTString(value);
	}

	@Override
	public NBTType getType() {
		return NBTType.STRING;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeUTF(value);
	}

	@Override
	public NBTString copy() {
		return this;
	}

	@Override
	public void toString(StringBuilder builder) {
		escape(builder, value);
	}

	@Override
	@NotNull
	public String toString() {
		var builder = new StringBuilder(value.length() + 2);
		escape(builder, value);
		return builder.toString();
	}
}
