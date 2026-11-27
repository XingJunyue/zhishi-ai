package com.zhishi.timetable

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 皮肤：每套一份浅色、一份深色配色（ARGB）。App 界面和桌面小部件共用同一份，换皮肤后小部件立刻重画。
 * 名字用中文当键，六种语言在 assets/i18n 下的 json 里。
 */
class Pal(
    val bg: Long, val card: Long, val ink: Long, val soft: Long, val faint: Long,
    val acc: Long, val deep: Long, val pale: Long, val track: Long, val sand: Long,
    val onAcc: Long, val sage: Long, val sagePale: Long, val sageInk: Long, val dark: Boolean,
) {
    fun i(v: Long) = v.toInt()
}

/**
 * 一套皮肤（十八套：原六套 + build-31 起按用户给的插画加的十二套）：名字、一句话简介（poem）、浅色 / 深色配色，和对应的整屏插画背景（res/drawable-nodpi/skin_<id>_<light|dark>.webp）
 * 及圆形缩略图（skin_<id>_<light|dark>_thumb.webp）。
 */
class Skin(val id: String, val name: String, val poem: String, val light: Pal, val dark: Pal) {
    fun art(dark: Boolean): Int = ART["${id}_${if (dark) "dark" else "light"}"] ?: 0
    fun thumb(dark: Boolean): Int = ART["${id}_${if (dark) "dark" else "light"}_thumb"] ?: 0
    companion object {
        /** 资源 id 表：用 R 常量，混淆和资源压缩都能看到引用。 */
        private val ART: Map<String, Int> by lazy { mapOf(
            "chunyu_light" to R.drawable.skin_chunyu_light, "chunyu_dark" to R.drawable.skin_chunyu_dark,
            "xuanzhi_light" to R.drawable.skin_xuanzhi_light, "xuanzhi_dark" to R.drawable.skin_xuanzhi_dark,
            "qingci_light" to R.drawable.skin_qingci_light, "qingci_dark" to R.drawable.skin_qingci_dark,
            "mushan_light" to R.drawable.skin_mushan_light, "mushan_dark" to R.drawable.skin_mushan_dark,
            "yehang_light" to R.drawable.skin_yehang_light, "yehang_dark" to R.drawable.skin_yehang_dark,
            "ying_light" to R.drawable.skin_ying_light, "ying_dark" to R.drawable.skin_ying_dark,
            "yinxing_light" to R.drawable.skin_yinxing_light, "yinxing_dark" to R.drawable.skin_yinxing_dark,
            "songxue_light" to R.drawable.skin_songxue_light, "songxue_dark" to R.drawable.skin_songxue_dark,
            "hefeng_light" to R.drawable.skin_hefeng_light, "hefeng_dark" to R.drawable.skin_hefeng_dark,
            "zhuying_light" to R.drawable.skin_zhuying_light, "zhuying_dark" to R.drawable.skin_zhuying_dark,
            "youlan_light" to R.drawable.skin_youlan_light, "youlan_dark" to R.drawable.skin_youlan_dark,
            "qinglv_light" to R.drawable.skin_qinglv_light, "qinglv_dark" to R.drawable.skin_qinglv_dark,
            "hanmei_light" to R.drawable.skin_hanmei_light, "hanmei_dark" to R.drawable.skin_hanmei_dark,
            "liuyue_light" to R.drawable.skin_liuyue_light, "liuyue_dark" to R.drawable.skin_liuyue_dark,
            "guixiang_light" to R.drawable.skin_guixiang_light, "guixiang_dark" to R.drawable.skin_guixiang_dark,
            "haitang_light" to R.drawable.skin_haitang_light, "haitang_dark" to R.drawable.skin_haitang_dark,
            "yanyu_light" to R.drawable.skin_yanyu_light, "yanyu_dark" to R.drawable.skin_yanyu_dark,
            "guiyan_light" to R.drawable.skin_guiyan_light, "guiyan_dark" to R.drawable.skin_guiyan_dark,
            "chunyu_light_thumb" to R.drawable.skin_chunyu_light_thumb, "chunyu_dark_thumb" to R.drawable.skin_chunyu_dark_thumb,
            "xuanzhi_light_thumb" to R.drawable.skin_xuanzhi_light_thumb, "xuanzhi_dark_thumb" to R.drawable.skin_xuanzhi_dark_thumb,
            "qingci_light_thumb" to R.drawable.skin_qingci_light_thumb, "qingci_dark_thumb" to R.drawable.skin_qingci_dark_thumb,
            "mushan_light_thumb" to R.drawable.skin_mushan_light_thumb, "mushan_dark_thumb" to R.drawable.skin_mushan_dark_thumb,
            "yehang_light_thumb" to R.drawable.skin_yehang_light_thumb, "yehang_dark_thumb" to R.drawable.skin_yehang_dark_thumb,
            "ying_light_thumb" to R.drawable.skin_ying_light_thumb, "ying_dark_thumb" to R.drawable.skin_ying_dark_thumb,
            "yinxing_light_thumb" to R.drawable.skin_yinxing_light_thumb, "yinxing_dark_thumb" to R.drawable.skin_yinxing_dark_thumb,
            "songxue_light_thumb" to R.drawable.skin_songxue_light_thumb, "songxue_dark_thumb" to R.drawable.skin_songxue_dark_thumb,
            "hefeng_light_thumb" to R.drawable.skin_hefeng_light_thumb, "hefeng_dark_thumb" to R.drawable.skin_hefeng_dark_thumb,
            "zhuying_light_thumb" to R.drawable.skin_zhuying_light_thumb, "zhuying_dark_thumb" to R.drawable.skin_zhuying_dark_thumb,
            "youlan_light_thumb" to R.drawable.skin_youlan_light_thumb, "youlan_dark_thumb" to R.drawable.skin_youlan_dark_thumb,
            "qinglv_light_thumb" to R.drawable.skin_qinglv_light_thumb, "qinglv_dark_thumb" to R.drawable.skin_qinglv_dark_thumb,
            "hanmei_light_thumb" to R.drawable.skin_hanmei_light_thumb, "hanmei_dark_thumb" to R.drawable.skin_hanmei_dark_thumb,
            "liuyue_light_thumb" to R.drawable.skin_liuyue_light_thumb, "liuyue_dark_thumb" to R.drawable.skin_liuyue_dark_thumb,
            "guixiang_light_thumb" to R.drawable.skin_guixiang_light_thumb, "guixiang_dark_thumb" to R.drawable.skin_guixiang_dark_thumb,
            "haitang_light_thumb" to R.drawable.skin_haitang_light_thumb, "haitang_dark_thumb" to R.drawable.skin_haitang_dark_thumb,
            "yanyu_light_thumb" to R.drawable.skin_yanyu_light_thumb, "yanyu_dark_thumb" to R.drawable.skin_yanyu_dark_thumb,
            "guiyan_light_thumb" to R.drawable.skin_guiyan_light_thumb, "guiyan_dark_thumb" to R.drawable.skin_guiyan_dark_thumb,
        ) }
    }
}

