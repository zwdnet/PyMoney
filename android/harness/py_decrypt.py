#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 App 加密过的 money.db 还原成一份明文临时库，给对拍用。

这不是「后门」：口令仍然要人给，算法只是公开的 AES-256-GCM + PBKDF2-HMAC-SHA256，
信封躺在库里的 AppCrypto 表。它存在的意义是跨实现自检——
Python 能独立解开 Java 写的密文，才说明密文格式里没有只有 App 认得的私有暗坑，
Termux 那边、以及「App 万一没了」的时候，账本不至于变砖。

    python3 harness/py_decrypt.py <加密的.db> <口令> <输出的明文.db>
"""
import base64
import hashlib
import os
import shutil
import sqlite3
import sys

PREFIX = "enc:v1:"
ALGO_KEY, ITER_KEY, SALT_KEY, DEK_KEY, VERIFY_KEY, RECOVERY_KEY = (
    "algo", "iter", "salt", "dek", "verify", "recovery")
WRAP_AAD = b"pymoney.dek-wrap"
VERIFY_AAD = b"pymoney.verify"
VERIFY_PLAIN = b"pymoney.key-check.v1"
INCOME_NAME = b"Income.Name"
TYPE_NAME = b"IncomeType.TypeName"
IV_LEN = 12
SALT_LEN = 16


def b64decode(text):
    """和 Java 的 B64 一样严格：非表内字符、padding 不对都算坏值"""
    return base64.b64decode(text, validate=True)


def pbkdf2(password, salt, iterations, length=32):
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations, length)


def gcm_decrypt(key, iv, aad, packed):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).decrypt(iv, packed, aad)


def gcm_encrypt(key, iv, aad, plain):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).encrypt(iv, plain, aad)


def load_envelope(db):
    rows = db.execute("SELECT k, v FROM AppCrypto").fetchall()
    values = dict(rows)
    if not values or values.get(DEK_KEY) is None or values.get(SALT_KEY) is None:
        raise SystemExit("这个库没有密钥信封（AppCrypto 是空的），本来就是明文")
    return {
        "iterations": int(values[ITER_KEY]),
        "salt": b64decode(values[SALT_KEY]),
        "wrapped": b64decode(values[DEK_KEY]),
        "verify": b64decode(values[VERIFY_KEY]),
        "recovery": b64decode(values[RECOVERY_KEY]) if values.get(RECOVERY_KEY) else None,
    }


def unwrap(envelope, passphrase, use_recovery=False):
    """先派生 KEK 取出 DEK，再用 DEK 验那个固定串——串对上了才算口令对"""
    if use_recovery:
        blob = envelope["recovery"]
        if blob is None:
            raise SystemExit("这个库没设恢复码")
        # Java 那边是 salt(16) ‖ iv(12) ‖ 密文 ‖ tag(16)
        key = pbkdf2(passphrase, blob[:SALT_LEN], envelope["iterations"])
        packed = blob[SALT_LEN:]
    else:
        key = pbkdf2(passphrase, envelope["salt"], envelope["iterations"])
        packed = envelope["wrapped"]
    dek = gcm_decrypt(key, packed[:IV_LEN], WRAP_AAD, packed[IV_LEN:])
    verify = envelope["verify"]
    if gcm_decrypt(dek, verify[:IV_LEN], VERIFY_AAD, verify[IV_LEN:]) != VERIFY_PLAIN:
        raise SystemExit("口令能取出密钥但校验串不对，说明信封被改过")
    return dek


def decrypt_field(dek, stored, aad):
    if stored is None or not stored.startswith(PREFIX):
        return stored                     # 混合态：还没迁的行照常是明文
    blob = b64decode(stored[len(PREFIX):])
    if len(blob) <= IV_LEN:
        raise SystemExit("密文长度不对：%r" % (stored[:24],))
    return gcm_decrypt(dek, blob[:IV_LEN], aad, blob[IV_LEN:]).decode("utf-8")


def main(db_path, passphrase, out_path):
    shutil.copyfile(db_path, out_path)
    db = sqlite3.connect(out_path)
    envelope = load_envelope(db)
    dek = unwrap(envelope, passphrase, passphrase_is_recovery(passphrase))

    for type_id, stored in db.execute(
            "SELECT TypeID, TypeName FROM IncomeType WHERE TypeName LIKE 'enc:v1:%'"):
        name = decrypt_field(dek, stored, TYPE_NAME)
        db.execute("UPDATE IncomeType SET TypeName = ? WHERE TypeID = ?", (name, type_id))
    for row_id, stored in db.execute("SELECT ID, Name FROM Income WHERE Name LIKE 'enc:v1:%'"):
        name = decrypt_field(dek, stored, INCOME_NAME)
        db.execute("UPDATE Income SET Name = ? WHERE ID = ?", (name, row_id))
    db.execute("DELETE FROM AppCrypto")
    db.commit()

    left = db.execute("SELECT COUNT(*) FROM Income WHERE Name LIKE 'enc:v1:%'").fetchone()[0]
    total = db.execute("SELECT COUNT(*) FROM Income").fetchone()[0]
    db.close()
    if left:
        raise SystemExit("还留着 %d 条没解开的密文" % left)
    print("ok|python 解开 %d 条|%s" % (total, os.path.basename(out_path)))


def passphrase_is_recovery(passphrase):
    """恢复码长得很规整（RECOV-xxxx-xxxx），用它自己的包，不动主口令那条路"""
    return passphrase.startswith("RECOV-")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print(__doc__.strip(), file=sys.stderr)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2], sys.argv[3])
