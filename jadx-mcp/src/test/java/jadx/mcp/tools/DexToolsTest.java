package jadx.mcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.mcp.JadxSession;
import jadx.mcp.script.ScriptRunner;
import jadx.mcp.util.ToolException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DexToolsTest {
	private static final Path APK = Path.of("../jadx-cli/src/test/resources/samples/small.apk")
			.toAbsolutePath().normalize();

	@TempDir
	Path tempDir;
	private final JadxSession session = new JadxSession();

	@AfterEach
	void closeSession() {
		session.close();
	}

	@Test
	void opensRenamedApkAndDecodesXmlLazily() throws Exception {
		Path input = Files.copy(APK, tempDir.resolve("sample.data"));
		Map<String, Object> opened = call(new OpenFileTool(session), Map.of("path", input.toString()));
		assertThat(((Number) opened.get("class_count")).intValue()).isPositive();

		Map<String, Object> resources = call(new ListResourcesTool(session), Map.of());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> entries = (List<Map<String, Object>>) resources.get("resources");
		assertThat(entries).extracting(entry -> entry.get("name"))
				.contains("AndroidManifest.xml", "res/layout/activity_main.xml");
		assertThat(entries).allSatisfy(entry -> assertThat(entry)
				.containsKeys("size_uncompressed", "size_compressed")
				.doesNotContainKey("zip_entry_name"));

		Map<String, Object> xml = call(new DecompileXmlTool(session),
				Map.of("resource_path", "AndroidManifest.xml", "line_numbers", false));
		assertThat((String) xml.get("code")).contains("<manifest");
		Map<String, Object> layout = call(new DecompileXmlTool(session),
				Map.of("resource_path", "res/layout/activity_main.xml", "line_numbers", false));
		assertThatThrownBy(() -> new DecompileXmlTool(session).call(Map.of("resource_path", "activity_main.xml")))
				.isInstanceOfSatisfying(ToolException.class, error ->
						assertThat(error.code()).isEqualTo(ToolException.Code.NOT_FOUND));

		Map<String, Object> search = call(new SearchResourceTool(session), Map.of("query", "manifest"));
		assertThat(((Number) search.get("count")).intValue()).isPositive();
		assertThat((String) layout.get("code")).contains("TextView");
	}

	@Test
	void browsesAndDecompilesDex() {
		call(new OpenFileTool(session), Map.of("path", APK.toString()));
		Map<String, Object> listed = call(new ListClassesTool(session),
				Map.of("max_depth", 0, "include_inner", true));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> classes = (List<Map<String, Object>>) listed.get("classes");
		String fqn = (String) classes.get(0).get("fqn");

		Map<String, Object> members = call(new ListClassMembersTool(session), Map.of("class_fqn", fqn));
		assertThat(members).containsEntry("class_fqn", fqn);
		Map<String, Object> code = call(new DecompileCodeTool(session),
				Map.of("target", fqn, "annotate", "off"));
		assertThat((String) code.get("code")).contains("class");
		Map<String, Object> smali = call(new DisassembleTool(session), Map.of("target", fqn));
		assertThat((String) smali.get("code")).contains(".class");
		Map<String, Object> symbols = call(new SearchSymbolTool(session),
				Map.of("kind", "class", "query", "MainActivity"));
		assertThat(((Number) symbols.get("count")).intValue()).isPositive();
		Map<String, Object> strings = call(new SearchStringsTool(session), Map.of("query", "Hello"));
		assertThat(((Number) strings.get("count")).intValue()).isPositive();
		Map<String, Object> smaliHits = call(new SearchCodeTool(session),
				Map.of("query", "onCreate"));
		assertThat(((Number) smaliHits.get("count")).intValue()).isPositive();
		String inner = "io.github.skylot.android.smallapp.R$layout";
		Map<String, Object> innerHits = call(new SearchCodeTool(session), Map.of("query", ".class", "package_prefix", inner));
		assertThat(innerHits).containsEntry("count", 1);
		Map<String, Object> allMethods = call(new SearchCodeTool(session),
				Map.of("query", ".method", "max_results", 1000));
		Map<String, Object> page = call(new SearchCodeTool(session),
				Map.of("query", ".method", "max_results", 1));
		Map<String, Object> nextPage = call(new SearchCodeTool(session), Map.of(
				"query", ".method", "max_results", 1,
				"start_class_fqn", page.get("next_class_fqn"), "offset", page.get("next_offset")));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> allMethodHits = (List<Map<String, Object>>) allMethods.get("hits");
		assertThat(nextPage.get("hits")).isEqualTo(allMethodHits.subList(1, 2));
		Map<String, Object> fields = call(new SearchSymbolTool(session),
				Map.of("kind", "field", "query", "activity_main"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> fieldHits = (List<Map<String, Object>>) fields.get("hits");
		Map<String, Object> xrefs = call(new XrefsToTool(session), Map.of(
				"target_fqn", fieldHits.get(0).get("target"), "kind", "field"));
		assertThat(((Number) xrefs.get("total")).intValue()).isPositive();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> uses = (List<Map<String, Object>>) xrefs.get("uses");
		assertThat(uses.get(0)).containsKey("insn_offsets").containsKey("op_locations");
	}

	@Test
	void findsReferencesToExternalSymbolsInRenamedDex() throws Exception {
		Path dex = Files.copy(APK.resolveSibling("hello.dex"), tempDir.resolve("misnamed.apk"));
		Map<String, Object> opened = call(new OpenFileTool(session), Map.of("path", dex.toString()));
		assertThat(opened).containsEntry("class_count", 1);
		XrefsToTool tool = new XrefsToTool(session);
		String method = "java.io.PrintStream#println";
		Map<String, Object> methodRefs = call(tool, Map.of(
				"kind", "method", "target_fqn", method + "(Ljava/lang/String;)V"));
		assertThat(methodRefs).containsEntry("total", 1).doesNotContainKey("total_app");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> uses = (List<Map<String, Object>>) methodRefs.get("uses");
		assertThat(uses).singleElement().satisfies(use -> assertThat(use)
				.containsEntry("in_class", "HelloWorld")
				.containsEntry("in_method", "main")
				.containsKey("insn_offsets"));
		assertThat(call(tool, Map.of("kind", "method", "target_fqn", method + "(I)V")))
				.containsEntry("total", 0);
		assertThatThrownBy(() -> tool.call(Map.of("kind", "method", "target_fqn", method)))
				.isInstanceOfSatisfying(ToolException.class, error -> {
					assertThat(error.code()).isEqualTo(ToolException.Code.INVALID_ARG);
					assertThat(error.getMessage()).contains("target_fqn", "JVM descriptor");
				});

		assertThat(call(tool, Map.of("kind", "class", "target_fqn", "java.lang.System")))
				.containsEntry("total", 1);
		assertThat(call(tool, Map.of("kind", "field", "target_fqn", "java.lang.System#out:Ljava/io/PrintStream;")))
				.containsEntry("total", 1)
				.containsEntry("op_summary", Map.of(
						"read_count", 1, "write_count", 0, "init_count", 0));
	}

	@Test
	void scriptsRequireAndReceiveExplicitClassScope() {
		call(new OpenFileTool(session), Map.of("path", APK.toString()));
		String fqn = firstClass();
		List<String> visible = new ArrayList<>();
		ScriptRunner runner = (decompiler, scriptPath) -> {
			decompiler.getClassesWithInners().forEach(cls -> visible.add(cls.getRawName()));
			return new ScriptRunner.ScriptRunResult(true, "scope", 1, 0, List.of(), null);
		};
		RunScriptTool script = new RunScriptTool(session, runner);
		assertThatThrownBy(() -> script.call(Map.of("script_text", "println(1)")))
				.isInstanceOf(ToolException.class)
				.extracting(error -> ((ToolException) error).code())
				.isEqualTo(ToolException.Code.INVALID_ARG);
		call(script, Map.of("script_text", "println(1)", "class_fqns", List.of(fqn)));
		assertThat(visible).contains(fqn);
	}

	@Test
	void largeApkKeepsCrossDexReferences() {
		String path = System.getenv("JADX_MCP_LARGE_APK");
		Assumptions.assumeTrue(path != null && !path.isBlank());
		long started = System.nanoTime();
		Map<String, Object> opened = call(new OpenFileTool(session), Map.of("path", path));
		long openMs = (System.nanoTime() - started) / 1_000_000;
		assertThat(((Number) opened.get("class_count")).intValue()).isGreaterThan(200_000);

		String fqn = "com.tencent.mm.ui.LauncherUI";
		started = System.nanoTime();
		Map<String, Object> code = call(new DecompileCodeTool(session),
				Map.of("target", fqn, "annotate", "off"));
		long codeMs = (System.nanoTime() - started) / 1_000_000;
		assertThat((String) code.get("code")).contains("class LauncherUI");
		Map<String, Object> annotated = call(new DecompileCodeTool(session), Map.of("target", fqn));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> refs = (List<Map<String, Object>>) annotated.get("refs");
		assertThat(refs).isNotEmpty();
		Map<String, Object> resolved = call(new ResolveRefTool(session), Map.of(
				"class_fqn", fqn, "ref_id", refs.get(0).get("ref_id")));
		assertThat(resolved).containsKey("target_fqn").doesNotContainKey("uses_preview");

		started = System.nanoTime();
		Map<String, Object> xrefs = call(new XrefsToTool(session), Map.of(
				"target_fqn", fqn + "#getInstance()Lcom/tencent/mm/ui/LauncherUI;",
				"kind", "method"));
		long xrefsMs = (System.nanoTime() - started) / 1_000_000;
		assertThat(((Number) xrefs.get("total")).intValue()).isEqualTo(78);
		String targetDex = session.read(project -> project.find(fqn).reader().getInputFileName());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> uses = (List<Map<String, Object>>) xrefs.get("uses");
		assertThat(uses).anySatisfy(use -> {
			String caller = (String) use.get("in_class");
			String callerDex = session.read(project -> project.find(caller).reader().getInputFileName());
			assertThat(callerDex).isNotEqualTo(targetDex);
		});
		Map<String, Object> manifest = call(new DecompileXmlTool(session),
				Map.of("resource_path", "AndroidManifest.xml", "line_numbers", false));
		assertThat((String) manifest.get("code")).contains("<manifest");
		System.err.println("DEX benchmark open=" + openMs + "ms code=" + codeMs
				+ "ms xrefs=" + xrefsMs + "ms");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> call(AbstractTool tool, Map<String, Object> args) {
		try {
			return (Map<String, Object>) tool.call(args);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private String firstClass() {
		Map<String, Object> listed = call(new ListClassesTool(session), Map.of("max_depth", 0));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> classes = (List<Map<String, Object>>) listed.get("classes");
		return (String) classes.get(0).get("fqn");
	}
}
