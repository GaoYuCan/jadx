package jadx.mcp.project;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import jadx.api.data.ICodeComment;
import jadx.api.data.ICodeRename;
import jadx.api.data.IJavaCodeRef;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRef;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import jadx.core.utils.GsonUtils;

import static jadx.core.utils.GsonUtils.interfaceReplace;

/** Reads and writes the subset of the jadx-gui v2 project format needed by jadx-mcp. */
public final class JadxProjectIO {

	private static final int PROJECT_VERSION = 2;

	public static LoadedProject read(Path projectFile) throws IOException {
		Path absoluteProject = projectFile.toAbsolutePath().normalize();
		Path baseDir = absoluteProject.getParent();
		if (baseDir == null) {
			throw new IOException("Project path has no parent: " + projectFile);
		}
		try (Reader reader = Files.newBufferedReader(absoluteProject, StandardCharsets.UTF_8)) {
			JsonElement document = JsonParser.parseReader(reader);
			if (!document.isJsonObject()) {
				throw new IOException("Project root must be a JSON object");
			}
			JsonObject root = document.getAsJsonObject();
			JsonArray files = root.has("files") && root.get("files").isJsonArray()
					? root.getAsJsonArray("files")
					: null;
			if (files == null || files.isEmpty()) {
				throw new IOException("Project does not contain any input files");
			}
			List<Path> inputFiles = new ArrayList<>(files.size());
			for (JsonElement fileElement : files) {
				if (!fileElement.isJsonPrimitive() || !fileElement.getAsJsonPrimitive().isString()) {
					throw new IOException("Project input path must be a string: " + fileElement);
				}
				String value = fileElement.getAsString();
				if (value.isBlank()) {
					throw new IOException("Project input path must not be blank");
				}
				Path path = Path.of(value);
				inputFiles.add((path.isAbsolute() ? path : baseDir.resolve(path)).normalize());
			}
			JadxCodeData codeData = new JadxCodeData();
			if (root.has("codeData") && root.get("codeData").isJsonObject()) {
				JadxCodeData loaded = gson().fromJson(root.get("codeData"), JadxCodeData.class);
				if (loaded != null) {
					codeData = loaded;
				}
			}
			if (codeData.getComments() == null) {
				codeData.setComments(List.of());
			}
			if (codeData.getRenames() == null) {
				codeData.setRenames(List.of());
			}
			return new LoadedProject(inputFiles, codeData);
		} catch (JsonParseException | IllegalArgumentException e) {
			throw new IOException("Invalid jadx project file: " + e.getMessage(), e);
		}
	}

	public static void write(Path output, List<Path> inputs, JadxCodeData codeData, boolean overwrite) throws IOException {
		Path absoluteOutput = output.toAbsolutePath().normalize();
		Path parent = absoluteOutput.getParent();
		if (parent == null) {
			throw new IOException("Output path has no parent: " + output);
		}

		List<String> projectFiles = new ArrayList<>(inputs.size());
		for (Path input : inputs) {
			projectFiles.add(projectPath(parent, input));
		}
		Map<String, Object> project = new LinkedHashMap<>();
		project.put("projectVersion", PROJECT_VERSION);
		project.put("files", projectFiles);
		project.put("codeData", codeData);

		Path temp = Files.createTempFile(parent, "." + absoluteOutput.getFileName() + ".", ".tmp");
		try {
			Files.writeString(temp, gson().toJson(project), StandardCharsets.UTF_8);
			moveIntoPlace(temp, absoluteOutput, overwrite);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static Gson gson() {
		return GsonUtils.defaultGsonBuilder()
				.registerTypeAdapter(ICodeComment.class, interfaceReplace(JadxCodeComment.class))
				.registerTypeAdapter(ICodeRename.class, interfaceReplace(JadxCodeRename.class))
				.registerTypeAdapter(IJavaNodeRef.class, interfaceReplace(JadxNodeRef.class))
				.registerTypeAdapter(IJavaCodeRef.class, interfaceReplace(JadxCodeRef.class))
				.create();
	}

	private static String projectPath(Path projectDir, Path input) {
		Path absoluteInput = input.toAbsolutePath().normalize();
		try {
			return projectDir.relativize(absoluteInput).toString();
		} catch (IllegalArgumentException e) {
			return absoluteInput.toString();
		}
	}

	private static void moveIntoPlace(Path temp, Path output, boolean overwrite) throws IOException {
		try {
			if (overwrite) {
				Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (AtomicMoveNotSupportedException e) {
			if (overwrite) {
				Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.move(temp, output);
			}
		}
	}

	public static final class LoadedProject {
		private final List<Path> inputFiles;
		private final JadxCodeData codeData;

		private LoadedProject(List<Path> inputFiles, JadxCodeData codeData) {
			this.inputFiles = List.copyOf(inputFiles);
			this.codeData = codeData;
		}

		public List<Path> inputFiles() {
			return inputFiles;
		}

		public JadxCodeData codeData() {
			return codeData;
		}
	}

	private JadxProjectIO() {
	}
}
