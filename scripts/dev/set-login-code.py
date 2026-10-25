#!/usr/bin/env python3
"""向 Redis 写入一个"已通过"的手机验证码，免去本地依赖短信通道。

用法：
    python scripts/dev/set-login-code.py --mobile 13800000000 --code 1234

为什么要写成 `"1"`（带双引号）：
登录接口用 RedisTemplate 读取，value 序列化器是 GenericFastJsonRedisSerializer，
写入裸字符串 1 会被反序列化成 Integer 1，`Objects.equals("1", value)` 判定失败，
接口返回「验证码输入错误」。只有写入 JSON 字符串 `"1"` 才能被反序列化为 String。
"""
import argparse
import socket


def cmd(sock, *args):
    payload = f"*{len(args)}\r\n".encode()
    for a in args:
        b = str(a).encode()
        payload += b"$" + str(len(b)).encode() + b"\r\n" + b + b"\r\n"
    sock.sendall(payload)
    return sock.recv(4096).decode(errors="replace").strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=6379)
    ap.add_argument("--prefix", default="zeroai:mobile_code:")
    ap.add_argument("--mobile", required=True)
    ap.add_argument("--code", default="1234")
    ap.add_argument("--ttl", default="600")
    args = ap.parse_args()

    key = f"{args.prefix}{args.mobile}_{args.code}"
    with socket.create_connection((args.host, args.port), timeout=5) as s:
        print("PING:", cmd(s, "PING"))
        print(f"SET {key} ->", cmd(s, "SET", key, '"1"', "EX", args.ttl))


if __name__ == "__main__":
    main()
