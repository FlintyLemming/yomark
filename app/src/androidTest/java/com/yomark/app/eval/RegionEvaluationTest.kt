package com.yomark.app.eval

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yomark.app.core.image.SourceImage
import com.yomark.app.core.model.SensitiveKind
import com.yomark.app.engine.buildEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M3 出口（spec §12）：正脸召回 ≥ 0.98，二维码 100%。
 *
 * 这两个指标必须在**真实图片**上量。把素材放进：
 *   app/src/androidTest/assets/faces/     每张图恰好一张正脸
 *   app/src/androidTest/assets/qrcodes/   每张图恰好一个二维码
 *
 * 素材来源要求同 docs/eval-sample-set.md：只用自己的照片、公开样张或自造数据。
 * 目录为空时测试会跳过而不是失败——但 M3 不能在跳过的情况下声称达标。
 *
 * 注意 assets 的 Context：androidTest 的 assets 打进的是**测试 APK**，
 * 只能用 `getInstrumentation().context` 读。计划里写的 `targetContext.assets`
 * 指向的是被测应用自己的 assets（src/main/assets），那里永远是空的——
 * 两个指标会静默跳过，跑出一片绿却什么都没量。
 */
@RunWith(AndroidJUnit4::class)
class RegionEvaluationTest {

    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun load(dir: String): List<Pair<String, SourceImage>> {
        val names = runCatching { testContext.assets.list(dir)?.toList() ?: emptyList() }.getOrDefault(emptyList())
        return names.filter { it.endsWith(".jpg") || it.endsWith(".png") }.sorted().mapNotNull { name ->
            val bmp = testContext.assets.open("$dir/$name").use { BitmapFactory.decodeStream(it) }
                ?: return@mapNotNull null
            name to SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png")
        }
    }

    @Test
    fun frontal_face_recall_is_at_least_98_percent() = runTest {
        val images = load("faces")
        assumeTrue("androidTest/assets/faces/ 为空，跳过 M3 人脸指标", images.isNotEmpty())

        val engine = buildEngine(appContext)
        var hit = 0
        images.forEach { (name, img) ->
            val found = engine.analyze(img).candidates.any { it.kind == SensitiveKind.FACE }
            if (found) hit++ else println("YOMARK-EVAL miss face: $name")
        }
        val recall = hit.toDouble() / images.size
        println("YOMARK-EVAL face recall: $recall over ${images.size}")
        assertThat(recall).isAtLeast(0.98)
    }

    @Test
    fun qr_code_detection_is_perfect() = runTest {
        val images = load("qrcodes")
        assumeTrue("androidTest/assets/qrcodes/ 为空，跳过 M3 二维码指标", images.isNotEmpty())

        val engine = buildEngine(appContext)
        val missed = ArrayList<String>()
        images.forEach { (name, img) ->
            val found = engine.analyze(img).candidates.any { it.kind == SensitiveKind.BARCODE }
            if (!found) {
                missed += name
                println("YOMARK-EVAL miss qr: $name")
            }
        }
        println("YOMARK-EVAL qr detection: ${images.size - missed.size}/${images.size}")
        assertThat(missed).isEmpty()
    }
}
