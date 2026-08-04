#!/usr/bin/env node
// CodeAI auto-generated hook: post-tool-use.js
const fs = require('fs');
const path = require('path');
const os = require('os');
const inputData = (() => { try { return fs.readFileSync(0, 'utf8'); } catch { return ''; } })();
let input = null;
try { input = JSON.parse(inputData || '{}'); } catch { process.exit(0); }
const toolName = input?.tool_name;
const toolInput = input?.tool_input || {};
if (!['Write', 'Edit', 'NotebookEdit'].includes(toolName)) process.exit(0);
const filePath = (toolInput.file_path || toolInput.notebook_path || '').replace(/\\/g, '/');
if (!filePath) process.exit(0);
if ([/\/\.claude\//, /\/\.git\//, /\/node_modules\//, /\/dist\//, /\/build\//].some((p) => p.test(filePath))) process.exit(0);
const sanitize = (s) => (s || 'unknown').replace(/[^a-zA-Z0-9_-]/g, '_').toLowerCase().slice(0, 30);
const detectModel = () => {
  const tierMap = { sonnet: 'ANTHROPIC_DEFAULT_SONNET_MODEL', opus: 'ANTHROPIC_DEFAULT_OPUS_MODEL', haiku: 'ANTHROPIC_DEFAULT_HAIKU_MODEL' };
  const settingsPaths = [path.join(process.cwd(), '.claude', 'settings.json'), path.join(process.cwd(), '.claude', 'settings.local.json'), path.join(os.homedir(), '.claude', 'settings.json')];
  let tier = null;
  for (const sp of settingsPaths) { try { const cfg = JSON.parse(fs.readFileSync(sp, 'utf8')); if (cfg?.model && ['sonnet', 'opus', 'haiku'].includes(cfg.model)) { tier = cfg.model; break; } } catch {} }
  const envKey = tier ? tierMap[tier] : null;
  return (envKey && process.env[envKey]) || process.env.ANTHROPIC_MODEL || process.env.ANTHROPIC_DEFAULT_SONNET_MODEL || 'claude-code';
};
const username = sanitize(os.userInfo().username);
const hostname = sanitize(os.hostname());
const projectRoot = process.cwd();
const sessionDir = path.join(projectRoot, 'ai-sessions');
const sessionFile = path.join(sessionDir, `${username}-ai-session-${hostname}.json`);
try { fs.mkdirSync(sessionDir, { recursive: true }); } catch {}
let session = {
  developer: username, hostname,
  model: detectModel(),
  firstSeen: new Date().toISOString(), lastUpdated: new Date().toISOString(),
  stats: { totalOperations: 0, totalFilesEdited: 0, totalAiLines: 0, totalAiLinesDeleted: 0, totalCommits: 0 },
  pendingFiles: [],
};
try {
  if (fs.existsSync(sessionFile)) {
    const existing = JSON.parse(fs.readFileSync(sessionFile, 'utf8'));
    if (existing && existing.developer) session = existing;
    session.stats = session.stats || { totalOperations: 0, totalFilesEdited: 0, totalAiLines: 0, totalAiLinesDeleted: 0, totalCommits: 0 };
    session.pendingFiles = session.pendingFiles || session.files || [];
  }
} catch {}
session.model = detectModel();
const idx = session.pendingFiles.findIndex((f) => f.path === filePath);
if (idx >= 0) {
  session.pendingFiles[idx].operations = (session.pendingFiles[idx].operations || 0) + 1;
  session.pendingFiles[idx].lastTool = toolName;
  session.pendingFiles[idx].lastModified = new Date().toISOString();
} else {
  session.pendingFiles.push({ path: filePath, tool: toolName, operations: 1, timestamp: new Date().toISOString(), lastModified: new Date().toISOString() });
  session.stats.totalFilesEdited = (session.stats.totalFilesEdited || 0) + 1;
}
session.stats.totalOperations = (session.stats.totalOperations || 0) + 1;
session.lastUpdated = new Date().toISOString();
try { fs.writeFileSync(sessionFile, JSON.stringify(session, null, 2), 'utf8'); } catch {}
process.exit(0);
