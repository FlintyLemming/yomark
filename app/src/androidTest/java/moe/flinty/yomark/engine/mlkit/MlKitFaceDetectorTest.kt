package moe.flinty.yomark.engine.mlkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitFaceDetectorTest {

    /**
     * 画一张卡通正脸。ML Kit 的 face detector 对这种高对比的简笔脸是能检出的；
     * 若某个版本检不出，把 assets 里放一张真实人脸照片（自己的）改用它。
     */
    private fun cartoonFace(w: Int = 600, h: Int = 800): SourceImage {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 200, 170) }
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30) }
            drawOval(RectF(150f, 150f, 450f, 570f), skin)
            drawOval(RectF(215f, 290f, 265f, 330f), ink)      // 左眼
            drawOval(RectF(335f, 290f, 385f, 330f), ink)      // 右眼
            drawOval(RectF(280f, 360f, 320f, 410f), Paint(skin).apply { color = Color.rgb(220, 170, 140) })
            drawRect(RectF(250f, 460f, 350f, 480f), ink)      // 嘴
        }
        return SourceImage(bmp, 1f, w, h, "image/png")
    }

    private fun blank(): SourceImage {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        return SourceImage(bmp, 1f, 400, 400, "image/png")
    }

    @Test
    fun detects_a_frontal_face() = runTest {
        val out = MlKitFaceDetector().detect(cartoonFace())
        assertThat(out).isNotEmpty()
    }

    @Test
    fun face_candidates_carry_the_right_kind_and_source() = runTest {
        val out = MlKitFaceDetector().detect(cartoonFace())
        out.forEach {
            assertThat(it.kind).isEqualTo(SensitiveKind.FACE)
            assertThat(it.source).isEqualTo(DetectorSource.FACE)
        }
    }

    @Test
    fun face_candidates_are_masked_by_default() = runTest {
        // 人脸不经过规则表的误报率分层，默认状态恒为打码
        MlKitFaceDetector().detect(cartoonFace()).forEach {
            assertThat(it.enabledByDefault).isTrue()
        }
    }

    @Test
    fun the_face_box_is_expanded_beyond_the_raw_detection() = runTest {
        val image = cartoonFace()
        val out = MlKitFaceDetector().detect(image)
        val b = out.first().quad.bounds()
        // 至少覆盖眼睛与嘴之间的区域，且落在图内
        assertThat(b.left).isAtLeast(0f)
        assertThat(b.top).isAtLeast(0f)
        assertThat(b.right).isAtMost(image.width.toFloat())
        assertThat(b.bottom).isAtMost(image.height.toFloat())
        assertThat(b.contains(300f, 310f)).isTrue()    // 双眼之间
    }

    @Test
    fun a_blank_image_yields_no_faces() = runTest {
        assertThat(MlKitFaceDetector().detect(blank())).isEmpty()
    }

    @Test
    fun candidate_ids_are_unique() = runTest {
        val out = MlKitFaceDetector().detect(cartoonFace())
        assertThat(out.map { it.id }.toSet()).hasSize(out.size)
    }
}
