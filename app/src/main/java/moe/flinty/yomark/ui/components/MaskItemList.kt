package moe.flinty.yomark.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskState
import moe.flinty.yomark.core.model.SensitiveKindLabels
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 宽屏的框列表：画面上框出来的每一块——识别出来的和手动画的——按从上到下、从左到右排成一列，
 * 每一行是这一块在原图上的缩略图、类型和状态。
 *
 * 点识别出的一行就是打码 / 取消打码，和在画布上点它一样；点手动框那一行是选中它（手动框不切换，spec §7.3），
 * 选中后照常在图上拖、在样式面板上改、点「删除」。
 *
 * 缩略图截的是原图，已经打了码的也是：在这儿就看得见码底下原来是什么，不用先取消打码再看。
 * 列表的顺序只看位置，不看状态：点过的那一行不会跳走。
 *
 * @param analyzing 识别结果还没出来（载入中、识别中、换方案重跑）：只列手动框
 * @param selectedId 选中的手动框
 */
@Composable
fun MaskItemList(
    image: SourceImage?,
    plan: MaskPlan,
    analyzing: Boolean,
    selectedId: String?,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 识别中（含换方案重跑）识别出的块在画布上不画，这里也先不列：这时点了也会被新结果整批换掉
    val items = remember(plan, analyzing) {
        MaskListOrder.of(if (analyzing) plan.items.filter { it.source == DetectorSource.MANUAL } else plan.items)
    }
    val listState = rememberLazyListState()
    // 选中了手动框（刚在图上画完的那个也是选中的）：那一行不在眼前就滚过去，看得见它进了列表
    LaunchedEffect(selectedId) {
        val index = items.indexOfFirst { it.candidateId == selectedId }
        if (index < 0) return@LaunchedEffect
        withFrameNanos { }                         // 等新加的那一行排好，再看它在不在眼前
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == index }
        val shown = row != null && row.offset >= info.viewportStartOffset &&
            row.offset + row.size <= info.viewportEndOffset
        if (!shown) listState.animateScrollToItem(index)
    }
    Column(modifier) {
        ListSummary(items, analyzing)
        if (items.isEmpty()) {
            // 打不开图的时候也没有什么可说的
            if (!analyzing && image != null) EmptyHint()
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.candidateId }) { item ->
                    MaskItemRow(
                        image = image,
                        item = item,
                        selected = item.candidateId == selectedId,
                        onClick = { onItemClick(item.candidateId) },
                    )
                }
            }
        }
    }
}

