// 设置一级页彩色图标的配色，输出 Kotlin 源码到 stdout。
//
// 每一项一个色相：圆底取色调 90、图标取色调 30（Material Color Utilities 的 TonalPalette），
// 和主题色的 primaryContainer 同一档明度，换哪个主题色都不跳；色相固定，不随主题色变，
// 认图标靠的就是「文字是蓝的、人脸是橙的」。
//
// 圆底的彩度压到 CONTAINER_MAX_CHROMA：色调 90 上蓝、紫、粉、橙的彩度本来就只到 14–20，
// 绿和青能到 40 以上，不压的话这两个会亮得扎眼。图标保留种子色的彩度。
//
//   cd tools/theme && npm install
//   node gen_settings_icon_colors.mjs > ../../app/src/main/java/com/yomark/app/ui/settings/SettingsIconColors.kt
//
// 增删颜色后重跑一遍。
import { Hct, TonalPalette } from '@material/material-color-utilities';

const CONTAINER_TONE = 90;
const CONTENT_TONE = 30;
const CONTAINER_MAX_CHROMA = 24;

// [名字, 种子色, 彩度上限]。灰色是蓝色压到几乎没有彩度，给「恢复默认」这种不属于任何一类的项。
const SEEDS = [
  ['blue', 0xff1a73e8, null],
  ['orange', 0xfff57c00, null],
  ['green', 0xff43a047, null],
  ['purple', 0xff6750a4, null],
  ['teal', 0xff00897b, null],
  ['pink', 0xffd81b60, null],
  ['gray', 0xff1a73e8, 6],
];

const hex = (argb) => '0x' + (argb >>> 0).toString(16).toUpperCase().padStart(8, '0');
const seedHex = (argb) => '#' + (argb & 0xffffff).toString(16).toUpperCase().padStart(6, '0');

const out = [];
out.push('package com.yomark.app.ui.settings');
out.push('');
out.push('import androidx.compose.ui.graphics.Color');
out.push('');
out.push('// 由 tools/theme/gen_settings_icon_colors.mjs 生成，不要手改。');
out.push(`// 每种颜色是种子色的两条色调（Material Color Utilities 的 TonalPalette）：圆底取 ${CONTAINER_TONE}、彩度不超过 ${CONTAINER_MAX_CHROMA}，图标取 ${CONTENT_TONE}。`);
out.push('');
out.push('internal object SettingsIconColors {');
for (const [name, seed, maxChroma] of SEEDS) {
  const hct = Hct.fromInt(seed);
  const chroma = maxChroma == null ? hct.chroma : Math.min(hct.chroma, maxChroma);
  const container = TonalPalette.fromHueAndChroma(hct.hue, Math.min(chroma, CONTAINER_MAX_CHROMA));
  const content = TonalPalette.fromHueAndChroma(hct.hue, chroma);
  out.push('');
  out.push(`    /** 种子色 ${seedHex(seed)}${maxChroma == null ? '' : `，彩度压到 ${maxChroma}`} */`);
  out.push(`    val ${name} = SettingsIconColor(`);
  out.push(`        container = Color(${hex(container.tone(CONTAINER_TONE))}),`);
  out.push(`        content = Color(${hex(content.tone(CONTENT_TONE))}),`);
  out.push('    )');
}
out.push('}');
console.log(out.join('\n'));
