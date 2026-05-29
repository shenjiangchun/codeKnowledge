/**
 * 安全钩子脚本 - Pre Tool Use Hook
 * 在工具执行前进行安全检查，防止危险操作
 *
 * @param {Object} context - 执行上下文
 * @param {Object} context.tool - 待执行的工具信息
 * @param {Object} context.params - 工具参数
 * @param {Object} context.session - 会话信息
 * @returns {Object} 检查结果 { allowed: boolean, reason?: string, modifiedParams?: Object }
 */

function execute(context) {
    const { tool, params, session } = context;

    // 危险命令黑名单
    const DANGEROUS_COMMANDS = [
        'rm -rf',
        'DROP TABLE',
        'DROP DATABASE',
        'TRUNCATE',
        'DELETE FROM',
        'git push --force',
        'git reset --hard',
        ':(){:|:&};:',  // Fork bomb
        'dd if=',
        'mkfs',
        'format c:',
        'del /s /q',
        'rmdir /s /q'
    ];

    // 敏感文件模式
    const SENSITIVE_FILES = [
        '.env',
        'credentials',
        'secrets',
        'password',
        'private_key',
        'id_rsa',
        '.pem',
        '.key'
    ];

    // 生产环境标识
    const PROD_INDICATORS = [
        'production',
        'prod',
        'live',
        'master'
    ];

    // 检查危险命令
    function checkDangerousCommands(command) {
        if (!command) return { safe: true };

        const lowerCommand = command.toLowerCase();
        for (const dangerous of DANGEROUS_COMMANDS) {
            if (lowerCommand.includes(dangerous.toLowerCase())) {
                return {
                    safe: false,
                    reason: `检测到危险命令: ${dangerous}`,
                    severity: 'high'
                };
            }
        }
        return { safe: true };
    }

    // 检查敏感文件访问
    function checkSensitiveFiles(filePath) {
        if (!filePath) return { safe: true };

        const lowerPath = filePath.toLowerCase();
        for (const sensitive of SENSITIVE_FILES) {
            if (lowerPath.includes(sensitive.toLowerCase())) {
                return {
                    safe: false,
                    reason: `检测到敏感文件访问: ${sensitive}`,
                    severity: 'medium'
                };
            }
        }
        return { safe: true };
    }

    // 检查生产环境操作
    function checkProductionEnvironment(env, branch) {
        if (!env && !branch) return { safe: true };

        const envLower = (env || '').toLowerCase();
        const branchLower = (branch || '').toLowerCase();

        for (const indicator of PROD_INDICATORS) {
            if (envLower === indicator || branchLower === indicator) {
                return {
                    safe: false,
                    reason: `检测到生产环境操作: ${env || branch}`,
                    severity: 'high',
                    requiresConfirmation: true
                };
            }
        }
        return { safe: true };
    }

    // 主检查逻辑
    try {
        // 根据工具类型执行不同的检查
        switch (tool.name) {
            case 'Bash':
                const cmdResult = checkDangerousCommands(params.command);
                if (!cmdResult.safe) {
                    return {
                        allowed: false,
                        reason: cmdResult.reason,
                        severity: cmdResult.severity
                    };
                }
                break;

            case 'Read':
            case 'Write':
            case 'Edit':
                const fileResult = checkSensitiveFiles(params.file_path);
                if (!fileResult.safe) {
                    return {
                        allowed: false,
                        reason: fileResult.reason,
                        severity: fileResult.severity
                    };
                }
                break;

            case 'git':
                const branchResult = checkProductionEnvironment(null, params.branch);
                if (!branchResult.safe) {
                    return {
                        allowed: false,
                        reason: branchResult.reason,
                        severity: branchResult.severity,
                        requiresConfirmation: true
                    };
                }
                break;
        }

        // 记录审计日志
        logAudit({
            timestamp: new Date().toISOString(),
            tool: tool.name,
            params: params,
            session: session.id,
            result: 'allowed'
        });

        return { allowed: true };

    } catch (error) {
        // 错误时默认允许，但记录警告
        console.warn('安全钩子执行异常:', error);
        return {
            allowed: true,
            warning: '安全检查异常，已放行'
        };
    }
}

// 审计日志记录
function logAudit(entry) {
    // 实际实现中可写入数据库或日志文件
    console.log('[AUDIT]', JSON.stringify(entry));
}

// 导出钩子函数
module.exports = { execute };