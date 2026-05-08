/**
 * 自我进化钩子脚本 - Skill Forced Evaluation Hook
 * 在技能执行后进行自我评估和学习改进
 *
 * @param {Object} context - 执行上下文
 * @param {Object} context.skill - 执行的技能信息
 * @param {Object} context.result - 技能执行结果
 * @param {Object} context.session - 会话信息
 * @param {Object} context.feedback - 用户反馈（如果有）
 * @returns {Object} 评估结果 { score: number, improvements: string[], learned: boolean }
 */

function execute(context) {
    const { skill, result, session, feedback } = context;

    // 评估维度权重
    const EVALUATION_WEIGHTS = {
        correctness: 0.3,      // 正确性
        efficiency: 0.2,      // 效率
        codeQuality: 0.25,    // 代码质量
        userSatisfaction: 0.25 // 用户满意度
    };

    // 评分阈值
    const SCORE_THRESHOLDS = {
        excellent: 0.9,
        good: 0.7,
        acceptable: 0.5,
        poor: 0.3
    };

    /**
     * 评估执行正确性
     */
    function evaluateCorrectness(result) {
        if (!result) return 0;

        let score = 1.0;

        // 检查是否有错误
        if (result.error) {
            score -= 0.5;
        }

        // 检查结果完整性
        if (result.incomplete) {
            score -= 0.3;
        }

        // 检查是否符合预期
        if (result.validation && !result.validation.passed) {
            score -= 0.2;
        }

        return Math.max(0, score);
    }

    /**
     * 评估执行效率
     */
    function evaluateEfficiency(result) {
        if (!result || !result.metrics) return 0.5;

        let score = 1.0;

        // 执行时间评估
        if (result.metrics.duration) {
            const duration = result.metrics.duration;
            if (duration > 30000) { // 超过30秒
                score -= 0.3;
            } else if (duration > 10000) { // 超过10秒
                score -= 0.1;
            }
        }

        // 资源使用评估
        if (result.metrics.memoryUsage && result.metrics.memoryUsage > 100 * 1024 * 1024) {
            score -= 0.2;
        }

        return Math.max(0, score);
    }

    /**
     * 评估代码质量
     */
    function evaluateCodeQuality(result) {
        if (!result || !result.code) return 0.5;

        let score = 1.0;
        const code = result.code;

        // 检查代码规范
        const patterns = {
            hasComments: /\/\*[\s\S]*?\*\/|\/\/.*/g.test(code),
            hasErrorHandling: /try\s*\{|catch\s*\(/g.test(code),
            hasLogging: /log\.(info|debug|error|warn)/g.test(code),
            hasValidation: /validate|check|verify/g.test(code)
        };

        if (!patterns.hasComments) score -= 0.1;
        if (!patterns.hasErrorHandling) score -= 0.2;
        if (!patterns.hasLogging) score -= 0.1;
        if (!patterns.hasValidation) score -= 0.1;

        // 检查代码复杂度
        const lines = code.split('\n').length;
        const functions = (code.match(/function\s+\w+|=>\s*{/g) || []).length;

        if (lines > 100 && functions < 3) {
            score -= 0.2; // 代码过长且没有拆分
        }

        return Math.max(0, score);
    }

    /**
     * 评估用户满意度
     */
    function evaluateUserSatisfaction(feedback) {
        if (!feedback) return 0.5;

        if (feedback.rating !== undefined) {
            return feedback.rating / 5; // 假设评分为1-5
        }

        if (feedback.positive) return 0.9;
        if (feedback.negative) return 0.2;

        return 0.5;
    }

    /**
     * 计算综合评分
     */
    function calculateOverallScore(evaluations) {
        let totalScore = 0;

        totalScore += evaluations.correctness * EVALUATION_WEIGHTS.correctness;
        totalScore += evaluations.efficiency * EVALUATION_WEIGHTS.efficiency;
        totalScore += evaluations.codeQuality * EVALUATION_WEIGHTS.codeQuality;
        totalScore += evaluations.userSatisfaction * EVALUATION_WEIGHTS.userSatisfaction;

        return totalScore;
    }

    /**
     * 生成改进建议
     */
    function generateImprovements(evaluations, result) {
        const improvements = [];

        if (evaluations.correctness < SCORE_THRESHOLDS.good) {
            improvements.push('建议增加单元测试验证功能正确性');
            improvements.push('考虑添加更多的边界条件处理');
        }

        if (evaluations.efficiency < SCORE_THRESHOLDS.good) {
            improvements.push('优化执行路径，减少不必要的计算');
            improvements.push('考虑使用缓存或异步处理提升性能');
        }

        if (evaluations.codeQuality < SCORE_THRESHOLDS.good) {
            improvements.push('添加必要的注释说明复杂逻辑');
            improvements.push('完善异常处理机制');
            improvements.push('遵循项目代码规范');
        }

        if (evaluations.userSatisfaction < SCORE_THRESHOLDS.acceptable) {
            improvements.push('优化输出格式，提升可读性');
            improvements.push('提供更详细的执行说明');
        }

        return improvements;
    }

    /**
     * 学习记录
     */
    async function recordLearning(skill, score, improvements, context) {
        const learningRecord = {
            skillId: skill.id,
            skillName: skill.name,
            timestamp: new Date().toISOString(),
            score: score,
            improvements: improvements,
            context: {
                inputType: context.inputType,
                outputType: context.outputType,
                sessionTags: context.session?.tags || []
            }
        };

        // 存储学习记录（实际实现中写入知识库）
        console.log('[LEARNING]', JSON.stringify(learningRecord));

        return learningRecord;
    }

    // 主评估逻辑
    try {
        // 执行各维度评估
        const evaluations = {
            correctness: evaluateCorrectness(result),
            efficiency: evaluateEfficiency(result),
            codeQuality: evaluateCodeQuality(result),
            userSatisfaction: evaluateUserSatisfaction(feedback)
        };

        // 计算综合评分
        const overallScore = calculateOverallScore(evaluations);

        // 生成改进建议
        const improvements = generateImprovements(evaluations, result);

        // 判断是否需要学习
        const needsLearning = overallScore < SCORE_THRESHOLDS.good || feedback?.needsImprovement;

        // 记录学习
        let learningRecord = null;
        if (needsLearning) {
            learningRecord = await recordLearning(skill, overallScore, improvements, context);
        }

        // 生成评估报告
        const evaluationReport = {
            skillId: skill.id,
            skillName: skill.name,
            overallScore: overallScore,
            scoreLevel: getScoreLevel(overallScore, SCORE_THRESHOLDS),
            dimensions: evaluations,
            improvements: improvements,
            learned: needsLearning,
            learningRecord: learningRecord,
            timestamp: new Date().toISOString()
        };

        return evaluationReport;

    } catch (error) {
        console.error('自我评估执行异常:', error);
        return {
            skillId: skill?.id,
            error: error.message,
            score: 0,
            learned: false
        };
    }
}

/**
 * 获取评分等级
 */
function getScoreLevel(score, thresholds) {
    if (score >= thresholds.excellent) return 'excellent';
    if (score >= thresholds.good) return 'good';
    if (score >= thresholds.acceptable) return 'acceptable';
    return 'poor';
}

// 导出钩子函数
module.exports = { execute };