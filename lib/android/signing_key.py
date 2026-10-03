#!/usr/bin/env python3
"""Keep the existing signing identity while securing its local keystore."""
import fcntl
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import uuid


class SigningError(RuntimeError):
    pass


def secure(file):
    if file.is_symlink() or not file.is_file():
        raise SigningError("签名资料必须是普通文件，不能使用符号链接。")
    file.chmod(0o600)


def invoke(keytool, *args):
    result = subprocess.run([str(keytool), *map(str, args)], capture_output=True)
    if result.returncode:
        raise SigningError("签名密钥校验或转换失败；已有密钥保留，请检查密码文件。")
    return result.stdout


def certificate(keytool, key, password):
    return invoke(keytool, "-exportcert", "-alias", "adbkeep", "-keystore", key, "-storepass:file", password)


def secret_file(file, text):
    with file.open("x") as stream:
        stream.write(text + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    secure(file)


def sync_directory(directory):
    fd = os.open(directory, os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)


def prepare(directory, legacy, keytool="keytool", external_password=None):
    directory = Path(directory).expanduser()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    old_umask = os.umask(0o077)
    try:
        fd = os.open(directory / ".signing.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "r+") as lock:
            os.fchmod(lock.fileno(), 0o600)
            fcntl.flock(lock, fcntl.LOCK_EX)
            return prepare_locked(directory, Path(legacy), keytool, external_password)
    finally: os.umask(old_umask)


def prepare_locked(directory, legacy, keytool, external_password):
    key = directory / "adb-keep.keystore"
    password = Path(external_password).expanduser() if external_password else directory / "adb-keep.password"
    if legacy.is_file(): secure(legacy)
    if not key.exists() and legacy.is_file():
        if key.is_symlink(): raise SigningError("不能覆盖已有密钥链接。")
        shutil.copyfile(legacy, key)
    if key.exists(): secure(key)
    if password.exists(): secure(password)
    elif external_password: raise SigningError("指定的签名密码文件不存在。")
    else: secret_file(password, "android" if key.exists() else secrets.token_hex(32))
    if not key.exists():
        if key.is_symlink(): raise SigningError("不能覆盖已有密钥链接。")
        invoke(keytool, "-genkeypair", "-keystore", key, "-storepass:file", password,
               "-keypass:file", password, "-alias", "adbkeep", "-keyalg", "RSA", "-keysize", "2048",
               "-validity", "10000", "-dname", "CN=Phone Station Adb Keep, O=Phone Station, C=CN")
        secure(key)
    if external_password:
        certificate(keytool, key, password)
        return key, password

    suffix = uuid.uuid4().hex
    old_pass = directory / (".legacy-" + suffix)
    new_pass = directory / (".password-" + suffix)
    new_key = directory / (".keystore-" + suffix)
    try:
        # Recovery also covers interruption after password replacement but before keystore replacement.
        try:
            before = certificate(keytool, key, password)
            legacy_password = password.read_text().strip() == "android"
        except SigningError:
            secret_file(old_pass, "android")
            before = certificate(keytool, key, old_pass)
            legacy_password = True
        if legacy_password:
            if not old_pass.exists(): secret_file(old_pass, "android")
            secret_file(new_pass, secrets.token_hex(32))
            invoke(keytool, "-importkeystore", "-noprompt", "-srckeystore", key,
                   "-srcstorepass:file", old_pass,
                   "-destkeystore", new_key, "-deststoretype", "PKCS12",
                   "-deststorepass:file", new_pass, "-destkeypass:file", new_pass)
            secure(new_key)
            if certificate(keytool, new_key, new_pass) != before:
                raise SigningError("转换后的签名证书不一致，保留原密钥。")
            os.replace(new_pass, password)
            sync_directory(directory)
            os.replace(new_key, key)
            sync_directory(directory)
        secure(key)
        secure(password)
        return key, password
    finally:
        for file in (old_pass, new_pass, new_key): file.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        key, password = prepare(sys.argv[1], sys.argv[2], external_password=os.environ.get("PHONE_STATION_KEY_PASS_FILE"))
        print(key)
        print(password)
    except (SigningError, OSError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
