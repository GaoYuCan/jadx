package jadx.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import static org.assertj.core.api.Assertions.assertThat;

class JadxMcpHttpServerTest {

	@Test
	void servesToolsOverStreamableHttp() throws Exception {
		JadxMcpArgs args = JadxMcpArgs.parse(new String[] {
				"--transport", "streamable-http",
				"--http-port", "0"
		});
		assertThat(args).isNotNull();

		JadxSession session = new JadxSession();
		try (JadxMcpHttpServer server = new JadxMcpHttpServer(args, session)) {
			server.start();
			URI endpoint = server.getEndpointUri();
			assertThat(endpoint.getPath()).isEqualTo("/mcp");
			assertThat(endpoint.getPort()).isPositive();

			HttpClientStreamableHttpTransport transport =
					HttpClientStreamableHttpTransport.builder(baseUri(endpoint))
							.endpoint(endpoint.getPath())
							.connectTimeout(Duration.ofSeconds(5))
							.build();
			try (McpSyncClient client = McpClient.sync(transport)
					.clientInfo(new Implementation("jadx-mcp-test", "1.0"))
					.initializationTimeout(Duration.ofSeconds(5))
					.requestTimeout(Duration.ofSeconds(5))
					.build()) {
				client.initialize();
				assertThat(client.listTools().tools())
						.extracting(tool -> tool.name())
						.hasSize(16)
						.doesNotContain("rename", "save_project", "inheritance_tree", "method_overrides")
						.contains("open_file", "current_project", "decompile_code", "run_script");

				checkToolArguments(client);
			}
		} finally {
			session.close();
		}
	}

	private static void checkToolArguments(McpSyncClient client) {
		assertInvalidArg(client, "open_file", Map.of("path", "sample.apk", "mode", "fast"), "mode");
		assertInvalidArg(client, "search_code", Map.of("query", ".method", "target", "smali"), "target");
		assertInvalidArg(client, "resolve_ref", Map.of("class_fqn", "HelloWorld", "ref_id", "R1", "max_uses", 10), "max_uses");
		assertInvalidArg(client, "xrefs_to", Map.of(
				"kind", "method", "target_fqn", "java.io.PrintStream#println", "descriptor", "(I)V"), "descriptor");
		assertInvalidArg(client, "search_symbol", Map.of(
				"kind", "class", "filter", Map.of("include_raw_names", true)), "filter.include_raw_names");
		assertInvalidArg(client, "search_symbol", Map.of(
				"kind", "class", "filter", Map.of("access", "public,static")), "filter.access");
		assertInvalidArg(client, "search_symbol", Map.of(
				"kind", "class", "filter", Map.of("superclass_transitive", "true")), "filter.superclass_transitive");
		assertInvalidArg(client, "run_script", Map.of("script_text", "println(1)", "class_fqns", "HelloWorld"), "class_fqns");
		assertInvalidArg(client, "search_code", Map.of("query", ".method", "regex", "false"), "regex");
		assertInvalidArg(client, "search_code", Map.of("query", ".method", "max_results", "1"), "max_results");
		assertInvalidArg(client, "search_code", Map.of("query", ".method", "max_results", 1.5), "max_results");
		assertInvalidArg(client, "search_code", Map.of("query", ".method", "max_results", 4_294_967_297L), "max_results");

		Path dex = Path.of("../jadx-cli/src/test/resources/samples/hello.dex").toAbsolutePath().normalize();
		assertThat(client.callTool(new CallToolRequest("open_file", Map.of("path", dex.toString()))).isError()).isFalse();
		CallToolResult search = client.callTool(new CallToolRequest("search_code",
				Map.of("query", ".method", "regex", false, "max_results", 1)));
		assertThat(search.isError()).isFalse();
		assertThat(resultText(search)).contains("\"snippet\"").doesNotContain("\"source\"");
	}

	private static void assertInvalidArg(McpSyncClient client, String tool, Map<String, Object> args, String arg) {
		CallToolResult result = client.callTool(new CallToolRequest(tool, args));
		assertThat(result.isError()).isTrue();
		assertThat(resultText(result)).contains("INVALID_ARG", "Invalid argument '" + arg + "'");
	}

	private static String resultText(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	private static String baseUri(URI endpoint) {
		return endpoint.getScheme() + "://" + endpoint.getHost() + ":" + endpoint.getPort();
	}
}
