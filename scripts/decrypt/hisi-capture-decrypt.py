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
