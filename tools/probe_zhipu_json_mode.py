#!/usr/bin/env python3
"""
智谱 glm-4-flash response_format 能力探测脚本

测试三种模式：
1. structured-output: response_format = {"type": "json_schema", "json_schema": {...}}
2. json-mode:        response_format = {"type": "json_object"}
3. prompt-only:      纯 prompt 工程，不设 response_format

用法: python tools/probe_zhipu_json_mode.py
"""

import json
import requests
import time

# 从 application-local.yml 提取
API_KEY = "a20e9da682604fd79e01d804a886002c.aOG4OuJm1N5l9NFl"
BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
MODEL = "glm-4-flash"

HEADERS = {
    "Authorization": f"Bearer {API_KEY}",
    "Content-Type": "application/json",
}

# 测试数据：3 个方法，要求返回 JSON 数组
TEST_PROMPT = """请为以下方法列表生成描述，严格遵守输出格式。

## 方法列表（共 3 个）
[0] 类名：UserService  方法名：findById  签名：(Long id)  注释：根据ID查询用户
[1] 类名：OrderService  方法名：createOrder  签名：(CreateOrderRequest req)  注释：创建订单
[2] 类名：PaymentService  方法名：processRefund  签名：(RefundRequest req)  注释：处理退款

## 输出格式
返回一个 JSON 数组，长度=3，元素顺序与编号一致。
示例：["描述0", "描述1", "描述2"]
禁止输出数组以外的任何内容。"""


def call_api(payload: dict, label: str) -> dict:
    """调用 API 并返回结果字典"""
    print(f"\n{'='*60}")
    print(f"🧪 测试: {label}")
    print(f"{'='*60}")
    print(f"  model: {MODEL}")
    print(f"  payload keys: {list(payload.keys())}")
    if "response_format" in payload:
        rf = payload["response_format"]
        print(f"  response_format: {json.dumps(rf, ensure_ascii=False)}")
    print(f"  prompt 长度: {len(TEST_PROMPT)} chars")

    start = time.time()
    try:
        resp = requests.post(
            f"{BASE_URL}/chat/completions",
            headers=HEADERS,
            json=payload,
            timeout=30,
        )
        elapsed = time.time() - start
        status = resp.status_code

        print(f"  HTTP {status} ({elapsed:.2f}s)")

        if status == 200:
            body = resp.json()
            content = body["choices"][0]["message"]["content"]
            print(f"  原始响应: {content[:200]}")
            # 尝试解析
            try:
                parsed = json.loads(content)
                print(f"  ✅ JSON 解析成功: type={type(parsed).__name__}, len={len(parsed) if isinstance(parsed, list) else 'N/A'}")
                if isinstance(parsed, list):
                    for i, item in enumerate(parsed):
                        print(f"    [{i}] {item}")
                return {"status": "ok", "parsed": True, "content": content, "elapsed": elapsed}
            except json.JSONDecodeError:
                # 尝试提取 JSON
                import re
                match = re.search(r'\[.*?\]', content, re.DOTALL)
                if match:
                    try:
                        parsed = json.loads(match.group(0))
                        print(f"  ⚠️  需要哨兵提取: 提取到 {len(parsed)} 个元素")
                        return {"status": "ok_with_extraction", "parsed": True, "content": content, "elapsed": elapsed}
                    except:
                        pass
                print(f"  ❌ JSON 解析失败 (无可提取的数组)")
                return {"status": "parse_error", "parsed": False, "content": content, "elapsed": elapsed}
        else:
            print(f"  ❌ HTTP 错误: {resp.text[:200]}")
            return {"status": "http_error", "parsed": False, "content": resp.text, "elapsed": elapsed}

    except Exception as e:
        elapsed = time.time() - start
        print(f"  ❌ 异常: {e}")
        return {"status": "exception", "parsed": False, "content": str(e), "elapsed": elapsed}


def main():
    print("=" * 60)
    print("🔬 智谱 glm-4-flash response_format 能力探测")
    print(f"   endpoint: {BASE_URL}/chat/completions")
    print(f"   model: {MODEL}")
    print("=" * 60)

    results = {}

    # === 测试 1: structured-output (json_schema) ===
    schema_payload = {
        "model": MODEL,
        "messages": [{"role": "user", "content": TEST_PROMPT}],
        "temperature": 0.0,
        "max_tokens": 512,
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "method_descriptions",
                "strict": True,
                "schema": {
                    "type": "object",
                    "properties": {
                        "descriptions": {
                            "type": "array",
                            "items": {"type": "string"},
                        }
                    },
                    "required": ["descriptions"],
                    "additionalProperties": False,
                }
            }
        }
    }
    results["structured-output"] = call_api(schema_payload, "structured-output (json_schema)")

    # === 测试 2: json-mode (json_object) ===
    json_mode_payload = {
        "model": MODEL,
        "messages": [{"role": "user", "content": TEST_PROMPT}],
        "temperature": 0.0,
        "max_tokens": 512,
        "response_format": {"type": "json_object"},
    }
    results["json-mode"] = call_api(json_mode_payload, "json-mode (json_object)")

    # === 测试 3: prompt-only ===
    prompt_only_payload = {
        "model": MODEL,
        "messages": [{"role": "user", "content": TEST_PROMPT}],
        "temperature": 0.0,
        "max_tokens": 512,
    }
    results["prompt-only"] = call_api(prompt_only_payload, "prompt-only (无 response_format)")

    # === 汇总 ===
    print(f"\n{'='*60}")
    print("📋 汇总")
    print(f"{'='*60}")
    print(f"{'模式':<25} {'HTTP':>5} {'解析':>5} {'耗时':>8} {'结论'}")
    print("-" * 65)
    for mode, r in results.items():
        http = "200" if r["status"] != "http_error" else "ERR"
        parse = "✅" if r["parsed"] else "❌"
        elapsed = f"{r['elapsed']:.2f}s"
        if r["parsed"]:
            if r["status"] == "ok":
                conclusion = "直接可用"
            else:
                conclusion = "需哨兵提取"
        else:
            conclusion = "不可用"
        print(f"{mode:<25} {http:>5} {parse:>5} {elapsed:>8} {conclusion}")

    print(f"\n💡 建议:")
    if results["structured-output"]["parsed"]:
        print("  → 使用 structured-output 策略")
    elif results["json-mode"]["parsed"]:
        print("  → 使用 json-mode 策略")
    else:
        print("  → 使用 prompt-only 策略（哨兵提取 + 重试）")


if __name__ == "__main__":
    main()