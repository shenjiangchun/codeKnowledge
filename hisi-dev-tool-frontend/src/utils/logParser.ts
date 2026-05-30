/**
 * 日志解析工具
 * 用于区分报错信息和堆栈信息
 */

export interface StackFrame {
  /** 完整类名 */
  className: string
  /** 方法名 */
  methodName: string
  /** 文件名 */
  fileName: string
  /** 行号 */
  lineNumber: number | null
  /** 是否原生方法 */
  isNative: boolean
  /** 原始文本 */
  raw: string
}

export interface CausedByInfo {
  errorType: string
  errorMessage: string
  stackFrames: StackFrame[]
  suppressedCount: number | null
}

export interface ParsedErrorLog {
  // 日志头部信息
  timestamp: string
  logLevel: string
  threadInfo: string
  traceId: string
  loggerName: string
  headerMessage: string

  // 主错误信息
  errorType: string
  errorMessage: string

  // 堆栈信息
  stackFrames: StackFrame[]
  causedByChain: CausedByInfo[]

  // 原始内容
  rawMessage: string
  rawStackTrace: string

  // 解析是否成功
  parseSuccess: boolean
}

/**
 * 解析 Java 异常日志
 */
export function parseJavaErrorLog(rawLog: string): ParsedErrorLog {
  const lines = rawLog.split('\n')

  // 默认返回值
  const defaultResult: ParsedErrorLog = {
    timestamp: '',
    logLevel: '',
    threadInfo: '',
    traceId: '',
    loggerName: '',
    headerMessage: '',
    errorType: '',
    errorMessage: '',
    stackFrames: [],
    causedByChain: [],
    rawMessage: rawLog,
    rawStackTrace: '',
    parseSuccess: false
  }

  if (!rawLog || !lines.length) {
    return defaultResult
  }

  let currentLineIndex = 0

  // 1. 解析日志头部 (第一行)
  // 格式: 2026-03-21 14:30:45.123 [thread-name - traceId] LEVEL className : message
  // 或者: 2026-03-21 14:30:45.123 [thread-name] LEVEL className : message
  const headerPattern = /^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+\[([^\]]+)\]\s+(\w+)\s+([\w.$]+)\s*:\s*(.*)$/
  const headerMatch = lines[0]?.match(headerPattern)

  let headerMessage = ''
  if (headerMatch) {
    defaultResult.timestamp = headerMatch[1]
    defaultResult.threadInfo = headerMatch[2]

    // 尝试从线程信息中提取 traceId
    const traceIdMatch = headerMatch[2].match(/-\s*([A-Fa-f0-9-]+)\s*$/)
    if (traceIdMatch) {
      defaultResult.traceId = traceIdMatch[1]
    }

    defaultResult.logLevel = headerMatch[3]
    defaultResult.loggerName = headerMatch[4]
    headerMessage = headerMatch[5]
    currentLineIndex = 1
  }

  // 2. 跳过空行
  while (currentLineIndex < lines.length && lines[currentLineIndex].trim() === '') {
    currentLineIndex++
  }

  // 3. 解析异常类型行
  // 格式: java.lang.NullPointerException: message
  // 或者: java.lang.NullPointerException
  const exceptionPattern = /^([a-zA-Z_$][\w.$]*(?:Exception|Error|Throwable)):\s*(.*)$/
  let firstExceptionLine = currentLineIndex

  // 查找第一个异常行
  while (firstExceptionLine < lines.length) {
    const line = lines[firstExceptionLine].trim()
    if (exceptionPattern.test(line)) {
      break
    }
    // 如果不是空行且不是堆栈帧，可能是 headerMessage 的一部分
    if (line && !line.startsWith('at ') && !line.startsWith('Caused by:') && !line.startsWith('... ')) {
      headerMessage += '\n' + line
    }
    firstExceptionLine++
  }

  defaultResult.headerMessage = headerMessage

  // 如果没找到异常行，返回原始内容
  if (firstExceptionLine >= lines.length) {
    // 尝试从 headerMessage 中提取错误信息
    const errorMatch = headerMessage.match(/([a-zA-Z_$][\w.$]*(?:Exception|Error)):\s*(.*)$/)
    if (errorMatch) {
      defaultResult.errorType = errorMatch[1]
      defaultResult.errorMessage = errorMatch[2]
      defaultResult.parseSuccess = true
    }
    return defaultResult
  }

  // 解析主异常
  const mainExceptionMatch = lines[firstExceptionLine].trim().match(exceptionPattern)
  if (mainExceptionMatch) {
    defaultResult.errorType = mainExceptionMatch[1]
    defaultResult.errorMessage = mainExceptionMatch[2]
    defaultResult.parseSuccess = true
  }

  // 4. 解析堆栈帧
  // 格式: at package.Class.method(File.java:123)
  // 或者: at package.Class.method(Native Method)
  const stackFramePattern = /^\s*at\s+([a-zA-Z_$][\w.$]*)\.([a-zA-Z_$][\w]*)\(([^):]+)(?::(\d+))?\)$/
  const nativePattern = /^\s*at\s+([a-zA-Z_$][\w.$]*)\.([a-zA-Z_$][\w]*)\(Native\s+Method\)$/

  let currentCause: CausedByInfo | null = null
  let currentFrames: StackFrame[] = defaultResult.stackFrames

  for (let i = firstExceptionLine + 1; i < lines.length; i++) {
    const line = lines[i]
    const trimmedLine = line.trim()

    // 跳过空行
    if (!trimmedLine) continue

    // 解析 "Caused by: ..."
    if (trimmedLine.startsWith('Caused by:')) {
      const causeMatch = trimmedLine.match(/^Caused by:\s*([a-zA-Z_$][\w.$]*(?:Exception|Error|Throwable)):\s*(.*)$/)
      if (causeMatch) {
        currentCause = {
          errorType: causeMatch[1],
          errorMessage: causeMatch[2],
          stackFrames: [],
          suppressedCount: null
        }
        defaultResult.causedByChain.push(currentCause)
        currentFrames = currentCause.stackFrames
      }
      continue
    }

    // 解析 "... N more"
    const moreMatch = trimmedLine.match(/^\s*\.\.\.\s+(\d+)\s+more$/)
    if (moreMatch && currentCause) {
      currentCause.suppressedCount = parseInt(moreMatch[1], 10)
      continue
    }

    // 解析堆栈帧
    let frameMatch = trimmedLine.match(stackFramePattern)
    let isNative = false

    if (!frameMatch) {
      frameMatch = trimmedLine.match(nativePattern)
      if (frameMatch) {
        isNative = true
      }
    }

    if (frameMatch) {
      const frame: StackFrame = {
        className: frameMatch[1],
        methodName: frameMatch[2],
        fileName: frameMatch[3] || '',
        lineNumber: frameMatch[4] ? parseInt(frameMatch[4], 10) : null,
        isNative,
        raw: trimmedLine
      }
      currentFrames.push(frame)
    }
  }

  // 5. 构建原始堆栈文本
  defaultResult.rawStackTrace = lines.slice(firstExceptionLine).join('\n')

  return defaultResult
}

