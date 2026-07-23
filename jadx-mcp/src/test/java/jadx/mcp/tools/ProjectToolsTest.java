package jadx.mcp.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.gui.settings.JadxProject;
import jadx.gui.settings.data.ProjectData;
import jadx.mcp.JadxSession;
import jadx.mcp.util.ToolException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectToolsTest {

	@TempDir
	Path tempDir;

	private final JadxSession session = new JadxSession();

	@AfterEach
	void closeSession() {
		session.close();
	}

	@Test
	void currentProjectReportsUnloadedState() {
		Map<String, Object> result = call(new CurrentProjectTool(session), Map.of());

		assertThat(result).containsOnly(Map.entry("loaded", false));
	}

	@Test
	void currentProjectReportsFileMetadataAndSurvivesDeletedInput() throws Exception {
		Path input = copyFixtureClass();
		load(input);

		Map<String, Object> result = call(new CurrentProjectTool(session), Map.of());
		assertThat(result)
				.containsEntry("loaded", true)
				.containsEntry("input_file", input.toAbsolutePath().normalize().toString())
				.containsEntry("file_name", input.getFileName().toString())
				.containsEntry("extension", "class")
				.containsEntry("exists", true)
				.containsKeys("size_bytes", "last_modified");

		Files.delete(input);
		Map<String, Object> deleted = call(new CurrentProjectTool(session), Map.of());
		assertThat(deleted)
				.containsEntry("loaded", true)
				.containsEntry("exists", false)
				.doesNotContainKeys("size_bytes", "last_modified");
	}

	@Test
	void saveProjectWritesGuiCompatibleV2FileAndProtectsExistingOutput() throws Exception {
		Path input = copyFixtureClass();
		load(input);
		Path requested = tempDir.resolve("analysis");

		Map<String, Object> result = call(new SaveProjectTool(session), Map.of("output_path", requested.toString()));
		Path output = tempDir.resolve("analysis.jadx");
		assertThat(result)
				.containsEntry("saved", true)
				.containsEntry("output_file", output.toString())
				.containsEntry("rename_count", 0);
		assertThat(output).isRegularFile();

		ProjectData project = JadxProject.loadProjectData(output);
		assertThat(project.getProjectVersion()).isEqualTo(2);
		assertThat(project.getFiles()).containsExactly(input.toAbsolutePath().normalize());
		assertThat(project.getCodeData().getRenames()).isEmpty();

		assertThatThrownBy(() -> new SaveProjectTool(session).call(Map.of("output_path", output.toString())))
				.isInstanceOf(ToolException.class)
				.extracting(e -> ((ToolException) e).code())
				.isEqualTo(ToolException.Code.INVALID_ARG);

		Map<String, Object> overwritten = call(new SaveProjectTool(session),
				Map.of("output_path", output.toString(), "overwrite", true));
		assertThat(overwritten).containsEntry("saved", true);
	}

	@Test
	void saveProjectRequiresLoadedInput() {
		assertThatThrownBy(() -> new SaveProjectTool(session)
				.call(Map.of("output_path", tempDir.resolve("empty.jadx").toString())))
						.isInstanceOf(ToolException.class)
						.extracting(e -> ((ToolException) e).code())
						.isEqualTo(ToolException.Code.NOT_LOADED);
	}

	private void load(Path input) {
		session.load(input.toFile(), List.of(), true, 1);
	}

	private Path copyFixtureClass() throws IOException {
		String resource = "/" + Fixture.class.getName().replace('.', '/') + ".class";
		Path output = tempDir.resolve("ProjectToolsTest$Fixture.class");
		try (InputStream in = Fixture.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("Missing test fixture resource: " + resource);
			}
			Files.copy(in, output);
		}
		return output;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> call(AbstractTool tool, Map<String, Object> args) {
		try {
			return (Map<String, Object>) tool.call(args);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static final class Fixture {
		private int value;

		int increment(int input) {
			return input + value;
		}
	}
}
