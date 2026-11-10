package jadx.mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
		Path input = copyFixtureDex();
		session.load(input.toFile());

		Map<String, Object> result = call(new CurrentProjectTool(session), Map.of());
		assertThat(result)
				.containsEntry("loaded", true)
				.containsEntry("input_file", input.toAbsolutePath().normalize().toString())
				.containsEntry("file_name", input.getFileName().toString())
				.containsEntry("extension", "dex")
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
	void openFileReturnsImmediatelyWhenInputIsAlreadyLoaded() throws Exception {
		Path input = copyFixtureDex();
		session.load(input.toFile());
		Object project = session.read(current -> current);
		Path equivalentPath = input.getParent().resolve(".").resolve(input.getFileName());

		Map<String, Object> result = call(new OpenFileTool(session), Map.of(
				"path", equivalentPath.toString()));

		assertThat(result)
				.containsEntry("loaded", true)
				.containsEntry("already_loaded", true)
				.containsEntry("input_file", equivalentPath.toFile().getAbsolutePath());
		Object currentProject = session.read(current -> current);
		assertThat(currentProject).isSameAs(project);
	}

	@Test
	void rejectsProjectsAndOrdinaryZipWithoutReplacingActiveInput() throws Exception {
		Path input = copyFixtureDex();
		session.load(input.toFile());
		Path project = tempDir.resolve("sample.jadx");
		Files.writeString(project, "{\"files\":[\"hello.dex\"]}");
		Path zip = tempDir.resolve("ordinary.apk");
		try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(zip))) {
			archive.putNextEntry(new ZipEntry("readme.txt"));
			archive.closeEntry();
		}

		OpenFileTool tool = new OpenFileTool(session);
		for (Path unsupported : List.of(project, zip)) {
			assertThatThrownBy(() -> tool.call(Map.of("path", unsupported.toString())))
					.isInstanceOfSatisfying(ToolException.class, error ->
							assertThat(error.code()).isEqualTo(ToolException.Code.UNSUPPORTED));
		}
		assertThat(session.getInputFile()).isEqualTo(input.toFile());
	}

	private Path copyFixtureDex() throws IOException {
		Path fixture = Path.of("../jadx-cli/src/test/resources/samples/hello.dex");
		return Files.copy(fixture, tempDir.resolve("hello.dex"));
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
}
