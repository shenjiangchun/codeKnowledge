# 子系统 F + 附录：独立解密脚本 + 业务方扫描

> **本文档覆盖**：
> - 子系统 F：独立解密脚本（不依赖 HiSi DevTool 解密采集信息）
> - 附录：业务方代码现状扫描结果（55 仓库，2026-07-01）
>
> **代码位置**：D:\codeknowledge\scripts\decrypt\hisi-capture-decrypt.py

---

## 1. 子系统 F：独立解密脚本

### 1.1 设计目标

不依赖 HiSi DevTool 项目，单独可解密采集信息，方便：
- 业务方在生产环境直接排查（运维人员拿到私钥即可）
- 日志归档后离线解密
- 第三方审计

### 1.2 私钥分发约束

| 项 | 说明 |
|----|------|
| 私钥生成 | 一次性生成，长周期不变（静态非对称方案） |
| 私钥存储 | codeknowledge 项目内部配置，不发布到业务方 |
| 私钥分发 | 由 codeknowledge 运维人员管控，业务方需要离线解密时由运维提供或代为解密 |
| 公钥分发 | 硬编码在 hisi-capture-spring-boot-starter 的 META-INF/capture-public-key.pem |

### 1.3 密文格式回顾

```
base64(rsa_wrapped_dek[256B] || iv[12B] || ciphertext || gcm_tag[16B])
```

- rsa_wrapped_dek：RSA-2048-OAEP 加密 DEK，固定 256B
- iv：AES-256-GCM 随机 IV，12B
- ciphertext：AES-256-GCM 密文，变长
- gcm_tag：AES-256-GCM 认证 tag，16B

### 1.4 完整 Python 脚本

scripts/decrypt/hisi-capture-decrypt.py：

