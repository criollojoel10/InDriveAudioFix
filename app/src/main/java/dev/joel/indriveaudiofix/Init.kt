package dev.joel.indriveaudiofix

import android.content.Context
import android.media.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class Init : IXposedHookLoadPackage {

    // Apunta SOLO al paquete de inDrive (conductor)
    private val targetPkgs = setOf("sinet.startup.inDriver")

    // USAGE objetivo (recomendado para avisos en Android Auto)
    private val TARGET_USAGE = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    // Alternativa si prefieres tratarlo como media:
    // private val TARGET_USAGE = AudioAttributes.USAGE_MEDIA

    // Pide focus transitorio (may duck) al iniciar, y lo abandona al parar
    private val REQUEST_TRANSIENT_FOCUS = true

    // Si inDrive intentara “modo llamada” (SCO) que rompa AA, puedes bloquearlo
    private val SUPPRESS_IN_COMM_MODE = false

    override fun handleLoadPackage(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName !in targetPkgs) return

        fun fixAA(aa: AudioAttributes): AudioAttributes {
            // Respeta MEDIA/NAV si ya viene; si es UNKNOWN u otro, fuerza TARGET_USAGE
            return if (aa.usage == AudioAttributes.USAGE_MEDIA || aa.usage == TARGET_USAGE) aa
            else AudioAttributes.Builder(aa).setUsage(TARGET_USAGE).build()
        }

        // 1) Hook a AudioAttributes.Builder.build()
        val AAB = XposedHelpers.findClass("android.media.AudioAttributes\$Builder", lpp.classLoader)
        XposedHelpers.findAndHookMethod(AAB, "build", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val aa = p.result as AudioAttributes
                val fixed = fixAA(aa)
                if (aa.usage != fixed.usage) {
                    XposedBridge.log("InDriveAudioFix: AA.build() ${aa.usage} -> ${fixed.usage}")
                    p.result = fixed
                }
            }
        })

        // 2) Red de seguridad: MediaPlayer.setAudioAttributes(...)
        XposedHelpers.findAndHookMethod(
            "android.media.MediaPlayer", lpp.classLoader,
            "setAudioAttributes", AudioAttributes::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val orig = p.args[0] as AudioAttributes
                    val fixed = fixAA(orig)
                    if (orig.usage != fixed.usage) {
                        XposedBridge.log("InDriveAudioFix: MP.setAA ${orig.usage} -> ${fixed.usage}")
                        p.args[0] = fixed
                    }
                }
            }
        )

        // 3) SoundPool.Builder.setAudioAttributes(...) (por si usa efectos cortos)
        runCatching {
            val SPB = XposedHelpers.findClass("android.media.SoundPool\$Builder", lpp.classLoader)
            XposedHelpers.findAndHookMethod(
                SPB, "setAudioAttributes", AudioAttributes::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val orig = p.args[0] as AudioAttributes
                        val fixed = fixAA(orig)
                        if (orig.usage != fixed.usage) {
                            XposedBridge.log("InDriveAudioFix: SP.setAA ${orig.usage} -> ${fixed.usage}")
                            p.args[0] = fixed
                        }
                    }
                }
            )
        }

        // 4) (Opcional) Focus transitorio al start()/stop()/release()
        if (REQUEST_TRANSIENT_FOCUS) {
            val mpClass = XposedHelpers.findClass("android.media.MediaPlayer", lpp.classLoader)
            XposedHelpers.findAndHookMethod(mpClass, "start", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    requestTransientFocus(p.thisObject)
                }
            })
            listOf("stop", "release").forEach { m ->
                XposedHelpers.findAndHookMethod(mpClass, m, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        abandonFocus(p.thisObject)
                    }
                })
            }
        }

        // 5) (Opcional) bloquear MODE_IN_COMMUNICATION si diera problemas con AA
        if (SUPPRESS_IN_COMM_MODE) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    "android.media.AudioManager", lpp.classLoader,
                    "setMode", Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            if (p.args[0] as Int == AudioManager.MODE_IN_COMMUNICATION) {
                                XposedBridge.log("InDriveAudioFix: suppress MODE_IN_COMMUNICATION")
                                p.result = null // cancela
                            }
                        }
                    }
                )
            }
        }

        XposedBridge.log("InDriveAudioFix: hooks loaded in ${lpp.packageName}")
    }

    private fun requestTransientFocus(player: Any) {
        try {
            val ctx = getPlayerContext(player) ?: return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(TARGET_USAGE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (_: Throwable) {}
    }

    private fun abandonFocus(player: Any) {
        try {
            val ctx = getPlayerContext(player) ?: return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(TARGET_USAGE).build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                am.abandonAudioFocusRequest(req)
            } else {
                @Suppress("DEPRECATION") am.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {}
    }

    private fun getPlayerContext(player: Any): Context? =
        runCatching { XposedHelpers.getObjectField(player, "mContext") as? Context }.getOrNull()
}
