package jadx.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

import jadx.mcp.tools.CloseFileTool;
import jadx.mcp.tools.CurrentProjectTool;
import jadx.mcp.tools.DecompileCodeTool;
import jadx.mcp.tools.DecompileXmlTool;
import jadx.mcp.tools.DisassembleTool;
import jadx.mcp.tools.InheritanceTreeTool;
import jadx.mcp.tools.ListClassMembersTool;
import jadx.mcp.tools.ListClassesTool;
import jadx.mcp.tools.ListResourcesTool;
import jadx.mcp.tools.MethodOverridesTool;
import jadx.mcp.tools.OpenFileTool;
import jadx.mcp.tools.RenameTool;
import jadx.mcp.tools.ResolveRefTool;
import jadx.mcp.tools.RunScriptTool;
import jadx.mcp.tools.SaveProjectTool;
import jadx.mcp.tools.SearchCodeTool;
import jadx.mcp.tools.SearchResourceTool;
import jadx.mcp.tools.SearchStringsTool;
import jadx.mcp.tools.SearchSymbolTool;
import jadx.mcp.tools.XrefsToTool;

/**
 * MCP server entry point. Exposes a fixed set of jadx tools over stdio or Streamable HTTP.
 * <p>
 * Logging is sent to stderr (logback default), so stdio mode keeps stdout reserved for JSON-RPC.
 */
public final class JadxMcpServer {

	private static final Logger LOG = LoggerFactory.getLogger(JadxMcpServer.class);
	private static final String SERVER_NAME = "jadx-mcp";
	private static final String SERVER_VERSION = "0.1.0";

	public static void main(String[] argv) {
		JadxMcpArgs args = JadxMcpArgs.parse(argv);
		if (args == null) {
			System.exit(1);
			return;
		}

		JadxSession session = new JadxSession();
		LOG.info("No project loaded at startup; waiting for `open_file` from MCP client.");
		if (args.getTransport() == JadxMcpArgs.Transport.STREAMABLE_HTTP) {
			runStreamableHttp(args, session);
		} else {
			runStdio(session);
		}
	}

	private static void runStdio(JadxSession session) {
		StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
		McpSyncServer server = McpServer.sync(transport)
				.serverInfo(SERVER_NAME, SERVER_VERSION)
				.capabilities(ServerCapabilities.builder().tools(true).build())
				.build();

		registerTools(server, session);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOG.info("Shutting down jadx-mcp");
			try {
				server.closeGracefully();
			} catch (Throwable t) {
				LOG.warn("Failed to close MCP server", t);
			}
			session.close();
		}, "jadx-mcp-shutdown"));

		LOG.info("jadx-mcp ready");
		// stdio transport keeps the process alive via its reactor; just block here so shutdown hook runs on
		// EOF/SIGTERM.
		try {
			Thread.currentThread().join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void runStreamableHttp(JadxMcpArgs args, JadxSession session) {
		JadxMcpHttpServer httpServer = new JadxMcpHttpServer(args, session);
		Thread shutdownHook = new Thread(() -> {
			LOG.info("Shutting down jadx-mcp");
			httpServer.close();
			session.close();
		}, "jadx-mcp-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		try (httpServer) {
			httpServer.start();
			LOG.info("jadx-mcp Streamable HTTP endpoint ready at {}", httpServer.getEndpointUri());
			httpServer.await();
		} catch (Exception e) {
			LOG.error("Streamable HTTP server failed", e);
			System.exit(1);
		} finally {
			session.close();
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				// JVM shutdown is already in progress.
			}
		}
	}

	static McpSyncServer createStreamableServer(
			McpStreamableServerTransportProvider transport, JadxSession session) {
		McpSyncServer server = McpServer.sync(transport)
				.serverInfo(SERVER_NAME, SERVER_VERSION)
				.capabilities(ServerCapabilities.builder().tools(true).build())
				.build();
		registerTools(server, session);
		return server;
	}

	private static void registerTools(McpSyncServer server, JadxSession session) {
		// session lifecycle
		server.addTool(new OpenFileTool(session).spec());
		server.addTool(new CloseFileTool(session).spec());
		server.addTool(new CurrentProjectTool(session).spec());
		server.addTool(new SaveProjectTool(session).spec());
		server.addTool(new RunScriptTool(session).spec());
		// reverse-engineering tools
		server.addTool(new DecompileCodeTool(session).spec());
		server.addTool(new DecompileXmlTool(session).spec());
		server.addTool(new DisassembleTool(session).spec());
		server.addTool(new SearchSymbolTool(session).spec());
		server.addTool(new SearchStringsTool(session).spec());
		server.addTool(new SearchCodeTool(session).spec());
		server.addTool(new SearchResourceTool(session).spec());
		server.addTool(new XrefsToTool(session).spec());
		server.addTool(new MethodOverridesTool(session).spec());
		server.addTool(new ResolveRefTool(session).spec());
		server.addTool(new RenameTool(session).spec());
		// browse / navigate / analyze tools
		server.addTool(new ListClassMembersTool(session).spec());
		server.addTool(new ListClassesTool(session).spec());
		server.addTool(new ListResourcesTool(session).spec());
		server.addTool(new InheritanceTreeTool(session).spec());
	}

	private JadxMcpServer() {
	}
}
