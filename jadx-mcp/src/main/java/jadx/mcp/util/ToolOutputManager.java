package jadx.mcp.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Keeps oversized tool responses out of the stdio MCP payload by writing the full JSON to a local file.
 */
public final class ToolOutputManager {

	private static final Logger LOG = LoggerFactory.getLogger(ToolOutputManager.class);

	private static final int MAX_CHARS = 50_000;
	private static final int PREVIEW_CHARS = 8_000;
	private static final Path OUTPUT_DIR = initOutputDir();

	public static CallToolResult success(String toolName, String jsonText) {
		if (jsonText.length() <= MAX_CHARS) {
			return CallToolResult.builder()
					.addTextContent(jsonText)
					.build();
		}
		try {
			Path file = writeFullOutput(toolName, jsonText);
			Map<String, Object> body = buildTruncatedBody(toolName, jsonText, file);
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("output_truncated", true);
			meta.put("output_file", file.toAbsolutePath().toString());
			meta.put("total_chars", jsonText.length());
			meta.put("max_chars", MAX_CHARS);
			return CallToolResult.builder()
					.addTextContent(JsonUtil.toPrettyJson(body))
					.meta(Map.of("jadx_mcp", meta))
					.build();
		} catch (IOException e) {
			LOG.warn("Failed to persist oversized tool output, falling back to inline result", e);
			return CallToolResult.builder()
					.addTextContent(jsonText)
					.build();
		}
	}

	private static Map<String, Object> buildTruncatedBody(String toolName, String jsonText, Path file) {
		int previewLen = Math.min(Math.min(PREVIEW_CHARS, MAX_CHARS), jsonText.length());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("output_truncated", true);
		body.put("tool", toolName);
		body.put("format", "json");
		body.put("total_chars", jsonText.length());
		body.put("preview_chars", previewLen);
		body.put("output_file", file.toAbsolutePath().toString());
		body.put("read_hint", "Full tool result was written to output_file. Read that JSON file for the complete response.");
		body.put("preview", jsonText.substring(0, previewLen));
		return body;
	}

	private static Path writeFullOutput(String toolName, String jsonText) throws IOException {
		Files.createDirectories(OUTPUT_DIR);
		String safeToolName = sanitize(toolName);
		String fileName = "jadx-mcp-" + safeToolName + "-" + UUID.randomUUID() + ".json";
		Path file = OUTPUT_DIR.resolve(fileName);
		Files.writeString(file, jsonText, StandardCharsets.UTF_8);
		file.toFile().deleteOnExit();
		return file;
	}

	private static Path initOutputDir() {
		String tmp = System.getProperty("java.io.tmpdir");
		Path dir = Path.of(tmp, "jadx-mcp-output-" + currentPid());
		try {
			Files.createDirectories(dir);
			dir.toFile().deleteOnExit();
		} catch (IOException e) {
			LOG.warn("Failed to create jadx-mcp output directory: {}", dir, e);
		}
		return dir;
	}

	private static String sanitize(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder(lower.length());
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
				out.append(c);
			} else {
				out.append('-');
			}
		}
		return out.length() == 0 ? "tool" : out.toString();
	}

	private static long currentPid() {
		return ProcessHandle.current().pid();
	}

	private ToolOutputManager() {
	}
}
