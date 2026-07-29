package jadx.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

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
 * MCP server entry point. Exposes a fixed set of jadx tools over stdio JSON-RPC.
 * <p>
 * Logging is sent to stderr (logback default) so it never interferes with the JSON-RPC channel on
 * stdout.
 */
public final class JadxMcpServer {

	private static final Logger LOG = LoggerFactory.getLogger(JadxMcpServer.class);

	public static void main(String[] argv) {
		if (JadxMcpArgs.parse(argv) == null) {
			System.exit(1);
			return;
		}

		JadxSession session = new JadxSession();
		LOG.info("No project loaded at startup; waiting for `open_file` from MCP client.");

		StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
		McpSyncServer server = McpServer.sync(transport)
				.serverInfo("jadx-mcp", "0.1.0")
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
