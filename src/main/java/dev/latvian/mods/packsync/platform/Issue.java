package dev.latvian.mods.packsync.platform;

import java.nio.file.Path;

public class Issue {
	public static Issue error(String message, Object... args) {
		var issue = new Issue();
		issue.warning = false;
		issue.message = message;
		issue.args = args;
		return issue;
	}

	public static Issue warning(String message, Object... args) {
		var issue = new Issue();
		issue.warning = true;
		issue.message = message;
		issue.args = args;
		return issue;
	}

	public boolean warning;
	public String message;
	public Object[] args;
	public Throwable cause;
	public Path path;

	public Issue cause(Throwable cause) {
		this.cause = cause;
		return this;
	}

	public Issue path(Path path) {
		this.path = path;
		return this;
	}
}