private val SAGE_L = Triple(0xFF6E9A55, 0xFFE8F1DF, 0xFF4F7A36)
private val SAGE_D = Triple(0xFF9CC47A, 0xFF22301A, 0xFFB5D99A)

private fun lp(bg: Long, card: Long, ink: Long, soft: Long, faint: Long, acc: Long, deep: Long, pale: Long, track: Long, sand: Long, onAcc: Long = 0xFFFFFFFF) =
    Pal(bg, card, ink, soft, faint, acc, deep, pale, track, sand, onAcc, SAGE_L.first, SAGE_L.second, SAGE_L.third, false)
private fun dp(bg: Long, card: Long, ink: Long, soft: Long, faint: Long, acc: Long, deep: Long, pale: Long, track: Long, sand: Long, onAcc: Long) =
    Pal(bg, card, ink, soft, faint, acc, deep, pale, track, sand, onAcc, SAGE_D.first, SAGE_D.second, SAGE_D.third, true)

object Skins {
    val ALL = listOf(
        Skin("chunyu", "春雨", "清新如春，细雨润心",
            lp(0xFFF1F6F1, 0xFFFFFFFE, 0xFF1E2B25, 0xFF56685E, 0xFF8C9A92, 0xFF3F8A62, 0xFF2C6B4A, 0xFFDCEEE1, 0xFFE0E9E2, 0xFFEAF1EB),
            dp(0xFF0F1813, 0xFF17221C, 0xFFE2ECE5, 0xFFA2B4A8, 0xFF73857A, 0xFF8CCBA4, 0xFFA9DDBC, 0xFF1C3427, 0xFF25312A, 0xFF1D2922, 0xFF0D2618)),
        Skin("xuanzhi", "宣纸", "书卷墨香，文心相伴",
            lp(0xFFF6F1E7, 0xFFFFFCF6, 0xFF2B2622, 0xFF6E645B, 0xFF9E9488, 0xFFB0503A, 0xFF8C3C2A, 0xFFF5E2D8, 0xFFEBE3D5, 0xFFF0E9DC),
            dp(0xFF191613, 0xFF221F1B, 0xFFEDE6DA, 0xFFB8AE9F, 0xFF857B6E, 0xFFE38E74, 0xFFF0AA94, 0xFF3A2620, 0xFF322C26, 0xFF29241F, 0xFF2A120B)),
        Skin("qingci", "青瓷", "古韵流光，淡雅宁静",
            lp(0xFFEDF4F1, 0xFFFBFEFC, 0xFF1D2E2A, 0xFF557069, 0xFF89A099, 0xFF4A9180, 0xFF337363, 0xFFD9EDE6, 0xFFDAE7E2, 0xFFE5F0EB),
            dp(0xFF0E1715, 0xFF16221F, 0xFFDDEDE7, 0xFF9DBAB1, 0xFF6B8981, 0xFF8ED3C0, 0xFFADE3D4, 0xFF1A3730, 0xFF22342F, 0xFF1A2A26, 0xFF0B2620)),
        Skin("mushan", "暮山", "暮色山河，静谧沉稳",
            lp(0xFFEFEDF7, 0xFFFFFEFF, 0xFF262537, 0xFF615E7A, 0xFF9693AE, 0xFF5F5CA6, 0xFF474489, 0xFFE4E2F6, 0xFFE2E0EF, 0xFFEAE9F4),
            dp(0xFF15142A, 0xFF1E1D36, 0xFFE6E5F3, 0xFFADABC8, 0xFF7B7996, 0xFFADA9EC, 0xFFC7C4F4, 0xFF2B2A4E, 0xFF2A2943, 0xFF201F38, 0xFF181638)),
        Skin("yehang", "夜航", "星海航行，向梦而行",
            lp(0xFFECEEF9, 0xFFFFFFFF, 0xFF1D2238, 0xFF59607E, 0xFF8E94B0, 0xFF4B5CB0, 0xFF35438C, 0xFFDFE3F8, 0xFFDFE2F1, 0xFFE8EAF6),
            dp(0xFF0A111E, 0xFF121B2C, 0xFFE2E8F3, 0xFF9EABC2, 0xFF6A7791, 0xFF7FA8E8, 0xFFA3C2F2, 0xFF1A2A47, 0xFF1F2A40, 0xFF162136, 0xFF0A1630)),
        Skin("ying", "樱", "樱花烂漫，与你相遇",
            lp(0xFFFBF3F4, 0xFFFFFDFD, 0xFF33262A, 0xFF7A6268, 0xFFAD959B, 0xFFC2607A, 0xFFA0465F, 0xFFF8E0E6, 0xFFF0E2E5, 0xFFF6EAED),
            dp(0xFF1A1215, 0xFF241A1E, 0xFFF1E5E8, 0xFFC3A9B0, 0xFF8E747B, 0xFFEE9AB0, 0xFFF5B8C7, 0xFF3F2430, 0xFF34262B, 0xFF2B1F24, 0xFF3A1220)),
        Skin("yinxing", "银杏", "金叶满枝，秋光正好",
            lp(0xFFF9F3E6, 0xFFFFFDF7, 0xFF2E2618, 0xFF6F6450, 0xFFA0947D, 0xFFB07A1E, 0xFF8A5C12, 0xFFF7E8C8, 0xFFEDE4D2, 0xFFF3EBDA),
            dp(0xFF16120C, 0xFF201A12, 0xFFF1E8D8, 0xFFBFAF95, 0xFF8A7C66, 0xFFE8B45A, 0xFFF2C97F, 0xFF3A2C14, 0xFF30281C, 0xFF261F16, 0xFF2A1A04)),
        Skin("songxue", "松雪", "松间落雪，清寂自持",
            lp(0xFFEFF2F6, 0xFFFDFEFF, 0xFF1E2731, 0xFF5A6675, 0xFF8E99A7, 0xFF4E6C8A, 0xFF38536E, 0xFFDDE6EF, 0xFFDFE5EC, 0xFFE9EDF2),
            dp(0xFF0E151C, 0xFF151E27, 0xFFE3EAF1, 0xFFA3B1BF, 0xFF6F7E8D, 0xFF9DBBD6, 0xFFBCD2E6, 0xFF1E2E3E, 0xFF223040, 0xFF18232E, 0xFF0C1A28)),
        Skin("hefeng", "荷风", "荷风送香，静水生凉",
            lp(0xFFEEF5F0, 0xFFFCFEFD, 0xFF1F2C27, 0xFF5A6E66, 0xFF8FA29A, 0xFFC86A80, 0xFFA44E64, 0xFFF6E0E5, 0xFFDDE8E2, 0xFFE7F0EB),
            dp(0xFF0C1719, 0xFF132124, 0xFFE0EDEC, 0xFF9EB7B5, 0xFF6A8583, 0xFFF0A3B4, 0xFFF6BFCB, 0xFF3A2329, 0xFF1E3134, 0xFF16282B, 0xFF3A1220)),
        Skin("zhuying", "竹影", "竹影清疏，心远自宁",
            lp(0xFFF4F3EA, 0xFFFFFEF8, 0xFF222A22, 0xFF5E6A5C, 0xFF939D90, 0xFF5B8A5A, 0xFF406B40, 0xFFE2EEDC, 0xFFE5E7DA, 0xFFEEEFE4),
            dp(0xFF0F1613, 0xFF16201B, 0xFFE1EBE3, 0xFFA0B3A5, 0xFF6D8172, 0xFF9CCB98, 0xFFB8DDB4, 0xFF1E3322, 0xFF233229, 0xFF1A261F, 0xFF0E2610)),
        Skin("youlan", "幽兰", "空谷幽兰，淡然自芳",
            lp(0xFFF4F2F2, 0xFFFFFEFE, 0xFF29262E, 0xFF66616E, 0xFF9A95A2, 0xFF7A68B0, 0xFF5C4C8E, 0xFFE9E4F6, 0xFFE7E4EA, 0xFFEFEDF0),
            dp(0xFF121418, 0xFF1A1C22, 0xFFE7E6EE, 0xFFADAABB, 0xFF7A7788, 0xFFB5A6EE, 0xFFCBC0F4, 0xFF2C2645, 0xFF262832, 0xFF1E2028, 0xFF1C1238)),
        Skin("qinglv", "青绿", "青绿山河，千里入画",
            lp(0xFFF8F2E8, 0xFFFFFDF8, 0xFF1E2B2B, 0xFF5A6A66, 0xFF8F9D98, 0xFF2F8A7E, 0xFF1F6A60, 0xFFD8EEE8, 0xFFE8E4D8, 0xFFF1EBDF),
            dp(0xFF0D141B, 0xFF141D26, 0xFFE0EAEE, 0xFF9DB0B8, 0xFF6A7D86, 0xFF74CDBD, 0xFF9BDDD0, 0xFF163430, 0xFF1F2C36, 0xFF17222C, 0xFF06261F)),
        Skin("hanmei", "寒梅", "踏雪寻梅，暗香浮动",
            lp(0xFFF4F2F5, 0xFFFFFEFF, 0xFF2A2328, 0xFF6A5F66, 0xFF9D939A, 0xFFA83A3F, 0xFF862A30, 0xFFF3DEDF, 0xFFE8E4E8, 0xFFEFECF0),
            dp(0xFF14151B, 0xFF1C1D24, 0xFFEDE7EA, 0xFFB8ADB3, 0xFF847A80, 0xFFE8848A, 0xFFF2A6AA, 0xFF3E2226, 0xFF292A33, 0xFF20212A, 0xFF3A0E12)),
        Skin("liuyue", "柳月", "杨柳依依，月照归途",
            lp(0xFFF5F3EA, 0xFFFFFEF8, 0xFF262A20, 0xFF646A58, 0xFF989D8C, 0xFF6F8534, 0xFF566822, 0xFFE9EED6, 0xFFE7E7DA, 0xFFEFEFE4),
            dp(0xFF111318, 0xFF191C22, 0xFFE8EAE4, 0xFFB0B4AA, 0xFF7D8178, 0xFFC3D48A, 0xFFD5E2A6, 0xFF2C321A, 0xFF252830, 0xFF1D2027, 0xFF20280A)),
        Skin("guixiang", "桂香", "桂子飘香，月色温柔",
            lp(0xFFFAF3E8, 0xFFFFFDF8, 0xFF2C2519, 0xFF6E6452, 0xFFA0957F, 0xFFC27C1A, 0xFF9A600F, 0xFFF8E7C9, 0xFFEEE5D4, 0xFFF4ECDD),
            dp(0xFF0D1420, 0xFF141C2A, 0xFFE6EAF2, 0xFFA7B1C4, 0xFF717C90, 0xFFF0B460, 0xFFF5C986, 0xFF3A2C16, 0xFF1F2939, 0xFF18212F, 0xFF2A1800)),
        Skin("haitang", "海棠", "海棠依旧，春色如许",
            lp(0xFFFBF5F2, 0xFFFFFEFD, 0xFF30241F, 0xFF74625B, 0xFFA8968F, 0xFFD0574A, 0xFFA93F34, 0xFFFAE0DB, 0xFFF0E5E1, 0xFFF6EDEA),
            dp(0xFF111417, 0xFF191D21, 0xFFEEE8E6, 0xFFB8ADA9, 0xFF847A77, 0xFFF28F82, 0xFFF6AEA4, 0xFF3E2420, 0xFF262A2F, 0xFF1E2226, 0xFF3A100A)),
        Skin("yanyu", "檐雨", "檐下听雨，灯火可亲",
            lp(0xFFEFF1EF, 0xFFFCFDFC, 0xFF20272A, 0xFF5B6669, 0xFF8F999B, 0xFF4D7470, 0xFF365855, 0xFFDCE9E6, 0xFFDEE3E2, 0xFFE8ECEB),
            dp(0xFF0F1416, 0xFF161D20, 0xFFE3E9EA, 0xFFA2AFB2, 0xFF6E7B7E, 0xFFE6A955, 0xFFF0C07E, 0xFF3A2B16, 0xFF222A2D, 0xFF1A2124, 0xFF2A1800)),
        Skin("guiyan", "归雁", "雁字回时，烟波浩渺",
            lp(0xFFF6F3F0, 0xFFFFFEFD, 0xFF25262C, 0xFF63636E, 0xFF97979F, 0xFF5E6F93, 0xFF465574, 0xFFE2E7F2, 0xFFE8E5E2, 0xFFF0EDEA),
            dp(0xFF0E1622, 0xFF15202E, 0xFFE3E9F2, 0xFFA3B0C3, 0xFF6F7C90, 0xFF9EB6DE, 0xFFBCCDEA, 0xFF1E2C44, 0xFF213044, 0xFF182434, 0xFF0C1830)),
    )
    val MODES = listOf("跟随系统", "浅色", "深色")

