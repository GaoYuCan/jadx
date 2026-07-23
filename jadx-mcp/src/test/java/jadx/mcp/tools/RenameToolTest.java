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

import jadx.api.data.IJavaNodeRef.RefType;
import jadx.gui.settings.JadxProject;
import jadx.gui.settings.data.ProjectData;
import jadx.mcp.JadxSession;
import jadx.mcp.util.ToolException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenameToolTest {

	private static final String RAW_CLASS = "jadx.mcp.tools.RenameToolTest$Fixture";
	private static final String METHOD_TARGET = RAW_CLASS + "#calculate(I)I";

	@TempDir
	Path tempDir;

	private final JadxSession session = new JadxSession();
	private Path input;

	@BeforeEach
	void openFixture() throws Exception {
		input = copyFixtureClass();
		session.load(input.toFile(), List.of(), true, 1);
	}

	@AfterEach
	void closeSession() {
		session.close();
	}

	@Test
	void renamesFieldMethodVariableAndClass() {
		Map<String, Object> field = rename(Map.of(
				"kind", "field",
				"target", RAW_CLASS + "#value:I",
				"new_name", "renamedField"));
		assertThat(field).containsEntry("target", RAW_CLASS + "#value:I");
		assertThat(decompile(RAW_CLASS, false)).contains("renamedField");

		Map<String, Object> method = rename(Map.of(
				"kind", "method",
				"target", METHOD_TARGET,
				"new_name", "renamedCalculate"));
		assertThat(method).containsEntry("target", METHOD_TARGET);
		assertThat(decompile(RAW_CLASS, false)).contains("renamedCalculate");

		Map<String, Object> variable = findVariable("local");
		Map<String, Object> variableRename = rename(Map.of(
				"kind", "variable",
				"target", variable.get("method_target"),
				"variable_id", variable.get("variable_id"),
				"new_name", "renamedLocal"));
		assertThat(variableRename).containsEntry("variable_id", variable.get("variable_id"));
		assertThat(decompile(RAW_CLASS, false)).contains("renamedLocal");

		Map<String, Object> cls = rename(Map.of(
				"kind", "class",
				"target", RAW_CLASS,
				"new_name", "RenamedFixture"));
		assertThat(cls).containsEntry("target", RAW_CLASS);
		assertThat(decompile("jadx.mcp.tools.RenamedFixture", false))
				.contains("class RenamedFixture");
	}

	@Test
	void exposesAndRenamesMethodArgument() {
		Map<String, Object> argument = findVariable("input");

		rename(Map.of(
				"kind", "variable",
				"target", argument.get("method_target"),
				"variable_id", argument.get("variable_id"),
				"new_name", "renamedInput"));

		assertThat(decompile(RAW_CLASS, false)).contains("renamedInput");
	}

	@Test
	void repeatedRenameReplacesExistingRecordAndSavedProjectContainsIt() {
		rename(Map.of("kind", "field", "target", RAW_CLASS + "#value", "new_name", "firstName"));
		Map<String, Object> second = rename(Map.of(
				"kind", "field", "target", RAW_CLASS + "#value", "new_name", "secondName"));
		assertThat(second).containsEntry("rename_count", 1);

		Path output = tempDir.resolve("renamed.jadx");
		new SaveProjectTool(session).call(Map.of("output_path", output.toString()));
		ProjectData project = JadxProject.loadProjectData(output);
		assertThat(project.getCodeData().getRenames()).singleElement().satisfies(rename -> {
			assertThat(rename.getNodeRef().getType()).isEqualTo(RefType.FIELD);
			assertThat(rename.getNewName()).isEqualTo("secondName");
		});

		session.close();
		@SuppressWarnings("unchecked")
		Map<String, Object> opened = (Map<String, Object>) new OpenFileTool(session)
				.call(Map.of("path", output.toString()));
		assertThat(opened)
				.containsEntry("loaded", true)
				.containsEntry("input_file", output.toFile().getAbsolutePath())
				.containsEntry("rename_count", 1);
		assertThat(decompile(RAW_CLASS, false)).contains("secondName");

		@SuppressWarnings("unchecked")
		Map<String, Object> current = (Map<String, Object>) new CurrentProjectTool(session).call(Map.of());
		assertThat(current).containsEntry("input_file", output.toAbsolutePath().normalize().toString());
	}

	@Test
	void rejectsAmbiguousMethodInvalidNameBadVariableAndConstructor() {
		assertCode(Map.of("kind", "method", "target", RAW_CLASS + "#overloaded", "new_name", "renamed"),
				ToolException.Code.AMBIGUOUS);
		assertCode(Map.of("kind", "field", "target", RAW_CLASS + "#value", "new_name", "class"),
				ToolException.Code.INVALID_ARG);
		assertCode(Map.of(
				"kind", "variable",
				"target", METHOD_TARGET,
				"variable_id", "bad",
				"new_name", "validName"), ToolException.Code.INVALID_ARG);
		assertCode(Map.of(
				"kind", "variable",
				"target", METHOD_TARGET,
				"variable_id", "r999v999",
				"new_name", "validName"), ToolException.Code.NOT_FOUND);
		assertCode(Map.of(
				"kind", "method",
				"target", RAW_CLASS + "#<init>()V",
				"new_name", "validName"), ToolException.Code.UNSUPPORTED);
	}

	@Test
	void renameRequiresLoadedProject() {
		session.close();
		assertCode(Map.of("kind", "class", "target", RAW_CLASS, "new_name", "Other"),
				ToolException.Code.NOT_LOADED);
	}

	private Map<String, Object> findVariable(String name) {
		Map<String, Object> result = decompileResult(RAW_CLASS, true);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> variables = (List<Map<String, Object>>) result.get("variables");
		return variables.stream()
				.filter(variable -> name.equals(variable.get("name")))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Variable not found: " + name + ", got " + variables));
	}

	private String decompile(String target, boolean includeVariables) {
		return (String) decompileResult(target, includeVariables).get("code");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> decompileResult(String target, boolean includeVariables) {
		return (Map<String, Object>) new DecompileCodeTool(session).call(Map.of(
				"target", target,
				"line_numbers", false,
				"include_variables", includeVariables));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> rename(Map<String, Object> args) {
		return (Map<String, Object>) new RenameTool(session).call(args);
	}

	private void assertCode(Map<String, Object> args, ToolException.Code code) {
		assertThatThrownBy(() -> new RenameTool(session).call(args))
				.isInstanceOf(ToolException.class)
				.extracting(e -> ((ToolException) e).code())
				.isEqualTo(code);
	}

	private Path copyFixtureClass() throws IOException {
		String resource = "/" + Fixture.class.getName().replace('.', '/') + ".class";
		Path output = tempDir.resolve("RenameToolTest$Fixture.class");
		try (InputStream in = Fixture.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("Missing test fixture resource: " + resource);
			}
			Files.copy(in, output);
		}
		return output;
	}

	private static final class Fixture {
		private int value = 1;

		int calculate(int input) {
			int local = input + value;
			return local * 2;
		}

		int overloaded() {
			return 0;
		}

		int overloaded(int input) {
			return input;
		}
	}
}
