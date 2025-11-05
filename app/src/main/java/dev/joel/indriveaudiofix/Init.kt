package dev.joel.indriveaudiofix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference

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
    
    // MediaSession para Android Auto
    @Volatile
    private var mediaSession: WeakReference<MediaSession>? = null
    private val sessionLock = Any()
    private val NOTIFICATION_ID = 9876
    private val CHANNEL_ID = "indrive_audio_fix"

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

        // 6) Hook MediaPlayer.start() para crear/activar MediaSession para Android Auto
        hookMediaPlayerForAndroidAuto(lpp)

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
    
    // Hooks adicionales para MediaSession y Android Auto
    private fun hookMediaPlayerForAndroidAuto(lpp: XC_LoadPackage.LoadPackageParam) {
        val mpClass = XposedHelpers.findClass("android.media.MediaPlayer", lpp.classLoader)
        
        // Hook para start() - crear/activar MediaSession
        XposedHelpers.findAndHookMethod(mpClass, "start", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    val ctx = getPlayerContext(p.thisObject)
                    if (ctx != null) {
                        ensureMediaSessionActive(ctx)
                        updatePlaybackState(PlaybackState.STATE_PLAYING)
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("InDriveAudioFix: Error in start() hook: ${e.message}")
                }
            }
        })
        
        // Hook para pause()
        XposedHelpers.findAndHookMethod(mpClass, "pause", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                try {
                    updatePlaybackState(PlaybackState.STATE_PAUSED)
                } catch (e: Throwable) {
                    XposedBridge.log("InDriveAudioFix: Error in pause() hook: ${e.message}")
                }
            }
        })
        
        // Hook para stop() - mantener MediaSession pero actualizar estado
        XposedHelpers.findAndHookMethod(mpClass, "stop", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                try {
                    updatePlaybackState(PlaybackState.STATE_STOPPED)
                } catch (e: Throwable) {
                    XposedBridge.log("InDriveAudioFix: Error in stop() hook: ${e.message}")
                }
            }
        })
    }
    
    private fun ensureMediaSessionActive(context: Context) {
        synchronized(sessionLock) {
            val session = mediaSession?.get()
            if (session != null && session.isActive) {
                return // Ya existe y está activa
            }
            
            try {
                // Limpiar sesión anterior si existe pero no está activa
                if (session != null && !session.isActive) {
                    try {
                        session.release()
                    } catch (e: Throwable) {
                        XposedBridge.log("InDriveAudioFix: Error releasing previous session: ${e.message}")
                    }
                }
            
            // Crear nueva MediaSession
            val newSession = MediaSession(context, "InDriveAudioFix")
            
            // Configurar para Android Auto
            newSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            
            // Configurar AudioAttributes para la sesión
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(TARGET_USAGE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            newSession.setPlaybackToLocal(audioAttributes)
            
            // Configurar callbacks básicos
            newSession.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    XposedBridge.log("InDriveAudioFix: MediaSession onPlay()")
                }
                
                override fun onPause() {
                    XposedBridge.log("InDriveAudioFix: MediaSession onPause()")
                }
                
                override fun onStop() {
                    XposedBridge.log("InDriveAudioFix: MediaSession onStop()")
                }
            })
            
            // Activar la sesión
            newSession.isActive = true
            mediaSession = WeakReference(newSession)
            
            // Intentar crear notificación para mantener el contexto activo
            runCatching {
                createNotificationChannel(context)
                createMediaNotification(context)
            }
            
            XposedBridge.log("InDriveAudioFix: MediaSession created and activated")
            } catch (e: Throwable) {
                XposedBridge.log("InDriveAudioFix: Error creating MediaSession: ${e.message}")
            }
        }
    }
    
    private fun updatePlaybackState(state: Int) {
        synchronized(sessionLock) {
            val session = mediaSession?.get() ?: return
            
            try {
                val playbackState = PlaybackState.Builder()
                    .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_PLAY_PAUSE
                    )
                    .build()
                
                session.setPlaybackState(playbackState)
                XposedBridge.log("InDriveAudioFix: PlaybackState updated to $state")
            } catch (e: Throwable) {
                XposedBridge.log("InDriveAudioFix: Error updating playback state: ${e.message}")
            }
        }
    }
    
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "InDrive Audio",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mantiene el audio activo en Android Auto"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            } catch (e: Throwable) {
                XposedBridge.log("InDriveAudioFix: Error creating notification channel: ${e.message}")
            }
        }
    }
    
    private fun createMediaNotification(context: Context) {
        try {
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }.apply {
                setContentTitle("InDrive Audio Activo")
                setContentText("Audio en reproducción para Android Auto")
                setSmallIcon(android.R.drawable.ic_media_play)
                setOngoing(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val session = mediaSession?.get()
                    if (session != null) {
                        setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
                    }
                }
            }.build()
            
            // Publicar notificación para mantener el contexto de MediaSession
            runCatching {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)
                XposedBridge.log("InDriveAudioFix: Notification posted")
            }
        } catch (e: Throwable) {
            XposedBridge.log("InDriveAudioFix: Error creating notification: ${e.message}")
        }
    }
}
