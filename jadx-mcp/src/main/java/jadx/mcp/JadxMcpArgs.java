package jadx.mcp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI arguments for {@link JadxMcpServer}.
 * <p>
 * The server always starts with no project loaded and waits for an {@code open_file} tool call from the client.
 */
public class JadxMcpArgs {

	private static final Logger LOG = LoggerFactory.getLogger(JadxMcpArgs.class);

	/** Env var name for default aux inputs. Same {@link File#pathSeparator} convention as {@code $CLASSPATH}. */
	public static final String AUX_INPUTS_ENV = "JADX_MCP_AUX_INPUTS";

	@Parameter(names = { "-h", "--help" }, help = true, description = "print this help")
	private boolean help = false;

	public static JadxMcpArgs parse(String[] argv) {
		JadxMcpArgs args = new JadxMcpArgs();
		JCommander jc = JCommander.newBuilder().addObject(args).programName("jadx-mcp").build();
		try {
			jc.parse(argv);
		} catch (ParameterException e) {
			System.err.println("Argument error: " + e.getMessage());
			jc.usage();
			return null;
		}
		if (args.help) {
			jc.usage();
			return null;
		}
		return args;
	}

	/**
	 * Read {@value #AUX_INPUTS_ENV} and split on the platform path separator (`:` on macOS/Linux,
	 * `;` on Windows — same convention as {@code $CLASSPATH}).
	 */
	public static List<File> resolveEnvAuxInputs() {
		String env = System.getenv(AUX_INPUTS_ENV);
		if (env == null || env.isBlank()) {
			return Collections.emptyList();
		}
		List<String> parts = new ArrayList<>();
		for (String part : env.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
			if (!part.isBlank()) {
				parts.add(part);
			}
		}
		return resolvePaths(parts, AUX_INPUTS_ENV);
	}

	/**
	 * Validate, dedup, and resolve raw aux-input strings to existing {@link File}s. Missing entries are
	 * dropped with a warning so the server still starts; an all-missing list returns {@link Collections#emptyList()}.
	 */
	public static List<File> resolvePaths(List<String> paths, String source) {
		List<File> out = new ArrayList<>(paths.size());
		java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
		for (String raw : paths) {
			if (raw == null) {
				continue;
			}
			String trimmed = raw.trim();
			if (trimmed.isEmpty() || !seen.add(trimmed)) {
				continue;
			}
			File f = new File(trimmed);
			if (!f.exists()) {
				LOG.warn("aux input from {} does not exist, dropping: {}", source, trimmed);
				continue;
			}
			if (!f.isFile()) {
				LOG.warn("aux input from {} is not a regular file, dropping: {}", source, trimmed);
				continue;
			}
			out.add(f);
		}
		return out;
	}
}
