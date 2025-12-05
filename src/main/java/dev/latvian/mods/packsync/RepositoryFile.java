package dev.latvian.mods.packsync;

import com.google.gson.JsonObject;

import java.nio.file.Path;

public record RepositoryFile(Path path, FileInfo fileInfo) {
	public RepositoryFile(Path path, JsonObject json) {
		this(path, new FileInfo(json));
	}
}