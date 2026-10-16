package moe.flinty.yomark.ui.onboarding

import android.graphics.PointF
import android.os.Parcel
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 引导里点一下图怎么变、换步、聊天的码：和编辑器的规矩一样（EditorViewModel.onTap）。 */
@RunWith(RobolectricTestRunner::class)
class TourStateTest {

    private fun state(chatOn: Boolean = false, progress: TourProgress = TourProgress(revealed = true)) =
        TourState(mutableStateOf(progress), chatOn)

    private fun ids(s: TourState) = s.plan.items.map { it.candidateId }

    private val phone = TourSample.AUTO.single { it.kind == SensitiveKind.PHONE }

    @Test fun `tapping the link masks that one piece like the editor does`() {
        val s = state()
        s.onTap(TourSample.LINK_CENTER, 0f)
        val line = s.plan.find(TourSample.lineId(TourSample.LINK_INDEX))!!
        assertThat(line.quad).isEqualTo(TourSample.LINK)
        assertThat(line.kind).isEqualTo(SensitiveKind.MANUAL)
        assertThat(line.source).isEqualTo(DetectorSource.LINE)
        assertThat(s.progress.tapped).isTrue()
        assertThat(s.linkMasked).isTrue()
        assertThat(s.plan.items).hasSize(TourSample.AUTO.size + 1)
    }

    @Test fun `tapping the link mask again takes it off`() {
        val s = state()
        s.onTap(TourSample.LINK_CENTER, 0f)
        s.onTap(TourSample.LINK_CENTER, 0f)
        assertThat(s.linkMasked).isFalse()
        assertThat(ids(s)).containsExactlyElementsIn(TourSample.AUTO.map { it.candidateId })
        // 点上过就算学会了：提示不再回来
        assertThat(s.progress.tapped).isTrue()
    }

    @Test fun `an auto mask comes off and goes back on in place`() {
        val s = state()
        val p = phone.quad.bounds().let { PointF(it.centerX(), it.centerY()) }
        s.onTap(p, 0f)
        assertThat(s.plan.find(phone.candidateId)).isNull()
        s.onTap(p, 0f)
        // 原样打回去：同一块，不是这一段字的整行
        assertThat(s.plan.find(phone.candidateId)).isSameInstanceAs(phone)
        assertThat(s.progress.toggled).isEmpty()
        assertThat(s.progress.tapped).isFalse()
    }

    @Test fun `tapping the label before an address masks the whole piece`() {
        val s = state()
        s.onTap(PointF(230f, 492f), 0f)    // 「地址：」
        assertThat(s.plan.find(TourSample.lineId(5))!!.quad).isEqualTo(TourSample.LINES[5])
    }

    @Test fun `a tap just beside the link still hits it within the finger's reach`() {
        val s = state()
        val below = TourSample.LINK.bounds().bottom + 10f
        s.onTap(PointF(443f, below), 18f)
        assertThat(s.linkMasked).isTrue()
        // 再点一下同一处：已经遮上的那段旁边，不再叠一块
        s.onTap(PointF(443f, below), 18f)
        assertThat(s.plan.items.count { it.source == DetectorSource.LINE }).isEqualTo(1)
    }

    @Test fun `blank taps do nothing`() {
        val s = state()
        s.onTap(PointF(980f, 1300f), 0f)
        assertThat(s.progress).isEqualTo(TourProgress(revealed = true))
    }

    @Test fun `taps before the masks land are ignored`() {
        val s = state(progress = TourProgress())
        assertThat(s.settled).isFalse()
        s.onTap(TourSample.LINK_CENTER, 0f)
        assertThat(s.linkMasked).isFalse()
    }

    @Test fun `leaving the scan page lands the masks with the same step`() {
        val s = state(progress = TourProgress())
        // 欢迎页之后才演识别动效
        s.goTo(s.autoStep)
        assertThat(s.progress).isEqualTo(TourProgress(step = 1, revealed = false))
        s.goTo(s.autoStep + 1)
        assertThat(s.progress).isEqualTo(TourProgress(step = 2, revealed = true))
        s.goTo(s.pages.size)
        assertThat(s.progress.step).isEqualTo(s.lastStep)
        s.goTo(0)
        assertThat(s.progress).isEqualTo(TourProgress(step = 0, revealed = true))
    }

    @Test fun `skipping past the scan page lands the masks too`() {
        val s = state(progress = TourProgress())
        s.goTo(s.autoStep + 2)
        assertThat(s.progress.revealed).isTrue()
    }

    @Test fun `the first run has the engine page and the replay does not`() {
        val first = state()
        assertThat(first.pages).containsExactly(
            TourPage.WELCOME, TourPage.AUTO, TourPage.TAP, TourPage.ENGINE, TourPage.MORE,
        ).inOrder()
        val replay = TourState(mutableStateOf(TourProgress()), chatOn = false, pages = TourState.WITHOUT_ENGINE)
        assertThat(replay.pages).doesNotContain(TourPage.ENGINE)
        assertThat(replay.autoStep).isEqualTo(1)
        assertThat(replay.lastStep).isEqualTo(3)
    }

    @Test fun `an engine choice is kept`() {
        val s = state()
        assertThat(s.progress.engineChosen).isFalse()
        s.chooseEngine()
        s.chooseEngine()
        assertThat(s.progress).isEqualTo(TourProgress(revealed = true, engineChosen = true))
    }

    @Test fun `chat masks start shown when the setting is already on`() {
        assertThat(state(chatOn = true).plan.items).hasSize(TourSample.AUTO.size + TourSample.CHAT.size)
        assertThat(state(chatOn = false).plan.items).hasSize(TourSample.AUTO.size)
    }

    @Test fun `turning chat on without animation shows every chat mask at once`() {
        val s = state()
        runBlocking { s.syncChat(on = true, animate = false) { true } }
        assertThat(ids(s)).containsAtLeastElementsIn(TourSample.CHAT.map { it.candidateId })
    }

    @Test fun `turning chat off forgets the chat masks tapped away`() {
        val s = state(chatOn = true)
        val avatar = TourSample.CHAT[1].quad.bounds()
        s.onTap(PointF(avatar.centerX(), avatar.centerY()), 0f)
        assertThat(s.progress.toggled).containsExactly(TourSample.CHAT[1].candidateId)
        runBlocking { s.syncChat(on = false, animate = true) { true } }
        assertThat(s.chatShown).isEqualTo(0)
        assertThat(s.progress.toggled).isEmpty()
        assertThat(ids(s)).containsExactlyElementsIn(TourSample.AUTO.map { it.candidateId })
    }

    /** 进程被杀后靠它接着来：真的写进 Parcel 再读回来（同一进程里 Bundle 只是存着同一个对象）。 */
    @Test fun `progress survives being parcelled`() {
        val progress = TourProgress(step = 2, revealed = true, toggled = listOf("tour-phone", "line-12"), tapped = true, engineChosen = true)
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(progress, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            val back = parcel.readParcelable<TourProgress>(TourProgress::class.java.classLoader)
            assertThat(back).isEqualTo(progress)
            assertThat(back).isNotSameInstanceAs(progress)
        } finally {
            parcel.recycle()
        }
    }
}
