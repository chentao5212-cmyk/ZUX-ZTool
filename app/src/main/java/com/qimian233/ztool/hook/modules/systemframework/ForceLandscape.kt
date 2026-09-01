package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method

/**
 * 强制全局横屏 Hook 模块（系统框架端）。
 *
 * Hook AOSP DisplayRotation / ZuiDisplayRotation 的 rotationForOrientation(int, int) 方法，
 * 让请求竖屏（PORTRAIT / REVERSE_PORTRAIT / SENSOR_PORTRAIT）的应用被强制改横屏。
 *
 * 横屏/跟随系统/用户手动 180° 旋转等场景不修改返回值，保留原方法的逻辑：
 *   - App 请求 LANDSCAPE / REVERSE_LANDSCAPE：原方法按 mLandscapeRotation / mSeascapeRotation 决定
 *   - App 请求 UNSPECIFIED（跟随系统）：原方法按 mUserRotation / 传感器决定
 *   - 关闭自动旋转后手动点转屏图标：mUserRotation 改变，rotationForOrientation 返回值变化，UI 横竖变化
 *   - 横屏状态下手动 180° 旋转：mLandscapeRotation ↔ mSeascapeRotation 切换
 *
 * 参考 KeepRotation 的 hook 模式（同样作用于 ZuiDisplayRotation），
 * 这里多候选两个类尝试（AOSP + ZUI），运行时检查 isEnabled() 动态开关。
 */
@SuppressLint("PrivateApi")
class ForceLandscape : SystemHookModule() {

    override fun getModuleName(): String = PreferenceKeys.FORCE_LANDSCAPE.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader
        // ZUI DisplayRotation 子类优先（AOSP 父类为兜底）
        val candidates = listOf(
            "com.zui.server.wm.ZuiDisplayRotation",
            "com.android.server.wm.DisplayRotation"
        )

        for (className in candidates) {
            try {
                hookRotationForOrientation(classLoader, className)
                logger.info("Hooked $className.rotationForOrientation [OK]")
                return
            } catch (t: Throwable) {
                logger.warn("Failed to hook $className.rotationForOrientation: ${t.message}")
            }
        }
        logger.error("All ForceLandscape hook attempts failed")
    }

    private fun hookRotationForOrientation(classLoader: ClassLoader, className: String) {
        val cls = classLoader.loadClass(className)
        val method: Method = cls.getDeclaredMethod(
            "rotationForOrientation",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true

        hookWithId(method, "force_landscape_${className.substringAfterLast('.')}") { chain: XposedInterface.Chain? ->
            // 运行时检查开关：支持动态启用/禁用，无需重启
            if (!isEnabled()) {
                return@hookWithId chain!!.proceed()
            }

            val orientation = chain!!.getArg(0) as Int
            // 只在 App 明确要求竖屏时强制改横屏；其他情况保留原方法逻辑
            val forcedToLandscape = orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

            if (!forcedToLandscape) {
                chain.proceed()  // 横屏 / 跟随系统 / 用户旋转：让原方法处理
            } else {
                // 锁竖屏的 App 走横屏方向，但必须跟随用户当前握姿，避免画面颠倒。
                // 0/90/180/270 与 mLandscapeRotation/mUpsideDownRotation/mSeascapeRotation/
                // mPortraitRotation 是一一对应的，直接返回 mUserRotation 都是正确方向。
                val self = chain.thisObject
                self.javaClass.getDeclaredField("mUserRotation").apply {
                    isAccessible = true
                }.getInt(self)
            }
        }
    }
}
