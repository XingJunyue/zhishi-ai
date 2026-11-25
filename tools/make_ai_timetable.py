# 生成一张带教室、老师、周次、单双周的课表截图（AI 截图识别的测试图）
# 用法：make_ai_timetable.py out.png [字体文件]
import sys, glob
from PIL import Image, ImageDraw, ImageFont

fp = sys.argv[2] if len(sys.argv) > 2 else (glob.glob('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc') + glob.glob('/usr/share/fonts/**/NotoSans*CJK*', recursive=True))[0]
f = lambda s: ImageFont.truetype(fp, s)
W, H, left, top, colw, rowh = 1080, 1700, 90, 160, 141, 120
img = Image.new('RGB', (W, H), (250, 250, 250))
d = ImageDraw.Draw(img)
for i, t in enumerate(['周一', '周二', '周三', '周四', '周五', '周六', '周日']):
    d.text((left + i * colw + colw // 2, top - 50), t, font=f(30), fill=(60, 60, 60), anchor='mm')
for n in range(1, 13):
    d.text((left // 2, top + (n - 1) * rowh + rowh // 2), str(n), font=f(28), fill=(90, 90, 90), anchor='mm')
# (星期, 第几节, 几节, 课名, 教室, 老师, 周次)
C = [(1, 1, 2, '高等数学', 'A101', '张伟', '1-16周'), (1, 5, 2, '大学物理', '理B305', '李娜', '1-15周(单)'),
     (2, 3, 2, '大学英语', '外302', '王芳', '2-16周(双)'), (3, 1, 2, '线性代数', '教2-108', '赵强', '1-8周'),
     (4, 7, 2, '程序设计', '机房C3', '刘洋', '3-17周'), (5, 3, 3, '形势与政策', '大礼堂', '陈静', '9-12周')]
col = [(255, 214, 196), (198, 230, 206), (210, 220, 245), (250, 232, 180), (230, 210, 240), (200, 235, 235)]
for k, (day, s, n, name, room, tea, wk) in enumerate(C):
    x0 = left + (day - 1) * colw + 4; y0 = top + (s - 1) * rowh + 4
    d.rounded_rectangle([x0, y0, x0 + colw - 8, y0 + n * rowh - 8], 14, fill=col[k])
    y = y0 + 10
    for line in [name[:4], name[4:], '@' + room, tea, wk]:
        if not line: continue
        d.text((x0 + 8, y), line, font=f(22 if line != wk else 19), fill=(40, 40, 40)); y += 30
img.save(sys.argv[1])
