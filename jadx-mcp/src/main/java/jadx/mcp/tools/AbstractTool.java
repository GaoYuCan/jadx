package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import jadx.mcp.JadxSession;
import jadx.mcp.util.JsonUtil;
import jadx.mcp.util.ToolException;
import jadx.mcp.util.ToolOutputManager;

/**
 * Common boilerplate for jadx-mcp tools: builds the {@link SyncToolSpecification},
 * wraps handler exceptions into structured tool-error results, and provides typed argument extraction.
 */
public abstract class AbstractTool {

	private static final Logger LOG = LoggerFactory.getLogger(AbstractTool.class);

	protected final JadxSession session;

	protected AbstractTool(JadxSession session) {
		this.session = session;
	}

	public abstract String name();

	public abstract String description();

	public abstract JsonSchema schema();

	/**
	 * Tool implementation. Should return any object that can be serialized by Gson; the framework
	 * wraps it in a {@code CallToolResult} with one TextContent entry containing pretty-printed JSON.
	 * <p>
	 * Throw {@link ToolException} for recoverable errors; they will be turned into {@code isError=true}
	 * tool-call results carrying {@code code} / {@code message} / {@code details}.
	 */
	protected abstract Object call(Map<String, Object> args) throws Exception;

	public final SyncToolSpecification spec() {
		Tool tool = Tool.builder()
				.name(name())
				.description(description())
				.inputSchema(schema())
				.build();
		return new SyncToolSpecification(tool, (exchange, request) -> handle(request));
	}

	private CallToolResult handle(CallToolRequest request) {
		Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
		try {
			Object result = call(args);
			String text = JsonUtil.toPrettyJson(result);
			return ToolOutputManager.success(name(), text);
		} catch (ToolException e) {
			return errorResult(e.code().name(), e.getMessage(), e.details());
		} catch (Throwable t) {
			LOG.error("Tool '{}' failed", name(), t);
			return errorResult("INTERNAL", t.getClass().getSimpleName() + ": " + t.getMessage(), null);
		}
	}

	private static CallToolResult errorResult(String code, String message, @Nullable Map<String, Object> details) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", true);
		body.put("code", code);
		body.put("message", message);
		if (details != null && !details.isEmpty()) {
			body.put("details", details);
		}
		return CallToolResult.builder()
				.addTextContent(JsonUtil.toPrettyJson(body))
				.isError(true)
				.build();
	}

	// ---------- typed arg helpers ----------

	protected static String requireString(Map<String, Object> args, String name) {
		Object v = args.get(name);
		if (v == null) {
			throw ToolException.invalidArg(name, "missing required string");
		}
		if (!(v instanceof String s) || s.isEmpty()) {
			throw ToolException.invalidArg(name, "must be a non-empty string");
		}
		return s;
	}

	protected static @Nullable String optString(Map<String, Object> args, String name) {
		Object v = args.get(name);
		if (v == null) {
			return null;
		}
		if (!(v instanceof String s)) {
			throw ToolException.invalidArg(name, "must be a string");
		}
		return s.isEmpty() ? null : s;
	}

	protected static String optString(Map<String, Object> args, String name, String fallback) {
		String s = optString(args, name);
		return s == null ? fallback : s;
	}

	protected static int optInt(Map<String, Object> args, String name, int fallback) {
		Object v = args.get(name);
		if (v == null) {
			return fallback;
		}
		if (v instanceof Number n) {
			return n.intValue();
		}
		if (v instanceof String s) {
			try {
				return Integer.parseInt(s);
			} catch (NumberFormatException nfe) {
				throw ToolException.invalidArg(name, "must be an integer, got '" + s + "'");
			}
		}
		throw ToolException.invalidArg(name, "must be an integer");
	}

	protected static boolean optBool(Map<String, Object> args, String name, boolean fallback) {
		Object v = args.get(name);
		if (v == null) {
			return fallback;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		if (v instanceof String s) {
			return Boolean.parseBoolean(s);
		}
		throw ToolException.invalidArg(name, "must be a boolean");
	}

	protected static @Nullable List<String> optStringList(Map<String, Object> args, String name) {
		Object v = args.get(name);
		if (v == null) {
			return null;
		}
		if (v instanceof List<?> list) {
			List<String> out = new ArrayList<>(list.size());
			for (Object item : list) {
				if (!(item instanceof String s)) {
					throw ToolException.invalidArg(name, "must be an array of strings");
				}
				out.add(s);
			}
			return out;
		}
		if (v instanceof String s) {
			// Lenient: accept comma-separated as a fallback so LLMs that "stringify" the array still work
			return Arrays.asList(s.split("\\s*,\\s*"));
		}
		throw ToolException.invalidArg(name, "must be an array of strings");
	}

	/**
	 * Extract a nested object argument (typically used for "filter" parameters). Returns {@code null}
	 * when missing; throws {@link ToolException} when present but not a JSON object.
	 */
	@SuppressWarnings("unchecked")
	protected static @Nullable Map<String, Object> optMap(Map<String, Object> args, String name) {
		Object v = args.get(name);
		if (v == null) {
			return null;
		}
		if (v instanceof Map<?, ?> m) {
			return (Map<String, Object>) m;
		}
		throw ToolException.invalidArg(name, "must be a JSON object");
	}
}
