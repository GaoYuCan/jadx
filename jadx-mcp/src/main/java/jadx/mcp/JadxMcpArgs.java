package jadx.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

/**
 * CLI arguments for {@link JadxMcpServer}.
 * <p>
 * The server always starts with no project loaded and waits for an {@code open_file} tool call from the client.
 */
public class JadxMcpArgs {

	@Parameter(names = { "-h", "--help" }, help = true, description = "print this help")
	private boolean help = false;

	@Parameter(names = "--transport", description = "transport: stdio or streamable-http")
	private String transport = "stdio";

	@Parameter(names = "--http-host", description = "Streamable HTTP bind host")
	private String httpHost = "127.0.0.1";

	@Parameter(names = "--http-port", description = "Streamable HTTP bind port (0 selects an ephemeral port)")
	private int httpPort = 8080;

	@Parameter(names = "--http-path", description = "Streamable HTTP MCP endpoint path")
	private String httpPath = "/mcp";

	@Parameter(names = "--http-allowed-host", description = "additional allowed HTTP Host value (repeatable)")
	private List<String> httpAllowedHosts = new ArrayList<>();

	@Parameter(names = "--http-allowed-origin", description = "additional allowed HTTP Origin value (repeatable)")
	private List<String> httpAllowedOrigins = new ArrayList<>();

	public static JadxMcpArgs parse(String[] argv) {
		JadxMcpArgs args = new JadxMcpArgs();
		JCommander jc = JCommander.newBuilder().addObject(args).programName("jadx-mcp").build();
		try {
			jc.parse(argv);
			if (args.help) {
				jc.usage();
				return null;
			}
			args.validate();
		} catch (ParameterException e) {
			System.err.println("Argument error: " + e.getMessage());
			jc.usage();
			return null;
		}
		return args;
	}

	private void validate() {
		String transportName = transport.toLowerCase(Locale.ROOT);
		if (!transportName.equals("stdio") && !transportName.equals("streamable-http")) {
			throw new ParameterException("--transport must be `stdio` or `streamable-http`");
		}
		transport = transportName;
		if (httpHost == null || httpHost.isBlank()) {
			throw new ParameterException("--http-host must not be blank");
		}
		httpHost = httpHost.trim();
		if (httpPort < 0 || httpPort > 65535) {
			throw new ParameterException("--http-port must be between 0 and 65535");
		}
		if (httpPath == null
				|| httpPath.length() < 2
				|| httpPath.charAt(0) != '/'
				|| httpPath.endsWith("/")
				|| httpPath.indexOf('?') != -1
				|| httpPath.indexOf('#') != -1
				|| httpPath.indexOf('*') != -1
				|| httpPath.chars().anyMatch(Character::isWhitespace)) {
			throw new ParameterException("--http-path must be an absolute endpoint path such as `/mcp`");
		}
		validateValues(httpAllowedHosts, "--http-allowed-host");
		validateValues(httpAllowedOrigins, "--http-allowed-origin");
		if (getTransport() == Transport.STREAMABLE_HTTP
				&& isWildcardAddress(httpHost)
				&& httpAllowedHosts.isEmpty()) {
			throw new ParameterException(
					"--http-allowed-host is required when --http-host listens on all interfaces");
		}
	}

	private static void validateValues(List<String> values, String option) {
		for (int i = 0; i < values.size(); i++) {
			String value = values.get(i);
			if (value == null || value.isBlank()) {
				throw new ParameterException(option + " must not be blank");
			}
			values.set(i, value.trim());
		}
	}

	private static boolean isWildcardAddress(String host) {
		return host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]");
	}

	public Transport getTransport() {
		return transport.equals("streamable-http") ? Transport.STREAMABLE_HTTP : Transport.STDIO;
	}

	public String getHttpHost() {
		return httpHost;
	}

	public int getHttpPort() {
		return httpPort;
	}

	public String getHttpPath() {
		return httpPath;
	}

	public List<String> getHttpAllowedHosts() {
		return List.copyOf(httpAllowedHosts);
	}

	public List<String> getHttpAllowedOrigins() {
		return List.copyOf(httpAllowedOrigins);
	}

	public enum Transport {
		STDIO,
		STREAMABLE_HTTP
	}
}
