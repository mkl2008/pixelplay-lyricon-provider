package io.github.mkl2008.pixelplaylyricon

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed(modulePackageName = Constants.MODULE_PACKAGE)
open class HookEntry : IYukiHookXposedInit {

    override fun onInit() {
        YukiHookAPI.configs {
            debugLog {
                tag = Constants.TAG
            }
        }
    }

    override fun onHook() = YukiHookAPI.encase {
        loadApp(Constants.TARGET_PACKAGE, PixelPlayerHooker())
    }
}
