#!/usr/bin/env node

/**
 * HiSi MCP Server
 * Model Context Protocol Server for HiSi Dev Tool
 *
 * Provides tools for:
 * - Knowledge Graph operations (code analysis, dependency tracking)
 * - Vector Search (semantic code search)
 * - Log Query and Analysis
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  ErrorCode,
  McpError,
} from '@modelcontextprotocol/sdk/types.js';

import { allToolDefinitions, handleToolCall } from './tools/index.js';
import { getApiClient } from './client/apiClient.js';

// Server configuration
const SERVER_NAME = 'hisi-mcp-server';
const SERVER_VERSION = '1.0.0';

// Environment configuration
const API_BASE_URL = process.env.HISI_API_URL || 'http://localhost:8080';
const DEBUG = process.env.HISI_DEBUG === 'true';

/**
 * Create and configure the MCP server
 */
function createServer(): Server {
  const server = new Server(
    { name: SERVER_NAME, version: SERVER_VERSION },
    {
      capabilities: {
        tools: {},
      },
    }
  );

  // Initialize API client
  const apiClient = getApiClient(API_BASE_URL);

  // Handle ListTools request
  server.setRequestHandler(ListToolsRequestSchema, async () => {
    if (DEBUG) {
      console.error('[DEBUG] ListTools request received');
    }

    return {
      tools: allToolDefinitions.map((tool) => ({
        name: tool.name,
        description: tool.description,
        inputSchema: tool.inputSchema,
      })),
    };
  });

  // Handle CallTool request
  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args } = request.params;

    if (DEBUG) {
      console.error(`[DEBUG] CallTool request: ${name}`, JSON.stringify(args, null, 2));
    }

    try {
      // Validate tool name
      const toolExists = allToolDefinitions.some((tool) => tool.name === name);
      if (!toolExists) {
        throw new McpError(ErrorCode.MethodNotFound, `Unknown tool: ${name}`);
      }

      // Execute tool
      const result = await handleToolCall(name, args || {});

      if (DEBUG) {
        console.error(`[DEBUG] Tool result:`, JSON.stringify(result, null, 2));
      }

      // Format response
      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(result, null, 2),
          },
        ],
      };
    } catch (error) {
      // Handle errors
      if (error instanceof McpError) {
        throw error;
      }

      const errorMessage = error instanceof Error ? error.message : 'Unknown error occurred';

      if (DEBUG) {
        console.error(`[DEBUG] Error: ${errorMessage}`, error);
      }

      // Return error as tool result (not as MCP error)
      // This allows the LLM to see and potentially handle the error
      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify({
              success: false,
              error: errorMessage,
              tool: name,
            }, null, 2),
          },
        ],
        isError: true,
      };
    }
  });

  return server;
}

/**
 * Main entry point
 */
async function main(): Promise<void> {
  if (DEBUG) {
    console.error(`[DEBUG] Starting ${SERVER_NAME} v${SERVER_VERSION}`);
    console.error(`[DEBUG] API Base URL: ${API_BASE_URL}`);
  }

  const server = createServer();
  const transport = new StdioServerTransport();

  try {
    await server.connect(transport);

    if (DEBUG) {
      console.error(`[DEBUG] ${SERVER_NAME} connected and running`);
    }
  } catch (error) {
    console.error(`Failed to start server:`, error);
    process.exit(1);
  }
}

// Handle graceful shutdown
process.on('SIGINT', () => {
  if (DEBUG) {
    console.error('[DEBUG] Received SIGINT, shutting down...');
  }
  process.exit(0);
});

process.on('SIGTERM', () => {
  if (DEBUG) {
    console.error('[DEBUG] Received SIGTERM, shutting down...');
  }
  process.exit(0);
});

// Handle uncaught errors
process.on('uncaughtException', (error) => {
  console.error('Uncaught exception:', error);
  process.exit(1);
});

process.on('unhandledRejection', (reason) => {
  console.error('Unhandled rejection:', reason);
  process.exit(1);
});

// Start the server
main().catch((error) => {
  console.error('Server failed to start:', error);
  process.exit(1);
});
