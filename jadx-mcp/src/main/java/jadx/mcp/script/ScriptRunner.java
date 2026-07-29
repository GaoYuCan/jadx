package jadx.mcp.script;

import java.nio.file.Path;
import java.util.List;

import jadx.api.JadxDecompiler;

public interface ScriptRunner {

	ScriptRunResult run(JadxDecompiler decompiler, Path scriptPath);

	final class ScriptRunResult {
		public final boolean success;
		public final String scriptName;
		public final long durationMs;
		public final int afterLoadCallbacks;
		public final List<ScriptLogEntry> logs;
		public final String error;

		public ScriptRunResult(boolean success, String scriptName, long durationMs,
				int afterLoadCallbacks, List<ScriptLogEntry> logs, String error) {
			this.success = success;
			this.scriptName = scriptName;
			this.durationMs = durationMs;
			this.afterLoadCallbacks = afterLoadCallbacks;
			this.logs = logs;
			this.error = error;
		}
	}

	final class ScriptLogEntry {
		public final String level;
		public final String message;
		public final String throwable;

		public ScriptLogEntry(String level, String message, String throwable) {
			this.level = level;
			this.message = message;
			this.throwable = throwable;
		}
	}
}