    private fun p(c: Context) = c.getSharedPreferences("look", Context.MODE_PRIVATE)

    /** Compose 界面读这两个状态，改了马上重组。 */
    val skinState = mutableStateOf(ALL[0])
    val modeState = mutableStateOf(0)
    val sysDarkState = mutableStateOf(false)

    fun init(c: Context) {
        skinState.value = byId(str(c, "skin"))
        modeState.value = mode(c)
        sysDarkState.value = sysDark(c)
    }
    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL[0]
    /** 容错读法：类型不对 / 越界都退回默认（升级来的老数据不能让启动崩）。 */
    private fun str(c: Context, k: String): String? = try { p(c).getString(k, null) } catch (x: ClassCastException) { null }
    private fun mode(c: Context): Int = (try { p(c).getInt("mode", 0) } catch (x: ClassCastException) { 0 }).let { if (it in 0..2) it else 0 }
    fun sysDark(c: Context) = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** redraw = false：调用方自己接着重画小部件（小部件刷新里轮换皮肤时）。 */
    fun setSkin(c: Context, s: Skin, redraw: Boolean = true) { p(c).edit().putString("skin", s.id).apply(); skinState.value = s; if (redraw) CourseWidgets.safeUpdate(c); CloudSync.localChanged(c) }
    fun setMode(c: Context, m: Int) { p(c).edit().putInt("mode", m).apply(); modeState.value = m; CourseWidgets.safeUpdate(c); CloudSync.localChanged(c) }
    /** 存着的皮肤 id / 深浅（云同步读这个：后台进程里 skinState / modeState 可能还没 init）。 */
    fun storedId(c: Context): String = byId(str(c, "skin")).id
    fun storedMode(c: Context): Int = mode(c)

