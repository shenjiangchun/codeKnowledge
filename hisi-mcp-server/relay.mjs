#!/usr/bin/env node
/**
 * MCP stdio → HTTP JSON-RPC relay.
 *
 * Claude Code launches this as an MCP subprocess.
 * It forwards all traffic to/from the remote hisi-mcp-server over HTTP.
 */

const MCP_URL = process.env.MCP_REMOTE_URL || 'http://localhost:3100/mcp';
process.stderr.write(`[hisi-relay] forwarding to ${MCP_URL}\n`);

async function forward(jsonStr) {
  try {
    const res = await fetch(MCP_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: jsonStr,
    });
    const out = JSON.stringify(await res.json()) + '\n';
    process.stdout.write(out);
  } catch (e) {
    process.stderr.write(`[hisi-relay] error: ${e?.message || e}\n`);
    process.stdout.write(JSON.stringify({
      jsonrpc: '2.0',
      error: { code: -32603, message: e?.message || 'relay error' },
      id: null,
    }) + '\n');
  }
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buf += chunk;
  // MCP stdio: newline-delimited JSON-RPC
  while (true) {
    const i = buf.indexOf('\n');
    if (i === -1) break;
    const msg = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (msg) forward(msg);
  }
});