/** 列表上方一行：一共几处、还有几处没打码。没打码的那几处也是导出前会被提醒的那几处（spec §7.4）。 */
@Composable
private fun ListSummary(items: List<MaskItem>, analyzing: Boolean) {
    val pending = items.count { it.state == MaskState.OUTLINED }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                analyzing -> "识别中…"
                items.isEmpty() -> "框出的内容"
                else -> "框出 ${items.size} 处"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.weight(1f))
        if (pending > 0) {
            Box(Modifier.size(8.dp).background(OutlineAmber, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                "$pending 处未打码",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!analyzing && items.isNotEmpty()) {
            Text(
                "都已打码",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            "没有识别到敏感信息",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "有漏掉的地方，在图上用一根手指拖出一个框",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 一行：缩略图、类型、状态，识别出的那几行右边再带一个开关。整行都是点击区，开关只是显示状态。
 * 圈出未打码的描一道琥珀色的边，和画布上的虚线框同一个颜色。
 */
@Composable
private fun MaskItemRow(image: SourceImage?, item: MaskItem, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val manual = item.source == DetectorSource.MANUAL
    val masked = item.state == MaskState.MASKED
    val shape = RoundedCornerShape(16.dp)
    val interaction = if (manual) {
        Modifier.selectable(selected = selected, role = Role.Button, onClick = onClick)
    } else {
        Modifier.toggleable(value = masked, role = Role.Switch, onValueChange = { onClick() })
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
            .then(
                when {
                    selected -> Modifier.border(2.dp, colors.primary, shape)
                    !masked -> Modifier.border(1.5.dp, OutlineAmber, shape)
                    else -> Modifier
                },
            )
            .then(interaction)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemThumbnail(image, item.quad, Modifier.weight(1f).height(THUMB_HEIGHT))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.widthIn(min = 48.dp, max = 112.dp)) {
            Text(
                SensitiveKindLabels.display(item),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    manual && selected -> "已选中"
                    manual -> "手动画的框"
                    masked -> "已打码"
                    else -> "未打码"
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (!manual) {
            Spacer(Modifier.width(8.dp))
            // 状态由整行的 toggleable 报给读屏，开关自己不接点击
            Switch(checked = masked, onCheckedChange = null)
        }
    }
}

/** 这一块在原图上的样子。截不到（手动框整个拖出了图外）就只留底色。 */
@Composable
private fun ItemThumbnail(image: SourceImage?, quad: Quad, modifier: Modifier) {
    val bitmap = image?.bitmap
    val thumb = remember(bitmap, quad) { bitmap?.let { ItemThumbnails.crop(it, quad) }?.asImageBitmap() }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(thumb, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

private val THUMB_HEIGHT = 44.dp

/** 圈出框的琥珀色（spec §7.2），与画布上的虚线框、类型小标签同一个颜色。 */
private val OutlineAmber = Color(0xFFFFB300)

/**
 * 列表的顺序：先按行、再按左右，和读这一页的顺序一样。
 *
 * 「同一行」要两块互相盖得住对方的竖直中点：同一行字的几段（人名和后面的电话）高矮差不多，一定算一行；
 * 左边一张大头像和右边的几行字，只有中点挨着头像中点的那一行和它算一行，其余照样一行一行往下排。
 * 只看位置，打码、取消打码都不改顺序。
 */
internal object MaskListOrder {

    fun of(items: List<MaskItem>): List<MaskItem> {
        // 拖手动框时每一帧都要排一遍，外接矩形先算好
        val byCenter = items.map { it to it.quad.bounds() }
            .sortedWith(compareBy({ it.second.centerY() }, { it.second.left }))
        val lines = mutableListOf<MutableList<Pair<MaskItem, RectF>>>()
        for (entry in byCenter) {
            val line = lines.lastOrNull()
            if (line != null && sameLine(line.first().second, entry.second)) line += entry else lines += mutableListOf(entry)
        }
        return lines.flatMap { line -> line.sortedBy { it.second.left }.map { it.first } }
    }

    /** [anchor] 是这一行最先排进来的那一块。 */
    private fun sameLine(anchor: RectF, box: RectF): Boolean =
        box.centerY() in anchor.top..anchor.bottom && anchor.centerY() in box.top..box.bottom
}

/** 列表缩略图：从分析图上截下这一块的外接矩形，四周留一点边，太大的缩小。 */
internal object ItemThumbnails {

    /** 四周留的边：外接矩形短边的这么多倍，至少 [MIN_PAD_PX]。一行字上下留一点，看得出是哪一行。 */
    private const val PAD_RATIO = 0.25f
    private const val MIN_PAD_PX = 2f

    /** 缩略图最大的宽高（像素）。列表一行四十来 dp 高，长条的一行字横着最多两三百 dp，这些足够看清。 */
    const val MAX_WIDTH = 720
    const val MAX_HEIGHT = 240

    /** 要截的范围（图像像素），已收进图内。和图没有交集时返回 null。 */
    fun cropRect(quad: Quad, imageWidth: Int, imageHeight: Int): Rect? {
        val b = quad.bounds()
        val pad = max(MIN_PAD_PX, min(b.width(), b.height()) * PAD_RATIO)
        val left = floor(b.left - pad).toInt().coerceIn(0, imageWidth)
        val top = floor(b.top - pad).toInt().coerceIn(0, imageHeight)
        val right = ceil(b.right + pad).toInt().coerceIn(0, imageWidth)
        val bottom = ceil(b.bottom + pad).toInt().coerceIn(0, imageHeight)
        if (right - left < 1 || bottom - top < 1) return null
        return Rect(left, top, right, bottom)
    }

    fun crop(bitmap: Bitmap, quad: Quad): Bitmap? {
        val r = cropRect(quad, bitmap.width, bitmap.height) ?: return null
        val shrink = min(1f, min(MAX_WIDTH.toFloat() / r.width(), MAX_HEIGHT.toFloat() / r.height()))
        val matrix = Matrix().apply { if (shrink < 1f) setScale(shrink, shrink) }
        return Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height(), matrix, true)
    }
}
