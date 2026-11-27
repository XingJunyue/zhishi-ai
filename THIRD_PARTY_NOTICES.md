# 第三方声明 / Third-party notices

知时的教务导入（继承自作者的另一个应用「留白」）参考并移植了以下开源项目的逻辑和数据。只用了 MIT 许可的部分；GPL 项目（如 Dawn-Course、sleepy、Saafo/uestc-coursetable-parser）没有复制任何代码，最多只核对过接口名。
`kqakqakqa/xiaoai-schedule-parser`（中国矿业大学新正方分支）没有许可证：只看了接口名和参数名（`xskbcx_cxXsgrkb.html`、`gnmkdm=N2151`、`kzlx=ck`、`xsdm`），没有复制代码。

## ShiGuangSchedule/shiguang_warehouse — MIT
- 来源：https://github.com/ShiGuangSchedule/shiguang_warehouse （读取时的提交 250c4f9）
- 用在：`app/src/main/java/com/zhishi/timetable/JwAdapters.kt`（正方网格/列表视图、新 URP 页面、青果「周次[节次]」、超星 sdpkkbList 接口与连排合并的解析思路）、
  `Jw.kt` 里强智的行标签节次与单双周字段、`WebImport.kt` 里超星的取数流程，以及树维 eams 直接请求课表的参数（`resources/UESTC/uestc.js`：
  `courseTableForStd!courseTable.action` 的 ignoreHead / setting.kind / project.id / semester.id / ids，电子科大学期 id 规律 483 起每学期 +20）、`Jw.kt` 树维解析里跳过「停课」记录；`JwScript.kt` 青果新版接口 `wsxk/xkjg.ckdgxsxdkchj_data10319.jsp?params=base64(xn=..&xq=..)`（`resources/HIST/hist_01.js`）；
  `app/src/main/assets/schools.tsv` 中来源标 `s` 的学校和入口网址（来自各校 `adapters.yaml` 的 `import_url`）。
- 拾光课程表主仓库 XingHeYuZhuan/shiguangschedule 为 Apache-2.0；本项目没有使用它的代码，只用了上面这个 MIT 的适配脚本仓库。

Copyright (c) 2025 星河欲转

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## baoozak/timetable — MIT
- 来源：https://github.com/baoozak/timetable （读取时的提交 e4b57df）
- 用在：`Jw.kt` 正方紧凑节次「0102」、`WebImport.kt` 正方备用接口 `xskbcx_cxXsgrkb.html`、`JwAdapters.kt` 南软 `table.tb_kcb` 与 `<hr>` 分隔；
  `app/src/main/assets/schools.tsv` 中来源标 `b` 的学校、网址和类型（`utils/schools.json`，类型按网址特征做了修正）。

Copyright (c) 2026 baoozak

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## NEU 教务课表导出脚本（greasyfork 573005）— MIT
- 东北大学金智教务的接口参考，见 `Jw.kt` 开头说明（此前版本已有）。

## Ma Shan Zheng（马善政楷书）— SIL Open Font License 1.1
- 来源：https://github.com/googlefonts/mashanzheng （Google Fonts：https://fonts.google.com/specimen/Ma+Shan+Zheng）
- 用在：应用图标里的书法「知时」和印章里的「雨」（`app/src/main/res/drawable/ic_launcher_fg.xml`、`ic_mono.xml`，以及由它们渲染的 `mipmap-*/ic_launcher*.png`）。只把这三个字的字形轮廓转成了矢量路径画进图标，没有附带、修改或再分发字体文件本身。

```
Copyright 2018 The Ma Shan Zheng Project Authors (https://github.com/googlefonts/mashanzheng)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
http://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## sherpa-onnx（k2-fsa）— Apache License 2.0
- 来源：https://github.com/k2-fsa/sherpa-onnx ，版本 v2.0.3 的 Android AAR `sherpa-onnx-static-link-onnxruntime-2.0.3.aar`（sha256 `7744a3a8f08f017d59c2d8ed8d9b700345561f5abe9f4e06dc6eaef3a0b977b4`），构建时下载，不在仓库里。
- 用在：AI 助手的离线语音输入（`Voice.kt`）：流式识别 API（`com.k2fsa.sherpa.onnx.OnlineRecognizer` 等）和 JNI 库 `libsherpa-onnx-jni.so`（只打包 arm64-v8a、armeabi-v7a）。
- 这个 AAR 静态链接了 ONNX Runtime（Microsoft，MIT License，https://github.com/microsoft/onnxruntime ）。
- Copyright (c) 2022-2026 Xiaomi Corporation and the k2-fsa / sherpa-onnx contributors. Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
- ONNX Runtime: Copyright (c) Microsoft Corporation. MIT License.

## 中文语音识别模型 sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01 — Apache License 2.0
- 来源：k2-fsa 发布的 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2
  （21,264,113 字节，sha256 `b3b309f7ce4a737195fcc6963ea19b0653a7d3401580af5ae0d3e284cbb71f0b`）；知时 AI 仓库的 release `asr-model` 里放了同一个文件作镜像。
- 许可：模型卡标注 Apache-2.0（https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01 ，
  PyTorch 原模型 https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01 也是 Apache-2.0），允许商用。训练代码来自 k2-fsa/icefall（Apache-2.0）。
- 不打进安装包：用户第一次用语音输入时下载到 App 私有目录，只取 `model.int8.onnx`、`tokens.txt` 两个文件，没有修改。

## Apache Commons Compress — Apache License 2.0
- 来源：https://commons.apache.org/proper/commons-compress/ （Maven `org.apache.commons:commons-compress:1.24.0`）
- 用在：`Asr.kt` 解语音模型包（.tar.bz2）。
- Copyright 2002-2023 The Apache Software Foundation. Licensed under the Apache License, Version 2.0.
