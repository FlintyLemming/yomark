// 由种子色生成预设主题色的浅色配色表，输出 Kotlin 源码到 stdout。
//
// 用的是 Material Color Utilities 的 SchemeContent：和 Android 12 起系统按壁纸取色（莫奈取色）
// 同一套色调体系，但主色尽量保住种子色的彩度——用户挑了红色，按钮就该是红的，
// 而不是莫奈默认的 TonalSpot 那样褪成砖红、和橙色分不太开。
//
//   cd tools/theme && npm install
//   node gen_theme_palettes.mjs > ../../app/src/main/java/com/youma/app/ui/theme/ThemePalettes.kt
//
// 改种子色或增删预设后重跑一遍，并同步 ThemeColor 枚举。
import {
  Hct,
  MaterialDynamicColors as C,
  SchemeContent,
} from '@material/material-color-utilities';

// 紫色不在这里：它就是 Compose 自带的 lightColorScheme()，即本应用原来的配色。
const SEEDS = [
  ['blue', 0xff1a73e8],
  ['teal', 0xff00897b],
  ['green', 0xff43a047],
  ['orange', 0xfff57c00],
  ['red', 0xffe53935],
  ['pink', 0xffd81b60],
];

// Compose lightColorScheme 的参数，按它的声明顺序
const ROLES = [
  'primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer', 'inversePrimary',
  'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
  'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
  'background', 'onBackground', 'surface', 'onSurface', 'surfaceVariant', 'onSurfaceVariant',
  'surfaceTint', 'inverseSurface', 'inverseOnSurface',
  'error', 'onError', 'errorContainer', 'onErrorContainer',
  'outline', 'outlineVariant', 'scrim',
  'surfaceBright', 'surfaceContainer', 'surfaceContainerHigh', 'surfaceContainerHighest',
  'surfaceContainerLow', 'surfaceContainerLowest', 'surfaceDim',
];

const hex = (argb) => '0x' + (argb >>> 0).toString(16).toUpperCase().padStart(8, '0');

const out = [];
out.push('package com.youma.app.ui.theme');
out.push('');
out.push('import androidx.compose.material3.ColorScheme');
out.push('import androidx.compose.material3.lightColorScheme');
out.push('import androidx.compose.ui.graphics.Color');
out.push('');
out.push('// 由 tools/theme/gen_theme_palettes.mjs 生成，不要手改。');
out.push('// 算法是 Material Color Utilities 的 SchemeContent（主色贴近种子色），每套注明了种子色。');
out.push('');
out.push('internal object ThemePalettes {');
for (const [name, seed] of SEEDS) {
  const scheme = new SchemeContent(Hct.fromInt(seed), false, 0);
  out.push('');
  out.push(`    /** 种子色 #${(seed & 0xffffff).toString(16).toUpperCase().padStart(6, '0')} */`);
  out.push(`    val ${name}: ColorScheme = lightColorScheme(`);
  for (const role of ROLES) {
    out.push(`        ${role} = Color(${hex(C[role].getArgb(scheme))}),`);
  }
  out.push('    )');
}
out.push('}');
console.log(out.join('\n'));
