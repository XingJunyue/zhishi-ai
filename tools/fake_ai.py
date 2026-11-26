#!/usr/bin/env python3
"""冒烟测试用的假 OpenAI 兼容接口（不是真的模型）：只认 /v1/models 和 /v1/chat/completions。
- 带图片的请求 → 固定的课表 JSON（含周次、单双周）
- 最后一条是工具结果 → 一句文字
- 带引用（「[用户在回复这条消息…」开头）→ 回一句带着引用原文的话
- 用户说「机器学习」→ add_course；「待办」→ add_todo；「皮肤」→ set_skin；「删」→ delete_course；其他 → 回答周二的课
用法：fake_ai.py 端口"""
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

COURSES = {"courses": [
    {"name": "高等数学", "teacher": "张伟", "room": "A101", "weekday": 1, "start_period": 1, "end_period": 2, "weeks": "1-16", "odd_even": "all"},
    {"name": "大学物理", "teacher": "李娜", "room": "理B305", "weekday": 1, "start_period": 5, "end_period": 6, "weeks": "1-15", "odd_even": "odd"},
    {"name": "大学英语", "teacher": "王芳", "room": "外302", "weekday": 2, "start_period": 3, "end_period": 4, "weeks": "2-16", "odd_even": "even"},
    {"name": "线性代数", "teacher": "赵强", "room": "教2-108", "weekday": 3, "start_period": 1, "end_period": 2, "weeks": "1-8", "odd_even": "all"},
]}


def reply(msg, finish="stop"):
    return {"id": "fake", "object": "chat.completion", "choices": [{"index": 0, "message": msg, "finish_reason": finish}]}


def call(name, args):
    return reply({"role": "assistant", "content": "我准备好了，请确认。", "tool_calls": [
        {"id": "call_1", "type": "function", "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}}]}, "tool_calls")


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): sys.stderr.write("fake_ai " + (a[0] % a[1:]) + "\n")

    def send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        if self.path.startswith("/v1/models"): return self.send(200, {"data": [{"id": "fake-chat"}, {"id": "fake-vision"}]})
        self.send(404, {"error": {"message": "not found"}})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); body = json.loads(self.rfile.read(n) or b"{}")
        if not self.path.startswith("/v1/chat/completions"): return self.send(404, {"error": {"message": "not found"}})
        if self.headers.get("Authorization") != "Bearer test-key": return self.send(401, {"error": {"message": "bad key"}})
        msgs = body.get("messages", [])
        has_img = any(isinstance(m.get("content"), list) and any(p.get("type") == "image_url" for p in m["content"]) for m in msgs)
        if has_img:
            # 看图测试图（AI 设置的「测试连接」）只问字
            if "What word" in json.dumps(msgs, ensure_ascii=False): return self.send(200, reply({"role": "assistant", "content": "OK"}))
            return self.send(200, reply({"role": "assistant", "content": "```json\n" + json.dumps(COURSES, ensure_ascii=False) + "\n```"}))
        last = msgs[-1] if msgs else {}
        if last.get("role") == "tool": return self.send(200, reply({"role": "assistant", "content": "好的。"}))
        text = last.get("content") if isinstance(last.get("content"), str) else ""
        if body.get("tools") is None: return self.send(200, reply({"role": "assistant", "content": "OK"}))
        # 回复某一条（引用）：回一句带着引用原文的话，冒烟测试据此确认引用真的发给了模型
        if text.startswith("[用户在回复这条消息") or text.startswith("[The user is replying"):
            q = text[text.find("「") + 1:text.find("」")] if "「" in text else text.split('"')[1]
            who = "你自己之前说的" if "用户自己之前发的" in text or "their own" in text else "我之前的回复"
            return self.send(200, reply({"role": "assistant", "content": "收到，你在回复" + who + "：「" + q[:30] + "」。" + text.split("\n", 1)[-1][:20]}))
        if "机器学习" in text: return self.send(200, call("add_course", {"name": "机器学习", "weekdays": [3], "start_period": 5, "end_period": 6, "room": "B302", "teacher": "王老师", "weeks": "1-16"}))
        if "待办" in text: return self.send(200, call("add_todo", {"title": "交线代作业", "due": "2030-01-02 22:00", "remind": True, "remind_before_minutes": 30}))
        if "皮肤" in text: return self.send(200, call("set_skin", {"skin": "yehang", "mode": "dark"}))
        if "删" in text: return self.send(200, call("delete_course", {"id": "nope"}))
        self.send(200, reply({"role": "assistant", "content": "周二有**大学英语**（第3-4节）。"}))


HTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8766), H).serve_forever()