    /** 当前是不是深色（mode：0 跟随系统、1 浅色、2 深色）。 */
    fun darkFor(mode: Int, sys: Boolean) = when (mode) { 1 -> false; 2 -> true; else -> sys }

    /** 小部件用：不依赖 Compose，直接读存储和系统深色设置。 */
    fun palFor(c: Context): Pal {
        val s = byId(str(c, "skin"))
        return if (darkFor(mode(c), sysDark(c))) s.dark else s.light
    }
}

/** 界面配色：随皮肤和深浅色变。名字沿用原来的写法（Clay = 主色）。 */
object C {
    private val pal: Pal get() { val s = Skins.skinState.value; return if (dark) s.dark else s.light }
    val dark: Boolean get() = Skins.darkFor(Skins.modeState.value, Skins.sysDarkState.value)
    val Cream get() = Color(pal.bg)
    val Card get() = Color(pal.card)
    val Ink get() = Color(pal.ink)
    val Soft get() = Color(pal.soft)
    val Faint get() = Color(pal.faint)
    val Clay get() = Color(pal.acc)
    val ClayDeep get() = Color(pal.deep)
    val ClayPale get() = Color(pal.pale)
    val Track get() = Color(pal.track)
    val Sand get() = Color(pal.sand)
    val OnClay get() = Color(pal.onAcc)
    val Sage get() = Color(pal.sage)
    val SagePale get() = Color(pal.sagePale)
    val SageInk get() = Color(pal.sageInk)
    /** 液态玻璃的底（清透，插画背景透出来）；要亮边和水光用 Modifier.liquidGlass（Glass.kt）。 */
    val Glass get() = com.zhishi.timetable.Glass.fill(com.zhishi.timetable.Glass.CLEAR)
    /** 稍实一点的玻璃（导航条一档）。 */
    val GlassStrong get() = com.zhishi.timetable.Glass.fill(com.zhishi.timetable.Glass.BAR)
    /** 玻璃的细亮边。 */
    val GlassEdge get() = Color.White.copy(alpha = if (dark) 0.16f else 0.6f)
}

