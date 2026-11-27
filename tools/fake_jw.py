#!/usr/bin/env python3
"""冒烟测试用的假教务网站（不是真实学校）：tools/fake_jw/ 里的静态页面：登录页（学号 + 密码框）→ 首页（菜单、一长串通知，
「我的课表」在最下面、新窗口打开）→ 通知公告 / 课表页（普通表格，直接导入认不出：测「AI 帮我找课表」）。
用法：python3 tools/fake_jw.py 8767（模拟器上 adb reverse 后打开 http://127.0.0.1:8767/login.html）"""
import functools, os, sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fake_jw')

class H(SimpleHTTPRequestHandler):
    def log_message(self, *a): sys.stderr.write("fake_jw " + (a[0] % a[1:]) + "\n"); sys.stderr.flush()
    def end_headers(self):
        self.send_header('Cache-Control', 'no-store'); super().end_headers()

if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1]) if len(sys.argv) > 1 else 8767), functools.partial(H, directory=D)).serve_forever()
