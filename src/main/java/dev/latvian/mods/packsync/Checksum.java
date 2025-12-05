package dev.latvian.mods.packsync;

import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.neoforgespi.IIssueReporting;

import java.io.BufferedInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public interface Checksum {
	static String checksum(Path path, String algorithm, String format, IIssueReporting issues) {
		if (Files.notExists(path)) {
			return "";
		}

		try (var in = new BufferedInputStream(Files.newInputStream(path))) {
			var md = MessageDigest.getInstance(algorithm);
			var tempBuffer = new byte[32768];

			while (true) {
				int len = in.read(tempBuffer);

				if (len >= 0) {
					md.update(tempBuffer, 0, len);
				} else {
					break;
				}
			}

			return format.formatted(new BigInteger(1, md.digest()));
		} catch (Exception ex) {
			issues.addIssue(ModLoadingIssue.error("Failed to read checksum of file %s!", path.getFileName().toString()).withCause(ex).withAffectedPath(path));
		}

		return "";
	}

	static String md5(Path path, IIssueReporting issues) {
		return checksum(path, "MD5", "%032x", issues);
	}

	static String sha512(Path path, IIssueReporting issues) {
		return checksum(path, "SHA-512", "%0128x", issues);
	}
}
