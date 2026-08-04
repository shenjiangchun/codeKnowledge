# Hisi Capture Decryptor

独立解密采集信息脚本，不依赖 HiSi DevTool 项目。

## 依赖

```bash
pip install -r requirements.txt
```

仅依赖 `cryptography>=42.0.0`，无其他第三方依赖。

## 用法

### 1. 解密单个日志文件（私钥文件方式）

```bash
python hisi-capture-decrypt.py --key-file /etc/hisi/capture-private.pem -f app.log
```

### 2. 解密日志文件并输出到指定文件

```bash
python hisi-capture-decrypt.py --key-file /etc/hisi/capture-private.pem \
    -f app.log -o app-decrypted.log
```

### 3. stdin 输入（私钥 base64 方式，便于 CI/CD 环境变量传递）

```bash
export HISI_CAPTURE_PRIVATE_KEY_B64=$(base64 -w0 /etc/hisi/capture-private.pem)
grep "HISI_CAPTURE_BEGIN" app.log | python hisi-capture-decrypt.py
```

### 4. 解密单个密文块

```bash
python hisi-capture-decrypt.py --key-file /etc/hisi/capture-private.pem \
    -c "AAAB...base64..."
```

### 5. pipeline 解密

```bash
cat app.log | python hisi-capture-decrypt.py --key-file /etc/hisi/capture-private.pem | less
```

## 参数说明

| 参数 | 说明 |
|------|------|
| `--key-file` | PEM 私钥文件路径 |
| `--key-b64` | PEM 私钥 base64 编码（适合环境变量传递） |
| `-f, --file` | 待解密日志文件路径 |
| `-c, --cipher` | 单个密文块（base64） |
| `-o, --output` | 输出文件路径（默认 stdout） |

## 环境变量

| 变量 | 说明 |
|------|------|
| `HISI_CAPTURE_PRIVATE_KEY_FILE` | PEM 私钥文件路径（等同 `--key-file`） |
| `HISI_CAPTURE_PRIVATE_KEY_B64` | PEM 私钥 base64 编码（等同 `--key-b64`） |

命令行参数优先级高于环境变量。

## 密文格式

```
base64(rsa_wrapped_dek[256B] || iv[12B] || ciphertext || gcm_tag[16B])
```

- RSA-2048-OAEP 加密 DEK，固定 256B
- AES-256-GCM 随机 IV，12B
- AES-256-GCM 密文，变长
- AES-256-GCM 认证 tag，16B