/** 课块配色（浅底, 深字）：深色模式下换成深底浅字，色相不变。 */
fun courseTone(pair: Pair<Color, Color>, dark: Boolean, card: Color): Pair<Color, Color> {
    if (!dark) return pair
    val (lightC, deepC) = pair
    // 深色：低饱和的深底（像蒙了一层雾的玻璃）+ 浅字
    return androidx.compose.ui.graphics.lerp(card, deepC, 0.42f) to androidx.compose.ui.graphics.lerp(lightC, Color.White, 0.25f)
}
fun courseToneArgb(pair: Pair<Int, Int>, pal: Pal): Pair<Int, Int> =
    if (!pal.dark) pair else courseTone(Color(pair.first) to Color(pair.second), true, Color(pal.card)).let { it.first.toArgb() to it.second.toArgb() }

/**
 * 皮肤收藏和自动轮换（SharedPreferences `look`）：`fav` = 收藏的皮肤 id（逗号分隔，按收藏先后）；
 * `rot` = 0 关闭 / 1 全部随机 / 2 收藏轮换（按收藏顺序一套接一套）；`rotEvery` = 0 每次打开 / 1 每天（默认）/ 2 每周；
 * `rotAt` = 上次换的那个周期（天 / 周的编号）。界面和小部件一起换（每天 / 每周的在小部件 0 点刷新时也会换，不用打开 App）。
 */
