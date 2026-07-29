package jadx.mcp;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

final class JadxMcpHttpServer implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(JadxMcpHttpServer.class);

	private final JadxMcpArgs args;
	private final Server httpServer;
	private final ServerConnector connector;
	private final McpSyncServer mcpServer;
	private final AtomicBoolean closed = new AtomicBoolean();

	JadxMcpHttpServer(JadxMcpArgs args, JadxSession session) {
		this.args = args;

		HttpServletStreamableServerTransportProvider transport =
				HttpServletStreamableServerTransportProvider.builder()
						.jsonMapper(McpJsonDefaults.getMapper())
						.mcpEndpoint(args.getHttpPath())
						.securityValidator(buildSecurityValidator(args))
						.build();
		this.mcpServer = JadxMcpServer.createStreamableServer(transport, session);

		this.httpServer = new Server();
		this.httpServer.setStopTimeout(5_000);
		this.connector = new ServerConnector(httpServer);
		this.connector.setHost(args.getHttpHost());
		this.connector.setPort(args.getHttpPort());
		this.httpServer.addConnector(connector);

		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		ServletHolder holder = new ServletHolder(transport);
		holder.setAsyncSupported(true);
		context.addServlet(holder, args.getHttpPath());
		this.httpServer.setHandler(context);
	}

	void start() throws Exception {
		httpServer.start();
	}

	void await() throws InterruptedException {
		httpServer.join();
	}

	URI getEndpointUri() {
		return URI.create("http://" + formatUriHost(args.getHttpHost()) + ":" + connector.getLocalPort()
				+ args.getHttpPath());
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			mcpServer.closeGracefully();
		} catch (Throwable t) {
			LOG.warn("Failed to close MCP Streamable HTTP server", t);
		}
		try {
			httpServer.stop();
		} catch (Exception e) {
			LOG.warn("Failed to stop HTTP server", e);
		}
	}

	private static ServerTransportSecurityValidator buildSecurityValidator(JadxMcpArgs args) {
		Set<String> allowedHosts = new LinkedHashSet<>(args.getHttpAllowedHosts());
		Set<String> allowedOrigins = new LinkedHashSet<>(args.getHttpAllowedOrigins());
		addBindAddressRules(args.getHttpHost(), allowedHosts, allowedOrigins);

		DefaultServerTransportSecurityValidator.Builder builder =
				DefaultServerTransportSecurityValidator.builder()
						.allowedHosts(allowedHosts.stream().toList())
						.allowedOrigins(allowedOrigins.stream().toList());
		return builder.build();
	}

	private static void addBindAddressRules(
			String host, Set<String> allowedHosts, Set<String> allowedOrigins) {
		if (isWildcardAddress(host)) {
			return;
		}
		addHostAndOrigin(host, allowedHosts, allowedOrigins);
		if (isLoopbackAddress(host)) {
			addHostAndOrigin("127.0.0.1", allowedHosts, allowedOrigins);
			addHostAndOrigin("localhost", allowedHosts, allowedOrigins);
			addHostAndOrigin("::1", allowedHosts, allowedOrigins);
		}
	}

	private static void addHostAndOrigin(
			String host, Set<String> allowedHosts, Set<String> allowedOrigins) {
		String uriHost = formatUriHost(host);
		allowedHosts.add(uriHost + ":*");
		allowedOrigins.add("http://" + uriHost + ":*");
	}

	private static boolean isWildcardAddress(String host) {
		return host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]");
	}

	private static boolean isLoopbackAddress(String host) {
		return host.equalsIgnoreCase("localhost")
				|| host.equals("127.0.0.1")
				|| host.equals("::1")
				|| host.equals("[::1]");
	}

	private static String formatUriHost(String host) {
		if (host.startsWith("[") && host.endsWith("]")) {
			return host;
		}
		return host.indexOf(':') == -1 ? host : "[" + host + "]";
	}
}
