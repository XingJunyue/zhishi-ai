#!/usr/bin/env python3
"""冒烟测试用的假 OpenAI 兼容接口（不是真的模型）：只认 /v1/models 和 /v1/chat/completions。
- 带图片的请求 → 固定的课表 JSON（含周次、单双周）
- 最后一条是工具结果 → 一句文字（AI 的改动马上生效：「已改好」；出错：「好的…没找到」）；
  用户说「顺便」且刚加了课 → 第二轮用返回的 id 改这门课的教室 + 加一条待办（测一轮多处改动、撤销的依赖）
- 带引用（「[用户在回复这条消息…」开头）→ 回一句带着引用原文的话
- 用户说「机器学习」→ add_course；「待办」→ add_todo；「皮肤」→ set_skin；「删」→ delete_course；其他 → 回答周二的课
- 「AI 帮我找课表」：按 tools/fake_jw/ 的页面走剧本（见 jw_step）
用法：fake_ai.py 端口"""
import json, re, sys, time
from http.server import BaseHTTPRequestHandler, HTTPServer

COURSES = {"courses": [
    {"name": "高等数学", "teacher": "张伟", "room": "A101", "weekday": 1, "start_period": 1, "end_period": 2, "weeks": "1-16", "odd_even": "all"},
    {"name": "大学物理", "teacher": "李娜", "room": "理B305", "weekday": 1, "start_period": 5, "end_period": 6, "weeks": "1-15", "odd_even": "odd"},
    {"name": "大学英语", "teacher": "王芳", "room": "外302", "weekday": 2, "start_period": 3, "end_period": 4, "weeks": "2-16", "odd_even": "even"},
    {"name": "线性代数", "teacher": "赵强", "room": "教2-108", "weekday": 3, "start_period": 1, "end_period": 2, "weeks": "1-8", "odd_even": "all"},
]}


def reply(msg, finish="stop"):
    return {"id": "fake", "object": "chat.completion", "choices": [{"index": 0, "message": msg, "finish_reason": finish}]}


def call(name, args, *more):
    calls = [(name, args)] + list(more)
    return reply({"role": "assistant", "content": None, "tool_calls": [
        {"id": "call_%d" % i, "type": "function", "function": {"name": n, "arguments": json.dumps(a, ensure_ascii=False)}} for i, (n, a) in enumerate(calls, 1)]}, "tool_calls")


# 「AI 帮我找课表」（系统提示以 "You operate an in-app browser" 开头）：照 tools/fake_jw/ 的页面按剧本操作网页
JW_KB = {"courses": [
    {"name": "操作系统", "teacher": "王老师", "room": "B201", "weekday": 1, "start_period": 1, "end_period": 2, "weeks": "1-16", "odd_even": "all"},
    {"name": "操作系统", "teacher": "王老师", "room": "B201", "weekday": 5, "start_period": 1, "end_period": 2, "weeks": "1-16", "odd_even": "all"},
    {"name": "数据库原理", "teacher": "赵老师", "room": "A110", "weekday": 2, "start_period": 3, "end_period": 4, "weeks": "2-16", "odd_even": "even"},
    {"name": "计算机网络", "teacher": "李老师", "room": "C305", "weekday": 3, "start_period": 1, "end_period": 2, "weeks": "1-15", "odd_even": "odd"},
    {"name": "体育", "teacher": "孙老师", "room": "东操场", "weekday": 3, "start_period": 5, "end_period": 6, "weeks": "1-16", "odd_even": "all"},
    {"name": "软件工程", "teacher": "钱老师", "room": "D402", "weekday": 4, "start_period": 3, "end_period": 4, "weeks": "1-12", "odd_even": "all"},
]}


def jw_item(page, pattern):
    m = re.search(r"\[(\d+)\] " + pattern, page)
    return int(m.group(1)) if m else None