object SkinRotation {
    const val OFF = 0; const val ALL = 1; const val FAV = 2
    const val OPEN = 0; const val DAY = 1; const val WEEK = 2
    /** 界面读：收藏 / 设置变了马上重组。 */
    val ver = mutableStateOf(0)

    private fun p(c: Context) = c.getSharedPreferences("look", Context.MODE_PRIVATE)
    private fun int(c: Context, k: String, d: Int) = try { p(c).getInt(k, d) } catch (x: ClassCastException) { d }

    fun favs(c: Context): List<String> = parseFavs(try { p(c).getString("fav", null) } catch (x: ClassCastException) { null })
    fun parseFavs(raw: String?): List<String> = (raw ?: "").split(',').map { it.trim() }.filter { id -> id.isNotEmpty() && Skins.ALL.any { it.id == id } }.distinct()
    fun isFav(c: Context, id: String) = id in favs(c)
    fun toggleFav(c: Context, id: String) {
        val l = favs(c); val n = if (id in l) l - id else l + id
        p(c).edit().putString("fav", n.joinToString(",")).apply(); ver.value++; CloudSync.localChanged(c)
    }

    fun mode(c: Context) = int(c, "rot", OFF).let { if (it in OFF..FAV) it else OFF }
    fun every(c: Context) = int(c, "rotEvery", DAY).let { if (it in OPEN..WEEK) it else DAY }
    /** 改设置：从现在这个周期开始算（打开后下一个周期才换，不会一打开就跳）。 */
    fun setMode(c: Context, m: Int) { p(c).edit().putInt("rot", m.coerceIn(OFF, FAV)).putLong("rotAt", period(every(c), Term.todayEpoch())).apply(); ver.value++; CloudSync.localChanged(c) }
    fun setEvery(c: Context, e: Int) { val v = e.coerceIn(OPEN, WEEK); p(c).edit().putInt("rotEvery", v).putLong("rotAt", period(v, Term.todayEpoch())).apply(); ver.value++; CloudSync.localChanged(c) }

