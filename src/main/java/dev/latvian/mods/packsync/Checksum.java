package dev.latvian.mods.packsync;

import dev.latvian.mods.packsync.platform.Issue;
import dev.latvian.mods.packsync.platform.PackSyncPlatformContext;

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

	static String checksum(PackSyncPlatformContext context, Path path, String algorithm) {
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
			context.addIssue(Issue.error("Failed to read checksum of file %s!", path.getFileName().toString()).cause(ex).path(path));
		}

		return "";
	}

	static String md5(PackSyncPlatformContext context, Path path) {
		return checksum(context, path, "MD5");
	}

	static String sha512(PackSyncPlatformContext context, Path path) {
		return checksum(context, path, "SHA-512");
	}
}
