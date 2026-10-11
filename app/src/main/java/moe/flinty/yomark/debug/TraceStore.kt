package moe.flinty.yomark.debug

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 调试记录落盘：一次识别一个文件（`<编号>.json`），放在应用私有的 noBackupFilesDir 下——
 * 别的应用读不到，也不进云备份和换机迁移（清单里本来就关了 allowBackup，这里再保险一层）。
 *
 * 一次一个文件，是因为识别过程中每记一节就要重写一遍：整份记录放一个文件的话，留一百次时每一节都要重写几十 MB。
 * 写用 AtomicFile，写到一半被杀，读回来的是上一版；读不出来的文件（老版本格式、写坏了）跳过并删掉。
 */
class TraceStore(private val dir: File) {

    /** 磁盘上最大的编号，只看文件名不读内容。没有记录是 0。 */
    fun maxNumber(): Int = files().maxOfOrNull { it.first } ?: 0

    /** 全部读回来，最新的在前。 */
    fun readAll(): List<TraceRun> = files()
        .sortedByDescending { it.first }
        .mapNotNull { (number, file) ->
            runCatching { AtomicFile(file).readFully().decodeToString().let(::decodeRun) }
                .getOrElse { delete(number); null }
        }

    fun write(run: TraceRun) {
        dir.mkdirs()
        val file = AtomicFile(fileOf(run.number))
        val out = file.startWrite()
        try {
            out.write(encodeRun(run).toByteArray())
            file.finishWrite(out)
        } catch (e: Throwable) {
            file.failWrite(out)
            throw e
        }
    }

    fun delete(number: Int) {
        AtomicFile(fileOf(number)).delete()
    }

    fun deleteAll() {
        dir.deleteRecursively()
    }

    private fun fileOf(number: Int) = File(dir, "$number.json")

    private fun files(): List<Pair<Int, File>> =
        dir.listFiles().orEmpty().mapNotNull { f -> f.name.removeSuffix(".json").toIntOrNull()?.let { it to f } }
}

internal fun encodeRun(run: TraceRun): String = JSONObject()
    .put("number", run.number)
    .put("title", run.title)
    .put("startedAt", run.startedAt)
    .apply { run.durationMs?.let { put("durationMs", it) } }
    .put("sections", JSONArray(run.sections.map { s ->
        JSONObject()
            .put("stage", s.stage.name)
            .put("title", s.title)
            .put("summary", s.summary)
            .put("lines", JSONArray(s.lines))
            .put("blocks", JSONArray(s.blocks.map { b -> JSONObject().put("label", b.label).put("text", b.text) }))
    }))
    .toString()

internal fun decodeRun(text: String): TraceRun {
    val o = JSONObject(text)
    return TraceRun(
        number = o.getInt("number"),
        title = o.getString("title"),
        startedAt = o.getLong("startedAt"),
        durationMs = if (o.has("durationMs")) o.getLong("durationMs") else null,
        sections = o.getJSONArray("sections").objects().map { s ->
            TraceSection(
                stage = TraceStage.valueOf(s.getString("stage")),
                title = s.getString("title"),
                summary = s.getString("summary"),
                lines = s.getJSONArray("lines").let { a -> List(a.length()) { a.getString(it) } },
                blocks = s.getJSONArray("blocks").objects().map { b -> TraceBlock(b.getString("label"), b.getString("text")) },
            )
        },
    )
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
