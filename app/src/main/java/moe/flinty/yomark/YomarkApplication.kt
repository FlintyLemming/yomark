package moe.flinty.yomark

import android.app.Application
import moe.flinty.yomark.rules.PersonNameRecognizer
import kotlin.concurrent.thread

class YomarkApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 人名识别的词典第一次用时才读，近 30 MB；启动时在后台先读掉，第一张图的识别就不用等它。
        // 失败也无妨：识别时会再读一次，读不出来那条规则就不出结果，其余照常。
        thread(name = "hanlp-warmup", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            runCatching { PersonNameRecognizer.warmUp() }
        }
    }
}
