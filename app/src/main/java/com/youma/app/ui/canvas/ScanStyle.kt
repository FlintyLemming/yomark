package com.youma.app.ui.canvas

/**
 * 识别动效的样式，在设置里切换（见 SettingsStore.scanStyle）。只影响编辑器里的过渡，不影响识别。
 *
 * 枚举名是持久化格式的一部分：改名或删掉一项，旧安装里存着的值就读不回来（读不回来退回出厂的 [SWEEP]）。
 */
enum class ScanStyle {
    /** 扫光：一束柔光自上而下扫过，结果紧跟在光后面自上而下落下（见 ScanEffect）。出厂默认。 */
    SWEEP,

    /**
     * 磨砂：整页模糊、压暗，闪着细碎的光点，中间转着一个边转边变形的等待图标；
     * 识别完从中心散开（见 FrostEffect）。照着 Android 原生壁纸生成时的加载动效做的。
     */
    FROST,
}
