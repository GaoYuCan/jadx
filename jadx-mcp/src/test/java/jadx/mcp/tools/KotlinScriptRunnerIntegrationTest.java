package jadx.mcp.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.mcp.JadxSession;
import jadx.mcp.script.ScriptRunner.ScriptLogEntry;

import static org.assertj.core.api.Assertions.assertThat;

class KotlinScriptRunnerIntegrationTest {

	@TempDir
	Path tempDir;

	private final JadxSession session = new JadxSession();

	@AfterEach
	void closeSession() {
		session.close();
	}

	@Test
	void executesPathAndInlineAfterLoadAgainstCurrentDecompilerWhenPluginIsInstalled() throws Exception {
		Path input = copyFixtureClass();
		session.load(input.toFile(), List.of(), true, 1);
		boolean pluginInstalled = session.read(decompiler -> decompiler.getPluginManager()
				.getResolvedPluginContexts()
				.stream()
				.anyMatch(context -> context.getPluginId().equals("jadx-script-kotlin")));
		Assumptions.assumeTrue(pluginInstalled, "jadx-script-kotlin is not installed");

		Path script = tempDir.resolve("integration.jadx.kts");
		Files.writeString(script,
				"val jadx = getJadxInstance()\n"
						+ "jadx.afterLoad {\n"
						+ "    println(\"mcp-path-class-count=${jadx.classes.size}\")\n"
						+ "}\n");
		@SuppressWarnings("unchecked")
		Map<String, Object> pathResult = (Map<String, Object>) new RunScriptTool(session).call(Map.of(
				"script_path",
				script.toString()));
		assertSuccessfulLog(pathResult, "path", "mcp-path-class-count=");

		@SuppressWarnings("unchecked")
		Map<String, Object> textResult = (Map<String, Object>) new RunScriptTool(session).call(Map.of(
				"script_text",
				"val jadx = getJadxInstance()\n"
						+ "jadx.afterLoad {\n"
						+ "    println(\"mcp-text-class-count=${jadx.classes.size}\")\n"
						+ "}\n"));
		assertSuccessfulLog(textResult, "text", "mcp-text-class-count=");
	}

	private static void assertSuccessfulLog(Map<String, Object> result, String source, String prefix) {
		assertThat(result)
				.containsEntry("success", true)
				.containsEntry("source", source)
				.containsEntry("after_load_callbacks", 1);
		@SuppressWarnings("unchecked")
		List<ScriptLogEntry> logs = (List<ScriptLogEntry>) result.get("logs");
		assertThat(logs)
				.extracting(entry -> entry.message)
				.anyMatch(message -> message.startsWith(prefix));
	}

	private Path copyFixtureClass() throws IOException {
		String resource = "/" + Fixture.class.getName().replace('.', '/') + ".class";
		Path output = tempDir.resolve("KotlinScriptRunnerIntegrationTest$Fixture.class");
		try (InputStream in = Fixture.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("Missing test fixture resource: " + resource);
			}
			Files.copy(in, output);
		}
		return output;
	}

	private static final class Fixture {
		int value() {
			return 1;
		}
	}
}
