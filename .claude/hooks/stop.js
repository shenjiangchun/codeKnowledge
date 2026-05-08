#!/usr/bin/env node
// CodeAI auto-generated hook: stop.js
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const SKIP_DIRS = new Set(['.git', 'node_modules', '__pycache__', '.venv', 'venv', 'dist', 'build', '.mypy_cache', '.pytest_cache']);
const findAndDeleteNul = (dir, depth = 0) => {
  if (depth > 2) return;
  try {
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name);
      if (entry.isFile() && entry.name === 'nul') fs.unlinkSync(fullPath);
      else if (entry.isDirectory() && !entry.name.startsWith('.') && !SKIP_DIRS.has(entry.name)) findAndDeleteNul(fullPath, depth + 1);
    }
  } catch {}
};
findAndDeleteNul(process.cwd());
const audioFile = path.join(process.cwd(), '.claude', 'audio', 'completed.wav');
try {
  if (fs.existsSync(audioFile)) {
    if (process.platform === 'darwin') execSync(`afplay \"${audioFile}\"`, { stdio: ['pipe', 'pipe', 'pipe'] });
    else if (process.platform === 'win32') execSync(`powershell -c \"(New-Object Media.SoundPlayer '${audioFile.replace(/'/g, "''")}').PlaySync()\"`, { stdio: ['pipe', 'pipe', 'pipe'] });
    else if (process.platform === 'linux') { try { execSync(`aplay \"${audioFile}\"`, { stdio: ['pipe', 'pipe', 'pipe'] }); } catch { try { execSync(`paplay \"${audioFile}\"`, { stdio: ['pipe', 'pipe', 'pipe'] }); } catch {} } }
  }
} catch {}
process.exit(0);
