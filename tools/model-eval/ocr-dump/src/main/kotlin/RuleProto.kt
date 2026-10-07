package com.yomark.app

import com.hankcs.hanlp.HanLP
import com.yomark.app.rules.DefaultRuleSet
import com.yomark.app.rules.LabeledField
import java.io.File
import java.util.Locale

/**
 * 规则补强的原型（只在评测台里，不进 app）：在出厂规则之外再加五种认法，外加 HanLP 的人名识别，
 * 对每页 OCR 行跑一遍，按来源分组写出命中原文，供 Python 打分。
 *
 * 用法：gradle run -Pmain=com.yomark.app.RuleProtoKt --args="<ocr目录> <输出.json>"
 */

private class Line(val text: String, val conf: Float, val l: Float, val t: Float, val r: Float, val b: Float) {
    val h get() = b - t
}

private val LINE = Regex("""\{"text":"((?:[^"\\]|\\.)*)","conf":([0-9.Ee-]+),"box":\[([^\]]*)\]\}""")
private val SIZE = Regex(""""analysis":\[(\d+),(\d+)\]""")

private fun load(f: File): Pair<List<Line>, Float> {
    val s = f.readText()
    val h = SIZE.find(s)!!.groupValues[2].toFloat()
    val lines = LINE.findAll(s).map { m ->
        val (l, t, r, b) = m.groupValues[3].split(',').map { it.trim().toFloat() }
        Line(m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\"), m.groupValues[2].toFloat(), l, t, r, b)
    }.toList()
    return lines to h
}

// ---------- 1. 身份证号：完整 18 位查校验码；打星的看形状 ----------
private val ID_PLAIN = Regex("""(?<![0-9A-Za-z])\d{17}[\dXx](?![0-9A-Za-z])""")
private val ID_MASKED = Regex("""(?<![0-9A-Za-z*＊])[1-8]\d{1,5}(?:\s?[*＊•●]){6,16}\s?\d{2,4}[\dXx]?(?![0-9A-Za-z*＊])""")

private fun idChecksumOk(v: String): Boolean {
    val w = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    val sum = (0 until 17).sumOf { (v[it] - '0') * w[it] }
    return "10X98765432"[sum % 11] == v[17].uppercaseChar()
}

private fun findIds(text: String): List<IntRange> =
    ID_PLAIN.findAll(text).filter { idChecksumOk(it.value) }.map { it.range }.toList() +
        ID_MASKED.findAll(text).filter { m ->
            val compact = m.value.filterNot { it.isWhitespace() }
            compact.length in 14..19 && compact.count { it in "*＊•●" } >= 6
        }.map { it.range }.toList()

// ---------- 2. 证件号旁边的名字：同一行在它前面，或紧挨着的上一行只写着名字（可带「成人」这类票种） ----------
private const val TICKET_TAGS = "成人票|儿童票|学生票|残军票|成人|儿童|学生|婴儿|本人|军人|老人"
private val NAME_ONLY_LINE = Regex("""^[^\u4e00-\u9fa5]{0,3}\s*([一-龥]{2,4})(?:\s+(?:$TICKET_TAGS))?\s*$""")
private val NAME_BEFORE = Regex("""([一-龥]{2,4})(?:\s*(?:$TICKET_TAGS))?[\s:：,，]{0,3}$""")
private val NOT_A_NAME = listOf("身份证", "证件", "号码", "护照", "手机", "电话", "账号", "卡号", "尾号")

private fun namesNearIds(lines: List<Line>): List<String> {
    val out = ArrayList<String>()
    lines.forEachIndexed { i, line ->
        findIds(line.text).forEach { id ->
            NAME_BEFORE.find(line.text.substring(0, id.first))?.let { m ->
                val n = m.groupValues[1]
                if (NOT_A_NAME.none { it in n }) out += n
            }
            val above = lines.subList(0, i).filter { a ->
                a.b <= line.t + 0.3f * line.h && line.t - a.b < 1.5f * line.h && kotlin.math.abs(a.l - line.l) < 2f * line.h
            }.maxByOrNull { it.b }
            above?.let { a -> NAME_ONLY_LINE.find(a.text.trim())?.let { m ->
                val n = m.groupValues[1]
                if (NOT_A_NAME.none { it in n }) out += n
            } }
        }
    }
    return out
}

// ---------- 3. 更多字段名（车票、酒店、医院、保险页） ----------
private val NAME_VALUE = Regex(
    """(?:(?!电话|手机|性别|民族|出生|地址|住址|身份|证件|联系)[一-龥]){2,6}""" +
        """|[一-龥](?:[ ][一-龥]){1,5}|[A-Za-z][A-Za-z.'\-]*(?:[ ][A-Za-z][A-Za-z.'\-]*){0,2}"""
)
private val MORE_LABELS = LabeledField(
    labels = listOf(
        "乘车人", "乘客", "旅客", "乘机人", "出行人", "入住人", "住客", "就诊人", "患者", "投保人",
        "被保险人", "被保人", "受益人", "订票人", "预订人", "购票人", "取票人", "学生姓名", "旅客姓名", "乘车人姓名",
    ),
    value = NAME_VALUE,
    confidence = 0.8f,
)

// ---------- 4. 姓 + 称呼：王经理、赵师傅、李女士 ----------
private const val SURNAMES =
    "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤常温康施文牛樊葛邢安齐易乔伍庞颜倪庄聂章鲁岳翟殷詹申欧耿关兰焦俞左柳甘祝包宁尚符舒阮柯纪梅童凌毕单季裴霍涂成苗谷盛曲翁冉骆蓝路游辛靳管柴蒙鲍华喻祁蒲房滕屈饶解牟艾尤阳时穆农司卓古吉缪简车项连芦麦褚娄窦戚岑景党宫费卜冷晏席卫米柏宗瞿桂全佟应臧闵苟邬边卞姬师和仇栾隋商刁沙荣巫寇桑郎甄丛仲虞敖巩明佘池查麻苑迟邝官封谈匡鞠惠荆乐冀郁胥南班储原栗燕楚鄢劳谌奚皮粟冼蔺楼盘满闻位厉伊仝区郜海阚花权强帅屠豆朴盖练廉禹井祖漆巴丰支卿国狄平计索宣晋相初门云容敬来扈晁芮都普阙浦戈伏鹿薄邸雍辜羊乌母裘亓修邰赫杭况那宿鲜印逯隆茹诸战慕危玉银亢嵇公哈湛宾戎勾茅利於呼居揭干但尉冶斯元束檀衣信展阴昝智幸奉植衡富尧闭由沐"
private const val COMPOUND = "欧阳|司马|上官|诸葛|东方|皇甫|司徒|夏侯|慕容|令狐|公孙|长孙|宇文|尉迟|独孤|南宫|西门|端木|轩辕|呼延|百里"
private val HONORIFIC = Regex(
    """(?:$COMPOUND|[$SURNAMES])(?:先生|女士|小姐|太太|师傅|老师|经理|总监|主任|医生|大夫|护士|同学|阿姨|叔叔|老板|律师|教授|总)"""
)

// ---------- 5. 聊天页顶栏：「< 陈晓峰」「陈晓峰(3)」 ----------
private const val TOP100 = "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤常"
private val CHAT_TITLE = Regex("""^[<＜‹〈]\s*([$TOP100][一-龥]{1,2})(?:\s*[（(]\d+[)）])?$""")

fun main(args: Array<String>) {
    Locale.setDefault(Locale.CHINA)
    val result = StringBuilder("{\n")
    val files = File(args[0]).listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    files.forEachIndexed { fi, f ->
        val (all, pageH) = load(f)
        val lines = all.filter { it.conf >= 0.5f }
        val now = ArrayList<String>()
        lines.forEach { l ->
            DefaultRuleSet.rules.filter { it.id != "datetime" }.forEach { r ->
                runCatching { r.findIn(l.text) }.getOrElse { emptyList() }.forEach { now += l.text.substring(it.range) }
            }
        }
        val proto = ArrayList<String>()
        lines.forEach { l -> findIds(l.text).forEach { proto += l.text.substring(it) } }
        proto += namesNearIds(lines)
        lines.forEach { l -> MORE_LABELS.findIn(l.text).forEach { proto += l.text.substring(it.range) } }
        lines.forEach { l -> HONORIFIC.findAll(l.text).forEach { proto += it.value } }
        lines.filter { it.t < 0.15f * pageH }.forEach { l -> CHAT_TITLE.find(l.text.trim())?.let { proto += it.groupValues[1] } }

        val t0 = System.nanoTime()
        val hanlp = lines.flatMap { l -> HanLP.segment(l.text).filter { it.nature.toString().startsWith("nr") }.map { it.word } }
        val ms = (System.nanoTime() - t0) / 1e6

        fun arr(xs: List<String>) = xs.joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        result.append("\"${f.nameWithoutExtension}\": {\"now\": ${arr(now)}, \"proto\": ${arr(proto)}, \"hanlp\": ${arr(hanlp)}, \"hanlp_ms\": $ms}")
        result.append(if (fi < files.lastIndex) ",\n" else "\n")
    }
    File(args[1]).writeText(result.append("}\n").toString())
}
