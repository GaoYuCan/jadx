package jadx.mcp.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.mcp.JadxSession;
import jadx.mcp.script.ScriptRunner;
import jadx.mcp.script.ScriptRunner.ScriptLogEntry;
import jadx.mcp.script.ScriptRunner.ScriptRunResult;
import jadx.mcp.util.ToolException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunScriptToolTest {

	@TempDir
	Path tempDir;

	private final JadxSession session = new JadxSession();
	private final CapturingRunner runner = new CapturingRunner();

	@BeforeEach
	void openFixture() throws Exception {
		Path input = copyFixtureClass();
		session.load(input.toFile(), List.of(), true, 1);
	}

	@AfterEach
	void closeSession() {
		session.close();
	}

	@Test
	void acceptsScriptPathAndInlineText() throws Exception {
		Path script = tempDir.resolve("analyze.jadx.kts");
		Files.writeString(script, "println(\"from-path\")");

		Map<String, Object> pathResult = call(Map.of("script_path", script.toString()));
		assertThat(pathResult)
				.containsEntry("success", true)
				.containsEntry("source", "path")
				.containsEntry("script_name", "captured")
				.containsEntry("script_path", script.toAbsolutePath().normalize().toString())
				.containsEntry("after_load_callbacks", 1);
		assertThat(runner.lastText).isEqualTo("println(\"from-path\")");
		assertThat(runner.lastPath).isEqualTo(script.toAbsolutePath().normalize());

		Map<String, Object> textResult = call(Map.of("script_text", "println(\"inline\")"));
		assertThat(textResult)
				.containsEntry("success", true)
				.containsEntry("source", "text")
				.doesNotContainKey("script_path");
		assertThat(runner.lastText).isEqualTo("println(\"inline\")");
		assertThat(runner.lastPath.getFileName().toString()).endsWith(".jadx.kts");
		assertThat(runner.lastPath).doesNotExist();
	}

	@Test
	void requiresExactlyOneValidSource() {
		assertCode(Map.of(), ToolException.Code.INVALID_ARG);
		assertCode(Map.of("script_path", "a", "script_text", "b"), ToolException.Code.INVALID_ARG);
		assertCode(Map.of("script_path", tempDir.resolve("missing.jadx.kts").toString()),
				ToolException.Code.NOT_FOUND);

		Path wrongSuffix = tempDir.resolve("script.kts");
		try {
			Files.writeString(wrongSuffix, "println(1)");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		assertCode(Map.of("script_path", wrongSuffix.toString()), ToolException.Code.INVALID_ARG);
	}

	@Test
	void requiresLoadedProject() {
		session.close();
		assertCode(Map.of("script_text", "println(1)"), ToolException.Code.NOT_LOADED);
		assertThat(runner.calls).isZero();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> call(Map<String, Object> args) {
		return (Map<String, Object>) new RunScriptTool(session, runner).call(args);
	}

	private void assertCode(Map<String, Object> args, ToolException.Code code) {
		assertThatThrownBy(() -> new RunScriptTool(session, runner).call(args))
				.isInstanceOf(ToolException.class)
				.extracting(e -> ((ToolException) e).code())
				.isEqualTo(code);
	}

	private Path copyFixtureClass() throws IOException {
		String resource = "/" + Fixture.class.getName().replace('.', '/') + ".class";
		Path output = tempDir.resolve("RunScriptToolTest$Fixture.class");
		try (InputStream in = Fixture.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("Missing test fixture resource: " + resource);
			}
			Files.copy(in, output);
		}
		return output;
	}

	private static final class CapturingRunner implements ScriptRunner {
		private int calls;
		private Path lastPath;
		private String lastText;

		@Override
		public ScriptRunResult run(jadx.api.JadxDecompiler decompiler, Path scriptPath) {
			calls++;
			lastPath = scriptPath;
			try {
				lastText = Files.readString(scriptPath);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			return new ScriptRunResult(
					true,
					"captured",
					7,
					1,
					List.of(new ScriptLogEntry("INFO", "done", null)),
					null);
		}
	}

	private static final class Fixture {
		private int value;

		int read() {
			return value;
		}
	}
}