```python
#!/usr/bin/env python3
"""
Hisi Capture Decryptor — 独立解密采集信息

混合加密方案（RSA-OAEP-2048 + AES-256-GCM）：
  1. base64 decode → rsa_wrapped_dek[256B] || iv[12B] || ct||tag
  2. RSA-OAEP-Decrypt(SK, rsa_wrapped_dek) → DEK
  3. AES-GCM-Decrypt(DEK, IV, ct||tag) → plaintext

用法：
  # 解密日志文件
  hisi-capture-decrypt --key-file /path/to/private.pem -f app.log

  # stdin 输入
  grep "HISI_CAPTURE_BEGIN" app.log | hisi-capture-decrypt --key-b64 $KEY_B64

  # 解密单个密文块
  hisi-capture-decrypt --key-file /path/to/private.pem -c "base64..."
"""
import argparse
import base64
import json
import os
import re
import sys
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def decrypt_block(enc_b64: str, sk) -> str:
    """解密单个密文块，返回明文 JSON 字符串。"""
    raw = base64.b64decode(enc_b64)
    if len(raw) < 256 + 12 + 16:
        raise ValueError(f"Ciphertext too short: {len(raw)} bytes")

    rsa_wrapped_dek = raw[:256]
    iv = raw[256:268]
    ct_tag = raw[268:]

    # RSA-OAEP 解密 DEK
    dek = sk.decrypt(
        rsa_wrapped_dek,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )

    # AES-GCM 解密数据
    aesgcm = AESGCM(dek)
    pt = aesgcm.decrypt(iv, ct_tag, None)
    return pt.decode("utf-8")


def process_log_line(line: str, sk) -> str:
    """处理单行日志：识别 HISI_CAPTURE_BEGIN...END 并解密。"""
    pattern = re.compile(r"HISI_CAPTURE_BEGIN(\{.*?\})HISI_CAPTURE_END", re.DOTALL)
    matches = list(pattern.finditer(line))
    if not matches:
        return line

    for m in matches:
        try:
            payload = json.loads(m.group(1))
            meta = payload.get("meta", {})
            enc = payload.get("enc", {})

            out = {"alg": payload.get("alg"), "meta": meta, "decrypted": {}}
            for k, v in enc.items():
                try:
                    out["decrypted"][k] = json.loads(decrypt_block(v, sk))
                except Exception as e:
                    out["decrypted"][k] = f"[DECRYPT_FAILED: {e}]"

            replacement = "HISI_CAPTURE_BEGIN" + \
                json.dumps(out, ensure_ascii=False, indent=2) + "HISI_CAPTURE_END"
            line = line.replace(m.group(0), replacement)
        except Exception as e:
            line = line + f"\n[PARSE_FAILED: {e}]"
    return line


def load_private_key(key_file: str = None, key_b64: str = None):
    """从文件或 base64 加载 PEM 私钥。"""
    if key_file:
        with open(key_file, "rb") as f:
            return serialization.load_pem_private_key(f.read(), password=None)
    if key_b64:
        pem = base64.b64decode(key_b64)
        return serialization.load_pem_private_key(pem, password=None)
    return None


def main():
    parser = argparse.ArgumentParser(
        description="Hisi Capture Decryptor — 独立解密采集信息"
    )
    parser.add_argument(
        "--key-file",
        help="PEM 私钥文件路径",
    )
    parser.add_argument(
        "--key-b64",
        help="PEM 私钥 base64 编码（环境变量传递更安全）",
    )
    parser.add_argument(
        "-f", "--file",
        help="待解密日志文件路径",
    )
    parser.add_argument(
        "-c", "--cipher",
        help="单个密文块（base64）",
    )
    parser.add_argument(
        "-o", "--output",
        help="输出文件路径（默认 stdout）",
    )
    args = parser.parse_args()

    # 优先级：命令行参数 > 环境变量
    key_file = args.key_file or os.getenv("HISI_CAPTURE_PRIVATE_KEY_FILE")
    key_b64 = args.key_b64 or os.getenv("HISI_CAPTURE_PRIVATE_KEY_B64")

    sk = load_private_key(key_file, key_b64)
    if sk is None:
        sys.exit(
            "Missing private key: use --key-file or --key-b64, "
            "or set HISI_CAPTURE_PRIVATE_KEY_FILE / HISI_CAPTURE_PRIVATE_KEY_B64 env var"
        )

    # 输出目标
    out = open(args.output, "w", encoding="utf-8") if args.output else sys.stdout

    try:
        if args.cipher:
            # 解密单个密文块
            result = decrypt_block(args.cipher, sk)
            out.write(result)
            out.write("\n")
        elif args.file:
            # 解密整个日志文件
            with open(args.file, encoding="utf-8") as f:
                for line in f:
                    out.write(process_log_line(line, sk))
        else:
            # stdin 输入
            for line in sys.stdin:
                out.write(process_log_line(line, sk))
    finally:
        if args.output:
            out.close()


if __name__ == "__main__":
    main()
```

### 1.5 用法示例

```bash
# 1. 解密单个日志文件（私钥文件方式）
hisi-capture-decrypt --key-file /etc/hisi/capture-private.pem -f app.log

# 2. 解密日志文件并输出到指定文件
hisi-capture-decrypt --key-file /etc/hisi/capture-private.pem \
    -f app.log -o app-decrypted.log

# 3. stdin 输入（私钥 base64 方式，便于 CI/CD 环境变量传递）
export HISI_CAPTURE_PRIVATE_KEY_B64=$(base64 -w0 /etc/hisi/capture-private.pem)
grep "HISI_CAPTURE_BEGIN" app.log | hisi-capture-decrypt

# 4. 解密单个密文块
hisi-capture-decrypt --key-file /etc/hisi/capture-private.pem \
    -c "AAAB...base64..."

# 5. pipeline 解密
cat app.log | hisi-capture-decrypt --key-file /etc/hisi/capture-private.pem | less
```

### 1.6 解密后输出样例