    /** 周期编号（纯函数）：每天 = epoch 天；每周 = 从周一开始的周编号；每次打开没有周期。 */
    fun period(every: Int, day: Long): Long = when (every) { DAY -> day; WEEK -> Math.floorDiv(day + 3, 7L); else -> -1L }

    /**
     * 下一套皮肤（纯函数）：全部随机 = 除了当前这套随便挑一套；收藏轮换 = 收藏里当前这套的下一套（当前不在收藏里就从第一套开始）。
     * 没得换（关闭、收藏为空、只收藏了当前这一套）返回 null。
     */
    fun pick(mode: Int, favs: List<String>, all: List<String>, cur: String, rnd: Int): String? = when (mode) {
        ALL -> (all - cur).let { if (it.isEmpty()) null else it[Math.floorMod(rnd, it.size)] }
        FAV -> if (favs.isEmpty()) null else favs[(favs.indexOf(cur) + 1) % favs.size].takeIf { it != cur }
        else -> null
    }

    /** 这个周期该不该换（纯函数）。 */
    fun due(every: Int, last: Long, day: Long): Boolean = every != OPEN && period(every, day) != last

    private fun rotate(c: Context, redraw: Boolean): Boolean {
        val cur = Skins.byId(try { p(c).getString("skin", null) } catch (x: ClassCastException) { null }).id
        val next = pick(mode(c), favs(c), Skins.ALL.map { it.id }, cur, kotlin.random.Random.nextInt(1 shl 20)) ?: return false
        Skins.setSkin(c, Skins.byId(next), redraw)
        return true
    }

    /** 冷启动打开 App：「每次打开」就换一套。 */
    fun onLaunch(c: Context) { if (mode(c) != OFF && every(c) == OPEN) rotate(c, true) }

    /** 打开 App / 小部件刷新时：「每天 / 每周」到了新的周期就换。返回换了没有。 */
    fun onTick(c: Context, redraw: Boolean = false): Boolean {
        if (mode(c) == OFF) return false
        val e = every(c); val day = Term.todayEpoch()
        val last = try { p(c).getLong("rotAt", Long.MIN_VALUE) } catch (x: ClassCastException) { Long.MIN_VALUE }
        if (!due(e, last, day)) return false
        p(c).edit().putLong("rotAt", period(e, day)).apply()
        return rotate(c, redraw)
    }
}