def jw_step(msgs):
    """剧本：登录页 → 想点密码框（App 必须拦下）→ need_login；登录后：读页 → 点错「通知公告」→ 读 → 返回 → 往下滚 → 读 → 点「我的课表」→ 读 → 截图 → 文字 JSON（带围栏和末尾逗号）。"""
    time.sleep(1.2)  # 让冒烟测试截得到进度
    names = [tc["function"]["name"] for m in msgs if m.get("role") == "assistant" for tc in (m.get("tool_calls") or [])]
    tools = [m.get("content") or "" for m in msgs if m.get("role") == "tool"]
    last = msgs[-1] if msgs else {}
    lt = (last.get("content") or "") if last.get("role") == "tool" else ""
    if not names: return call("read_page", {})
    if last.get("role") != "tool":
        return reply({"role": "assistant", "content": "```json\n" + json.dumps(JW_KB, ensure_ascii=False) + "\n```"})
    ln = names[-1]
    if any("type the password" in t for t in tools): return reply({"role": "assistant", "content": "oops"})
    if ln == "read_page":
        pw = jw_item(lt, "password field")
        if pw is not None:
            if not any(t.startswith("refused") for t in tools): return call("click", {"n": pw})
            return call("need_login", {"message": "请先登录"})
        if "通知公告" in lt.split("Clickable items:")[0]: return call("back", {})
        if "星期一" in lt: return call("screenshot", {})
        kb = jw_item(lt, "link: 我的课表")
        if kb is not None and "back" in names: return call("click", {"n": kb})
        nt = jw_item(lt, "link: 通知公告")
        if nt is not None: return call("click", {"n": nt})
        return call("not_found", {"reason": "fake: no menu"})
    if ln == "click":
        if lt.startswith("refused"):
            # 被拦下以后再试一次「输入密码」类的工具：也必须被拦下
            if not any(n not in ("read_page", "click", "need_login") for n in names): return call("type_text", {"n": 2, "text": "123456"})
            return call("need_login", {"message": "请先登录"})
        return call("read_page", {})
    if ln == "type_text": return call("need_login", {"message": "请先登录"})
    if ln == "back": return call("scroll", {"direction": "down"})
    if ln == "scroll": return call("read_page", {})
    if ln == "screenshot":
        body = json.dumps(JW_KB, ensure_ascii=False)
        return reply({"role": "assistant", "content": "找到了，这是你的课表：\n```json\n" + body[:-2] + ",]}\n```"})
    return call("not_found", {"reason": "fake: unexpected"})


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
        sysm = msgs[0].get("content") if msgs and isinstance(msgs[0].get("content"), str) else ""
        if sysm.startswith("You operate an in-app browser") and body.get("tools"): return self.send(200, jw_step(msgs))
        has_img = any(isinstance(m.get("content"), list) and any(p.get("type") == "image_url" for p in m["content"]) for m in msgs)
        if has_img:
            # 看图测试图（AI 设置的「测试连接」）只问字
            if "What word" in json.dumps(msgs, ensure_ascii=False): return self.send(200, reply({"role": "assistant", "content": "OK"}))
            return self.send(200, reply({"role": "assistant", "content": "```json\n" + json.dumps(COURSES, ensure_ascii=False) + "\n```"}))
        last = msgs[-1] if msgs else {}
        if last.get("role") == "tool":
            users = [m.get("content") for m in msgs if m.get("role") == "user" and isinstance(m.get("content"), str)]
            u = users[-1] if users else ""
            tool = last.get("content") or ""
            if tool.startswith("error"): return self.send(200, reply({"role": "assistant", "content": "好的，没找到那门课，没有改动。"}))
            if "顺便" in u and "added course id=" in tool:
                cid = tool.split("added course id=", 1)[1].split()[0].strip(" ()")
                return self.send(200, call("update_course", {"id": cid, "room": "B303"}, ("add_todo", {"title": "预习机器学习", "due": "2030-01-01 20:00"})))
            return self.send(200, reply({"role": "assistant", "content": "已改好。"}))
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
