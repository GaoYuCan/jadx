package jadx.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JadxMcpArgsTest {

	@Test
	void defaultsToStdio() {
		JadxMcpArgs args = JadxMcpArgs.parse(new String[0]);

		assertThat(args).isNotNull();
		assertThat(args.getTransport()).isEqualTo(JadxMcpArgs.Transport.STDIO);
		assertThat(args.getHttpHost()).isEqualTo("127.0.0.1");
		assertThat(args.getHttpPort()).isEqualTo(8080);
		assertThat(args.getHttpPath()).isEqualTo("/mcp");
	}

	@Test
	void parsesStreamableHttpOptions() {
		JadxMcpArgs args = JadxMcpArgs.parse(new String[] {
				"--transport", "streamable-http",
				"--http-host", "0.0.0.0",
				"--http-port", "9000",
				"--http-path", "/custom-mcp",
				"--http-allowed-host", "mcp.example.com:*",
				"--http-allowed-origin", "https://client.example.com"
		});

		assertThat(args).isNotNull();
		assertThat(args.getTransport()).isEqualTo(JadxMcpArgs.Transport.STREAMABLE_HTTP);
		assertThat(args.getHttpHost()).isEqualTo("0.0.0.0");
		assertThat(args.getHttpPort()).isEqualTo(9000);
		assertThat(args.getHttpPath()).isEqualTo("/custom-mcp");
		assertThat(args.getHttpAllowedHosts()).containsExactly("mcp.example.com:*");
		assertThat(args.getHttpAllowedOrigins()).containsExactly("https://client.example.com");
	}

	@Test
	void rejectsInvalidHttpSettings() {
		assertThat(JadxMcpArgs.parse(new String[] { "--transport", "http" })).isNull();
		assertThat(JadxMcpArgs.parse(new String[] { "--http-port", "65536" })).isNull();
		assertThat(JadxMcpArgs.parse(new String[] { "--http-path", "mcp" })).isNull();
		assertThat(JadxMcpArgs.parse(new String[] {
				"--transport", "streamable-http",
				"--http-host", "0.0.0.0"
		})).isNull();
	}
}