```
2026-07-01 10:23:45 ERROR [order-svc,550e8400-e29b-41d4-a716-446655440000]
  c.example.OrderController - createOrder failed
java.lang.NullPointerException: ...
HISI_CAPTURE_BEGIN{
  "alg": "hybrid-rsa-aes-gcm",
  "meta": {
    "tag": "550e8400-e29b-41d4-a716-446655440000",
    "uri": "/api/orders",
    "method": "com.example.OrderService.create",
    "ts": 1719804225123
  },
  "decrypted": {
    "entry": {
      "entry": {
        "tag": "550e8400-e29b-41d4-a716-446655440000",
        "type": "HTTP",
        "uri": "/api/orders",
        "params": {
          "uri": "/api/orders",
          "method": "POST",
          "headers": {"Authorization": "***REDACTED***", "Content-Type": "application/json"},
          "body": "{\"orderId\":\"A001\",\"qty\":2}"
        }
      }
    },
    "spans": {
      "spans": [
        {"sig": "OrderService.create(OrderReq)", "args": [{"orderId":"A001","qty":2}], "exc": "NullPointerException: ..."},
        {"sig": "InventoryMapper.deduct(String)", "args": ["A001"], "ret": 0},
        {"sig": "PaymentClient.charge(OrderReq)", "args": [{"orderId":"A001","qty":2}], "ret": 200}
      ]
    },
    "feign": {
      "feign": [
        {"url": "http://payment-svc/charge", "params": {"amount": 200}, "status": 200, "dur": 45}
      ]
    }
  }
}HISI_CAPTURE_END
```

### 1.7 依赖

```bash
pip install cryptography>=42.0.0
```

无其他依赖，纯 Python 标准库 + cryptography。

---

## 2. 附录：业务方代码现状扫描结果（2026-07-01）

> 对 D:\auto_generated 下 55 个业务方仓库（业务方全量代码）扫描，结果用于采集 SDK MVP 范围收敛与改造点定位。
>
> **扫描约束**：只读不改（subagents instructed "只读不改"）。

### 2.1 技术栈分布

| 项 | 结果 |
|----|------|
| 总仓库数 | 55 |
| Java Spring Boot 仓库 | ~31 |
| Vue 前端仓库 | ~21 |
| 其他（脚本/工具） | ~3 |
| 主流 Spring Boot 版本 | 3.5.14（via icanal-parent 3.4.9-SNAPSHOT） |
| 主流 Java 版本 | 21（jakarta.servlet） |
| 少量旧版 | SB 2.5.14（himeeting-redirect1/2/3、deliverable-management-hiapm） |
| 旧版 2.x | sgovernance-invoke、task-management-service（via icanal-parent 2.9.5-SNAPSHOT） |

**含义**：采集 SDK 需兼容 SB 2.x（javax.servlet）与 3.x（jakarta.servlet），通过 spring.factories + AutoConfiguration.imports 双注册。

### 2.2 入口类型实测分布

| 入口类型 | 是否存在 | 频度 | 处理策略 |
|---------|---------|------|---------|
| HTTP（Spring MVC Controller） | ✅ 所有 Java 仓库 | 主入口 | MVP 必须支持 |
| @Async | ✅ 普遍存在 | task-trace-management-service 58 处、hiapm 35 处、ams 21 处、task-management-service 18 处 | MVP 必须支持 |
| @Scheduled | ✅ 普遍存在 | project-basic-information-management 22 处 | MVP 必须支持 |
| FeignClient（出向） | ✅ 极密集 | project-basic-information-management 129 处、hiapm 80 处、hiapm-openapi 58 处 | MVP 必须支持（出向传播 entryTag） |
| @RabbitListener | ❌ 无 | — | MVP 不实现 |
| @KafkaListener | ❌ 无 | rms2-service 有注释掉的 @KafkaListener，未启用 | MVP 不实现 |
| gRPC | ❌ 无 | — | MVP 不实现 SPI |
| WebSocket | ❌ 无 | — | MVP 不实现 SPI |
| Netty | ❌ 无 | — | MVP 不实现 SPI |
| 其他自定义入口 | ❌ 无 | — | SPI 接口预留，不写实现 |

**结论**：MVP 入口层只需实现 HTTP / @Async / @Scheduled + Feign 出向拦截。Rabbit/Kafka/gRPC/WebSocket/Netty SPI 接口预留但不写实现，因为业务方根本不用。

### 2.3 线程池使用现状

**关键发现**：所有 Java 业务仓库使用**统一命名**的 ExecutorPoolConfig Bean：

