/**
 * RAM 对话助手的动作工具：以用户身份（HISI_SCOPE_TOKEN）调后端发起分析、提交澄清、确认节点。
 *
 * 令牌由 pi 会话的环境变量传入（后端 PiFlowClient.runWithToken → mcp.json 的 ${HISI_SCOPE_TOKEN} 引用）。
 * 没有令牌时一律返回明确的权限错误——动作工具绝不能以匿名身份执行。
 */
import { getApiClient } from '../client/apiClient.js';

export const ACT_TOOLS = [
  'act_start_analysis',
  'act_start_status',
  'act_resume_session',
  'act_submit_clarification',
  'act_confirm_node',
] as const;

export const actToolDefinitions = [
  {
    name: 'act_start_analysis',
    description:
      '以用户身份发起一次需求分析（RAM 需求分析大师）。用户明确要求分析某个需求时使用。' +
      '参数 raw_input 为需求原文，project_path 为项目绝对路径，mode 默认 interactive。返回 session_id，后续用 act_submit_clarification / act_confirm_node 与它交互。',
    inputSchema: {
      type: 'object',
      properties: {
        raw_input: { type: 'string', description: '需求原文' },
        project_path: { type: 'string', description: '项目绝对路径' },
        mode: { type: 'string', description: 'interactive（默认）' },
      },
      required: ['raw_input', 'project_path'],
    },
  },
  {
    name: 'act_start_status',
    description:
      '以用户身份发起一次项目现状分析。用户要求了解项目现状/健康度时使用。返回 session_id。',
    inputSchema: {
      type: 'object',
      properties: {
        project_path: { type: 'string', description: '项目绝对路径' },
        question: { type: 'string', description: '要回答的问题（可选）' },
      },
      required: ['project_path'],
    },
  },
  {
    name: 'act_resume_session',
    description: '继续一个等待中的 RAM 会话（例如确认门或澄清之后）。',
    inputSchema: {
      type: 'object',
      properties: {
        session_id: { type: 'string', description: '会话 id（分析接口返回的那个）' },
      },
      required: ['session_id'],
    },
  },
  {
    name: 'act_submit_clarification',
    description:
      '回答需求分析器提出的澄清问题。answers 是「问题原文 → 回答」的映射；只提交用户明确给出的答案，不要替用户编。',
    inputSchema: {
      type: 'object',
      properties: {
        session_id: { type: 'string' },
        answers: { type: 'object', description: '问题 → 回答 映射' },
      },
      required: ['session_id', 'answers'],
    },
  },
  {
    name: 'act_confirm_node',
    description:
      '确认或驳回需求分析某个节点的产出。action 为 approve 或 reject；驳回时 feedback 说明原因。用户在页面上说「确认/继续/驳回」时使用。',
    inputSchema: {
      type: 'object',
      properties: {
        session_id: { type: 'string' },
        node_name: { type: 'string' },
        action: { type: 'string', enum: ['approve', 'reject'] },
        feedback: { type: 'string', description: '驳回时的原因（可选）' },
      },
      required: ['session_id', 'node_name', 'action'],
    },
  },
];

function tokenFromEnv(): string | null {
  const token = process.env.HISI_SCOPE_TOKEN;
  return token && token.trim() ? token.trim() : null;
}

function forbidden(): Record<string, unknown> {
  return { success: false, error: '缺少用户令牌（HISI_SCOPE_TOKEN）：动作工具不能以匿名身份执行' };
}

export async function handleActToolCall(
  toolName: string,
  args: Record<string, unknown>
): Promise<unknown> {
  const token = tokenFromEnv();
  if (!token) {
    return forbidden();
  }
  const client = getApiClient();
  const headers = { Authorization: `Bearer ${token}` };

  switch (toolName) {
    case 'act_start_analysis': {
      const body = {
        rawInput: String(args.raw_input ?? ''),
        projectPath: String(args.project_path ?? ''),
        projectPaths: [String(args.project_path ?? '')],
      };
      return client.post<unknown>('/api/ram/sessions', body, { headers });
    }
    case 'act_start_status': {
      const body = {
        projectPath: String(args.project_path ?? ''),
        question: String(args.question ?? ''),
        mode: 'full',
      };
      return client.post<unknown>('/api/ram/status/start', body, { headers });
    }
    case 'act_resume_session': {
      return client.post<unknown>(`/api/ram/sessions/${encodeURIComponent(String(args.session_id))}/resume`, {}, { headers });
    }
    case 'act_submit_clarification': {
      return client.post<unknown>(
        `/api/ram/sessions/${encodeURIComponent(String(args.session_id))}/clarify`,
        { answers: args.answers ?? {} },
        { headers }
      );
    }
    case 'act_confirm_node': {
      return client.post<unknown>(
        `/api/ram/sessions/${encodeURIComponent(String(args.session_id))}/confirm`,
        {
          nodeName: String(args.node_name ?? ''),
          action: String(args.action ?? 'approve'),
          feedback: args.feedback == null ? null : String(args.feedback),
        },
        { headers }
      );
    }
    default:
      throw new Error(`Unknown tool: ${toolName}`);
  }
}
