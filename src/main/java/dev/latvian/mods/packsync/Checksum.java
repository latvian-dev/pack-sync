package dev.latvian.mods.packsync;

import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.neoforgespi.IIssueReporting;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public interface Checksum {
	byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

	static String toHex(byte[] array) {
		var chars = new byte[array.length * 2];

		for (int i = 0; i < array.length; i++) {
			int v = array[i] & 0xFF;
			chars[i * 2] = HEX_ARRAY[v >>> 4];
			chars[i * 2 + 1] = HEX_ARRAY[v & 0x0F];
		}

		return new String(chars, StandardCharsets.UTF_8);
	}

	static ByteBuffer allocateTempBuffer(int maxBufferSize, long fileSize) {
		return ByteBuffer.allocate(Math.min(maxBufferSize, (int) Math.min(Integer.MAX_VALUE, fileSize)));
	}

	static String checksum(Path path, String algorithm, IIssueReporting issues) {
		if (Files.notExists(path)) {
			return "";
		}

		try (var channel = Files.newByteChannel(path)) {
			var md = MessageDigest.getInstance(algorithm);
			var buf = allocateTempBuffer(32768, Files.size(path));

			while (channel.read(buf) != -1) {
				buf.flip();
				md.update(buf);
				buf.clear();
			}

			return toHex(md.digest());
		} catch (Exception ex) {
			issues.addIssue(ModLoadingIssue.error("Failed to read checksum of file %s!", path.getFileName().toString()).withCause(ex).withAffectedPath(path));
		}

		return "";
	}

	static String md5(Path path, IIssueReporting issues) {
		return checksum(path, "MD5", issues);
	}

	static String sha512(Path path, IIssueReporting issues) {
		return checksum(path, "SHA-512", issues);
	}
}
