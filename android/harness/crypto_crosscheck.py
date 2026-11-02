#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Java 加密核心的交叉验证：拿 Python 的 hashlib / cryptography 去解 Java 写出来的东西。
两边实现独立，能互解就说明密文格式没有私有暗坑——Termux 那边的 pymoney_dump.py 才敢照这个格式写。
用法：python3 harness/crypto_crosscheck.py build/compare/crypto_vector.txt
"""
import base64
import binascii
import hashlib
import sys

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

failures = 0


def check(label, want, got):
    global failures
    if want == got:
        print("ok|%s|%s" % (label, got if len(str(got)) < 48 else str(got)[:48] + "…"))
    else:
        failures += 1
        print("FAIL|%s| 期望 %s 实得 %s" % (label, want, got))


def main(path):
    global failures
    lines = open(path, encoding="utf-8").read().splitlines()
    for line in lines:
        if not line.strip():
            continue
        parts = line.split("|")
        kind = parts[0]
        if kind == "b64":
            check("B64|java 编的等于 python 编的|%s" % parts[1][:12], parts[2],
                  base64.b64encode(parts[1].encode("utf-8")).decode())
            check("B64|python 编的 java 表能解|%s" % parts[1][:12], parts[1],
                  base64.b64decode(parts[2]).decode("utf-8"))
        elif kind == "pbkdf2":
            password, salt, iterations, length, expected = parts[1:6]
            got = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"),
                                      salt.encode("utf-8"), int(iterations),
                                      int(length)).hex()
            check("Pbkdf2|%s/%s/%s" % (password[:12], salt[:12], iterations), expected, got)
        elif kind == "gcm":
            key, iv, aad, plain_b64, packed_b64 = parts[1:6]
            blob = base64.b64decode(packed_b64)
            check("AesGcm|长度=IV12+密文+tag16", "true",
                  "true" if len(blob) >= 28 else "false")
            try:
                opened = AESGCM(binascii.unhexlify(key)).decrypt(
                    binascii.unhexlify(iv), blob[12:], aad.encode("utf-8"))
                check("AesGcm|python 解 java|%s" % plain_b64[:12],
                      base64.b64decode(plain_b64), opened)
            except Exception as exc:
                failures += 1
                print("FAIL|AesGcm|python 解不开 java 写的（%s）" % exc)
            # 换 AAD 必须失败
            try:
                AESGCM(binascii.unhexlify(key)).decrypt(
                    binascii.unhexlify(iv), blob[12:], b"IncomeType.TypeName")
                check("AesGcm|换 AAD 要失败", "抛异常", "解开了")
            except Exception:
                check("AesGcm|换 AAD 要失败", "抛异常", "抛异常")
            # 改一个 bit 必须失败
            flipped = bytearray(blob)
            flipped[-1] ^= 0x01
            try:
                AESGCM(binascii.unhexlify(key)).decrypt(
                    binascii.unhexlify(iv), bytes(flipped)[12:], aad.encode("utf-8"))
                check("AesGcm|篡改要失败", "抛异常", "解开了")
            except Exception:
                check("AesGcm|篡改要失败", "抛异常", "抛异常")
            # Python 加密 → Java 那种「IV 前置 + tag 在后」的包法应当一致
            sealed = AESGCM(binascii.unhexlify(key)).encrypt(
                binascii.unhexlify(iv), base64.b64decode(plain_b64), aad.encode("utf-8"))
            check("AesGcm|python 加密等于 java 的包", packed_b64,
                  base64.b64encode(binascii.unhexlify(iv) + sealed).decode())
        elif kind == "algo":
            check("算法标记是 AES-256-GCM", "true",
                  "true" if parts[1].startswith("AES-256-GCM") else "false")
    print("done|crosscheck|%d" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: crypto_crosscheck.py <向量文件>", file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