| 仓库 | 路径 |
|------|------|
| activity-management-service | com.hisilicon.ams.basic.config.ExecutorPoolConfig |
| hiapm | com.hisilicon.autopmweb.basic.config.ExecutorPoolConfig |
| project-basic-information-management | com.hisilicon.pbim.basic.config.ExecutorPoolConfig |
| 其他业务仓库 | 同 pattern：com.hisilicon.<module>.basic.config.ExecutorPoolConfig |

**实现模式统一**：
- ThreadPoolTaskExecutor（Spring Bean）
- CallerRunsPolicy 或 DiscardPolicy
- 通过 @Configuration 暴露为 Bean

**含义**：
- ✅ 业务方线程池 100% 通过 Spring Bean 暴露 → 决策 1 默认实现 (c) BeanPostProcessor 自动包装可行
- ✅ 业务方零代码改动即可获得 TTL 传播
- ⚠️ 需确认：是否有业务方在代码里 new ThreadPoolExecutor 直用（不走 Bean），需静态扫描告警

### 2.4 已有 APM 现状

| 项 | 结果 |
|----|------|
| OTel Agent | ❌ 无 |
| SkyWalking Agent | ❌ 无 |
| Pinpoint Agent | ❌ 无 |
| 自研 APM | ❌ 无 |

**含义**：业务方是 APM 空白，无既有 agent 冲突：hisi-capture-spring-boot-starter 可零冲突接入。

### 2.5 代表性仓库指标

| 仓库 | Java 文件数 | @Scheduled | @Async | FeignClient |
|------|-----------|-----------|--------|-------------|
| project-basic-information-management | 918 | 22 | — | 129 |
| hiapm | — | — | 35 | 80 |
| hiapm-openapi | — | — | — | 58 |
| task-trace-management-service | — | — | 58 | — |
| ams (activity-management-service) | — | — | 21 | — |
| task-management-service | — | — | 18 | — |

### 2.6 MVP 范围收敛结论

基于扫描结果，MVP 范围：

| 子系统 | MVP 范围 | 推迟到 v2 |
|--------|---------|----------|
| 入口层 | HTTP + @Async + @Scheduled + Feign 出向 | Rabbit/Kafka/gRPC/WebSocket/Netty SPI |
| 线程池改造 | BeanPostProcessor 自动包装业务方 ExecutorPoolConfig Bean | 静态扫描告警 new ThreadPoolExecutor 直用场景 |
| 加密 | 静态非对称（RSA-OAEP-2048 + AES-256-GCM） | — |
| 异常出口 | HTTP ControllerAdvice + AsyncUncaughtExceptionHandler + ErrorHandler + 兜底 1/2/3 | RabbitListenerErrorHandler / KafkaListenerErrorHandler |

**工作量影响**：相对原评估，MVP 减少 Rabbit/Kafka 入口 + SPI 实现 → 节省 ~2 天。

### 2.7 业务代码改造场景清单（零代码改动）

| 场景 | 是否需改业务代码 | 说明 |
|------|----------------|------|
| HTTP 入口 | ❌ | 自动配置 HttpCaptureFilter |
| @Async | ❌ | 自动配置 AsyncAspect + TaskDecorator |
| @Scheduled | ❌ | 自动配置 ErrorHandler |
| Feign 出向 | ❌ | 自动配置 RequestInterceptor |
| 线程池 | ❌（决策 1 默认 (c)） | BeanPostProcessor 自动包装 |
| silent_catch 抓取 | 可选 @CaptureLog | 不加注解也能用兜底 3（默认开） |
| 自定义入口（gRPC/WS） | 需实现 SPI | MVP 不写实现，业务方暂不用 |

**结论**：业务方零代码改动，只需 Maven 引入 starter + 配置 hisi.capture.enabled=true。

### 2.8 SDK 部署形态

**形态**：Spring Boot Starter + 二方件（发布到内部 Nexus）

**业务方引入方式**：

```xml
<dependency>
    <groupId>com.hisi</groupId>
    <artifactId>hisi-capture-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```yaml
# application.yml
hisi:
  capture:
    enabled: true
    crypto:
      enabled: true
```

**发版频率**：业务方一个月好几次发版，引入 starter 无运维负担。
