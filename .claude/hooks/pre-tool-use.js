#!/usr/bin/env node
// CodeAI auto-generated hook: pre-tool-use.js
const fs = require('fs');
let inputData = '';
try { inputData = fs.readFileSync(0, 'utf8'); } catch { process.stdout.write(JSON.stringify({ continue: true })); process.exit(0); }
let input = {};
try { input = JSON.parse(inputData || '{}'); } catch { process.stdout.write(JSON.stringify({ continue: true })); process.exit(0); }
const toolName = input.tool_name;
const toolInput = input.tool_input || {};
if (toolName === 'Bash') {
  const command = toolInput.command || '';
  const nulPattern = /[12]?\s*>\s*nul\b/i;
  if (nulPattern.test(command)) {
    process.stdout.write(JSON.stringify({ decision: 'block', reason: '检测到 `> nul`，请改用 `> /dev/null 2>&1` 或移除重定向。' }));
    process.exit(0);
  }
  const dangerousPatterns = [
    /rm\s+-rf\s+\/(?!\w)/,
    /rm\s+-rf\s+\*/,
    /drop\s+database/i,
    /truncate\s+table/i,
    /git\s+push\s+--force\s+(origin\s+)?(main|master)/i,
    /git\s+reset\s+--hard\s+HEAD~\d+/,
    />\s*\/dev\/sd[a-z]/,
    /mkfs\./,
    /:(){ :|:& };:/,
  ];
  if (dangerousPatterns.some((p) => p.test(command))) {
    process.stdout.write(JSON.stringify({ decision: 'block', reason: `危险操作已拦截: ${command}` }));
    process.exit(0);
  }
}
if (toolName === 'Write') {
  const filePath = toolInput.file_path || '';
  const sensitiveFiles = ['.env.dev', '.env.prod', 'application.yml', 'credentials.json', 'secrets.json'];
  if (sensitiveFiles.some((n) => filePath.endsWith(n))) {
    process.stdout.write(JSON.stringify({ continue: true, systemMessage: '正在写入敏感文件，请确认不要提交敏感信息。' }));
    process.exit(0);
  }
}
process.stdout.write(JSON.stringify({ continue: true }));
