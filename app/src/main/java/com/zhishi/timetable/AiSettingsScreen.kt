package com.zhishi.timetable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 「AI 设置」：用户自带接口（BYO）专用的一页，和 AI 的其他部分分开放。
 * 以后换成托管后端时，删掉这一页、「我的」里的入口和 AiSettingsStore 即可（见 AGENTS.md 的「AI 接缝」）。
 */
@Composable
fun AiSettingsScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { Ai.settings(c) }
    var preset by remember { mutableStateOf(saved.preset) }
    var base by remember { mutableStateOf(saved.base) }
    var key by remember { mutableStateOf(saved.key) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(saved.model) }
    var vision by remember { mutableStateOf(saved.visionModel) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var warn by remember { mutableStateOf(false) }
    var encOk by remember { mutableStateOf(Ai.store(c).encrypted()) }
    fun cur() = AiSettings(base.trim(), key.trim(), model.trim(), vision.trim(), preset)
    fun save(): Boolean { val ok = Ai.save(c, cur()); encOk = ok; return ok }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding()) {
        Note(tr("用你自己的 AI 接口（OpenAI 兼容：官方、各家云服务或中转站都行）。截图、你发的消息，以及回答需要的课表、待办和设置，会发给你在这里填的服务商；知时 AI 没有自己的服务器，不经手这些数据。"), warm = true)

        SectionTitle(tr("服务商"), tr("预设只帮你填接口地址，模型名自己填或从列表里选"))
        FlowRowLite {
            for (p in AiPresets.ALL) {
                val on = p.id == preset
                Text(tr(p.name), fontSize = 13.sp, color = if (on) C.OnClay else C.Ink,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = if (on) null else androidx.compose.material3.LocalTextStyle.current.shadow),
                    modifier = Modifier.padding(4.dp).glassButton(CircleShape, tint = C.Clay, fill = if (on) C.Clay.copy(alpha = 0.8f) else null, shadow = 0.dp) {
                        preset = p.id; base = p.base; models = null
                    }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }

        SectionTitle(tr("接口地址"), tr("填到 /v1 为止，或者只填域名；末尾的 / 和 /chat/completions 会自动去掉"))
        AiField(base, "https://…/v1", { base = it; models = null }, keyboard = KeyboardType.Uri)
        if (base.isNotBlank()) Text(tr("实际请求：{0}", AiUrl.candidates(base).joinToString(tr(" 或 ")) { "$it/chat/completions" }), fontSize = 11.sp, color = C.Faint, lineHeight = 16.sp,
            modifier = Modifier.padding(start = 6.dp, top = 6.dp))

        SectionTitle(tr("API Key"), if (encOk) tr("只存在这台手机上，用 Android Keystore 加密") else tr("这台手机的密钥库不可用，密钥没有加密保存"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiField(key, "sk-…", { key = it }, Modifier.weight(1f), secret = !showKey, keyboard = KeyboardType.Password)
            Text(if (showKey) tr("隐藏") else tr("显示"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { showKey = !showKey }.padding(8.dp))
        }

        SectionTitle(tr("对话模型"), tr("AI 助手用它聊天、调用工具，需要支持 tools / function calling"))
        AiField(model, tr("模型名，比如 qwen-plus、deepseek-chat"), { model = it })
        SectionTitle(tr("看图模型（选填）"), tr("截图识别用；留空就用对话模型。不少文本模型看不了图片，这时要单独填一个能看图的模型"))
        AiField(vision, tr("留空 = 用对话模型"), { vision = it })

        Spacer(Modifier.height(10.dp))
        GhostButton(tr("从接口获取模型列表"), Modifier.fillMaxWidth()) {
            busy = tr("正在获取模型列表…"); result = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { try { Result.success((Ai.override ?: OpenAiCompatClient(cur())).models()) } catch (e: Throwable) { Result.failure(e) } }
                busy = null
                r.onSuccess { models = it; if (it.isEmpty()) result = tr("这个接口没有提供模型列表，直接手动填模型名") }.onFailure { result = aiErrorText(it) }
            }
        }
        models?.takeIf { it.isNotEmpty() }?.let { list ->
            Text(tr("点「对话」或「看图」填进上面"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(start = 6.dp, top = 10.dp))
            Column(Modifier.padding(top = 4.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                for (m in list) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m, fontSize = 13.sp, color = if (m == model || m == vision) C.ClayDeep else C.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                    for ((label, isVision) in listOf(tr("对话") to false, tr("看图") to true))
                        Text(label, fontSize = 12.sp, color = C.ClayDeep, modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp))
                            .clickable { if (isVision) vision = m else model = m }.padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        PrimaryButton(tr("保存")) { save(); result = tr("已保存") }
        Spacer(Modifier.height(10.dp))
        GhostButton(tr("测试连接"), Modifier.fillMaxWidth()) {
            busy = tr("正在测试…"); result = null; warn = false
            scope.launch {
                val s = cur()
                val lines = withContext(Dispatchers.IO) {
                    val out = ArrayList<String>()
                    val cl = Ai.override ?: OpenAiCompatClient(s)
                    try { cl.test(); out += tr("对话模型：连接成功") } catch (e: Throwable) { out += tr("对话模型：") + aiErrorText(e); warn = true }
                    if (!warn) {
                        val vOk = try { (cl as? OpenAiCompatClient)?.testVision() ?: true } catch (e: Throwable) { out += tr("看图：") + aiErrorText(e, s.visionModel.isBlank()); warn = true; null }
                        when (vOk) {
                            true -> out += tr("看图（{0}）：能读出测试图里的字", s.visionModelOrChat)
                            false -> { out += tr("看图（{0}）：没读对测试图，可能看不了图片，截图识别建议单独填一个看图模型", s.visionModelOrChat); warn = true }
                            null -> {}
                        }
                    }
                    out
                }
                busy = null; result = lines.joinToString("\n")
                if (!warn) save()
            }
        }
        busy?.let { Text(it, fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 12.dp, start = 6.dp)) }
        result?.let { Note(it, warm = warn) }
        Spacer(Modifier.height(18.dp))
        Text(tr("清除 AI 设置"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(10.dp)).clickable {
            Ai.store(c).clear(); OpenAiCompatClient.forget(); Ai.ver.value++
            base = ""; key = ""; model = ""; vision = ""; preset = AiPresets.CUSTOM; models = null; result = tr("已清除")
        }.padding(10.dp))
        Spacer(Modifier.height(60.dp))
    }
}

/** 输入框：和 FieldBox 一样的玻璃底；secret = 密码样式（圆点）。 */
@Composable
private fun AiField(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    Box(modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(18.dp), fill = Glass.field(), shadow = 0.dp).padding(horizontal = 16.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(hint, fontSize = 14.sp, color = C.Faint, maxLines = 1)
        BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(C.Clay),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
    }
}
