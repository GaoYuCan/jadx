package jadx.mcp.util;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * Recoverable error raised from a tool handler. The MCP layer turns this into a {@code CallToolResult}
 * with {@code isError = true} and a structured payload that the LLM can read.
 */
public class ToolException extends RuntimeException {

	public enum Code {
		INVALID_ARG,
		NOT_FOUND,
		AMBIGUOUS,
		UNSUPPORTED,
		NOT_LOADED,
		INTERNAL
	}

	private final Code code;
	private final @Nullable Map<String, Object> details;

	public ToolException(Code code, String message) {
		this(code, message, null, null);
	}

	public ToolException(Code code, String message, @Nullable Map<String, Object> details) {
		this(code, message, details, null);
	}

	public ToolException(Code code, String message, @Nullable Map<String, Object> details, @Nullable Throwable cause) {
		super(message, cause);
		this.code = code;
		this.details = details;
	}

	public Code code() {
		return code;
	}

	public @Nullable Map<String, Object> details() {
		return details;
	}

	public static ToolException invalidArg(String name, String reason) {
		return new ToolException(Code.INVALID_ARG, "Invalid argument '" + name + "': " + reason);
	}

	public static ToolException notFound(String what, String key) {
		return new ToolException(Code.NOT_FOUND, what + " not found: " + key);
	}

	public static ToolException ambiguous(String what, String key, Map<String, Object> details) {
		return new ToolException(Code.AMBIGUOUS, "Multiple matches for " + what + " '" + key + "'", details);
	}
}
