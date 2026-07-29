package jadx.mcp;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.Implementation;

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
						.contains("open_file", "current_project", "decompile_code", "run_script");
			}
		} finally {
			session.close();
		}
	}

	private static String baseUri(URI endpoint) {
		return endpoint.getScheme() + "://" + endpoint.getHost() + ":" + endpoint.getPort();
	}
}
