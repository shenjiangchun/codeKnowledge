#!/usr/bin/env node
// CodeAI auto-generated hook: prepare-commit-msg.js
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');
const commitMsgFile = process.argv[2];
const commitSource = process.argv[3] || '';
if (!commitMsgFile || ['merge', 'squash'].includes(commitSource)) process.exit(0);
const projectRoot = (() => { try { return execSync('git rev-parse --show-toplevel', { encoding: 'utf8' }).trim(); } catch { return process.cwd(); } })();
const sanitize = (s) => (s || 'unknown').replace(/[^a-zA-Z0-9_-]/g, '_').toLowerCase().slice(0, 30);
const detectModel = () => {
  const tierMap = { sonnet: 'ANTHROPIC_DEFAULT_SONNET_MODEL', opus: 'ANTHROPIC_DEFAULT_OPUS_MODEL', haiku: 'ANTHROPIC_DEFAULT_HAIKU_MODEL' };
  const settingsPaths = [path.join(projectRoot, '.claude', 'settings.json'), path.join(projectRoot, '.claude', 'settings.local.json'), path.join(os.homedir(), '.claude', 'settings.json')];
  let tier = null;
  for (const sp of settingsPaths) { try { const cfg = JSON.parse(fs.readFileSync(sp, 'utf8')); if (cfg?.model && ['sonnet', 'opus', 'haiku'].includes(cfg.model)) { tier = cfg.model; break; } } catch {} }
  const envKey = tier ? tierMap[tier] : null;
  return (envKey && process.env[envKey]) || process.env.ANTHROPIC_MODEL || process.env.ANTHROPIC_DEFAULT_SONNET_MODEL || 'claude-code';
};
const sessionRelPath = `ai-sessions/${sanitize(os.userInfo().username)}-ai-session-${sanitize(os.hostname())}.json`;
const sessionFile = path.join(projectRoot, sessionRelPath);
const stageSession = () => { try { if (fs.existsSync(sessionFile)) execSync(`git add \"${sessionRelPath}\"`, { cwd: projectRoot, stdio: 'pipe' }); } catch {} };
let commitMsg = '';
try { commitMsg = fs.readFileSync(commitMsgFile, 'utf8'); } catch { process.exit(0); }
if (commitMsg.includes('AI-Generated:')) { stageSession(); process.exit(0); }
let session = null;
try { if (fs.existsSync(sessionFile)) session = JSON.parse(fs.readFileSync(sessionFile, 'utf8')); } catch {}
let stagedFiles = [];
try { stagedFiles = execSync('git diff --cached --name-only', { encoding: 'utf8' }).trim().split('\n').filter(Boolean).map((f) => f.replace(/\\/g, '/')); } catch { stageSession(); process.exit(0); }
if (stagedFiles.length === 0) { stageSession(); process.exit(0); }
const pendingFiles = (session?.pendingFiles || session?.files || []).map((f) => (f.path || '').replace(/\\/g, '/'));
const isMatch = (a, b) => a === b || a.endsWith('/' + b) || b.endsWith('/' + a) || a.endsWith(b);
const aiMatchedFiles = stagedFiles.filter((sf) => pendingFiles.some((pf) => isMatch(pf, sf)));
if (aiMatchedFiles.length === 0) { stageSession(); process.exit(0); }
let aiLinesAdded = 0; let aiLinesDeleted = 0; let totalLinesAdded = 0; let totalLinesDeleted = 0;
try {
  const numstat = execSync('git diff --cached --numstat', { encoding: 'utf8' });
  for (const line of numstat.trim().split('\n').filter(Boolean)) {
    const parts = line.split('\t');
    const add = parseInt(parts[0], 10); const del = parseInt(parts[1], 10);
    const fPath = (parts[2] || '').replace(/\\/g, '/');
    if (!Number.isNaN(add)) { totalLinesAdded += add; if (aiMatchedFiles.some((sf) => isMatch(sf, fPath))) aiLinesAdded += add; }
    if (!Number.isNaN(del)) { totalLinesDeleted += del; if (aiMatchedFiles.some((sf) => isMatch(sf, fPath))) aiLinesDeleted += del; }
  }
} catch { aiLinesAdded = 0; aiLinesDeleted = 0; }
const model = String(detectModel()).replace(/-cc$/, '');
const shortList = aiMatchedFiles.slice(0, 5).map((f) => { const p = f.split('/'); return p.length > 2 ? p.slice(-2).join('/') : f; }).join(', ');
const extra = aiMatchedFiles.length > 5 ? ` (+${aiMatchedFiles.length - 5} more)` : '';
const trailers = [
  'AI-Generated: true',
  'AI-Tool: claude-code',
  `AI-Model: ${model}`,
  `AI-Lines: ${aiLinesAdded}`,
  `AI-Lines-Deleted: ${aiLinesDeleted}`,
  `AI-Total-Lines: ${totalLinesAdded}`,
  `AI-Total-Lines-Deleted: ${totalLinesDeleted}`,
  `AI-Files: ${aiMatchedFiles.length}`,
  `AI-File-List: ${shortList}${extra}`,
  `AI-Developer: ${sanitize(os.userInfo().username)}@${sanitize(os.hostname())}`,
].join('\n');
try { fs.writeFileSync(commitMsgFile, commitMsg.trimEnd() + '\n\n' + trailers + '\n', 'utf8'); } catch {}
try {
  if (session) {
    session.model = detectModel();
    const oldPending = session.pendingFiles || session.files || [];
    session.pendingFiles = oldPending.filter((f) => !aiMatchedFiles.some((sf) => isMatch((f.path || '').replace(/\\/g, '/'), sf)));
    delete session.files;
    session.stats = session.stats || { totalOperations: 0, totalFilesEdited: 0, totalAiLines: 0, totalAiLinesDeleted: 0, totalCommits: 0 };
    session.stats.totalCommits = (session.stats.totalCommits || 0) + 1;
    session.stats.totalAiLines = (session.stats.totalAiLines || 0) + Math.max(0, aiLinesAdded);
    session.stats.totalAiLinesDeleted = (session.stats.totalAiLinesDeleted || 0) + Math.max(0, aiLinesDeleted);
    session.lastUpdated = new Date().toISOString();
    fs.writeFileSync(sessionFile, JSON.stringify(session, null, 2), 'utf8');
  }
} catch {}
stageSession();
process.exit(0);