/**
 * 格式化解析结果用于 Claude 分析
 */
export function formatForAnalysis(parsed: ParsedErrorLog): {
  errorSummary: string
  errorType: string
  errorMessage: string
  stackTrace: string
  causedBy: string
} {
  // 错误摘要：日志头 + 异常类型 + 消息
  let errorSummary = ''
  if (parsed.headerMessage) {
    errorSummary += parsed.headerMessage
  }
  if (parsed.errorType) {
    errorSummary += `\n异常类型: ${parsed.errorType}`
  }
  if (parsed.errorMessage) {
    errorSummary += `\n异常消息: ${parsed.errorMessage}`
  }

  // 堆栈信息：格式化后的堆栈帧
  let stackTrace = ''
  if (parsed.stackFrames.length > 0) {
    stackTrace = parsed.stackFrames.map((frame, index) =>
      `  [${index + 1}] ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber || 'N/A'})`
    ).join('\n')
  }

  // Caused by 链
  let causedBy = ''
  if (parsed.causedByChain.length > 0) {
    causedBy = parsed.causedByChain.map((cause, index) => {
      let str = `[${index + 1}] ${cause.errorType}: ${cause.errorMessage}`
      if (cause.stackFrames.length > 0) {
        str += '\n' + cause.stackFrames.slice(0, 3).map(f =>
      `      at ${f.className}.${f.methodName}(${f.fileName}:${f.lineNumber || 'N/A'})`
        ).join('\n')
        if (cause.stackFrames.length > 3) {
          str += `\n      ... ${cause.stackFrames.length - 3} more`
        }
      }
      return str
    }).join('\n\n')
  }

  return {
    errorSummary: errorSummary.trim(),
    errorType: parsed.errorType,
    errorMessage: parsed.errorMessage,
    stackTrace: stackTrace,
    causedBy
  }
}

/**
 * 获取简化版的堆栈信息（只保留关键帧）
 */
export function getCompactStackTrace(frames: StackFrame[], maxFrames: number = 10): string {
  if (!frames.length) return ''

  // 优先保留项目代码帧
  const projectFrames = frames.filter(f =>
    !f.className.startsWith('java.') &&
    !f.className.startsWith('javax.') &&
    !f.className.startsWith('org.springframework.') &&
    !f.className.startsWith('org.apache.') &&
    !f.className.startsWith('com.zaxxer.') &&
    !f.className.startsWith('sun.')
  )

  const selectedFrames = projectFrames.length > 0 ? projectFrames : frames

  return selectedFrames.slice(0, maxFrames).map((frame) =>
    `${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber || 'N/A'})`
  ).join('\n')
}
