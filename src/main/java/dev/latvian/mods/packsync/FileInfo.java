package dev.latvian.mods.packsync;

import com.google.gson.JsonObject;
import dev.latvian.mods.packsync.platform.PackSyncPlatformContext;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public record FileInfo(
	String checksum,
	String filename,
	long size,
	Artifact artifact
) {
	public FileInfo(JsonObject json) {
		this(
			json.get("checksum").getAsString(),
			json.get("filename").getAsString(),
			json.get("size").getAsLong(),
			Artifact.of(json)
		);
	}

	public FileInfo(String filename, long size) {
		this("", filename, size, Artifact.NONE);
	}

	public boolean isEqual(PackSyncPlatformContext context, Path path) {
		return size == PackSync.size(path) && checksum.equals(Checksum.md5(context, path));
	}

	public void write(JsonObject json) {
		json.addProperty("checksum", checksum);
		json.addProperty("filename", filename);
		json.addProperty("size", size);
		artifact.write(json);
	}

	@Override
	@NotNull
	public String toString() {
		return filename + " (" + (artifact.equals(Artifact.NONE) ? "" : (artifact + "/")) + checksum + ")";
	}
}
