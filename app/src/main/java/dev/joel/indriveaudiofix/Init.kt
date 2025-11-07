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

/**
 * Módulo Xposed para corregir problemas de audio en inDrive con Android Auto.
 * 
 * Este módulo intercepta las llamadas de audio de inDrive y las modifica para que
 * funcionen correctamente con Android Auto, forzando el uso de AudioAttributes apropiados
 * y manteniendo una MediaSession activa.
 */
class Init : IXposedHookLoadPackage {

    companion object {
        // Apunta SOLO al paquete de inDrive (conductor)
        private val TARGET_PACKAGES = setOf("sinet.startup.inDriver")
        
        // USAGE objetivo (recomendado para avisos en Android Auto)
        private const val TARGET_USAGE = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        
        // Pide focus transitorio (may duck) al iniciar, y lo abandona al parar
        private const val REQUEST_TRANSIENT_FOCUS = true
        
        // Si inDrive intentara "modo llamada" (SCO) que rompa AA, puedes bloquearlo
        private const val SUPPRESS_IN_COMM_MODE = false
        
        // MediaSession para Android Auto
        private const val NOTIFICATION_ID = 9876
        private const val CHANNEL_ID = "indrive_audio_fix"
        private const val TAG = "InDriveAudioFix"
        
        // Singleton para MediaSession
        @Volatile
        private var mediaSession: WeakReference<MediaSession>? = null
        private val sessionLock = Any()
        
        @Volatile
        private var notificationChannelCreated = false
    }

    override fun handleLoadPackage(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName !in TARGET_PACKAGES) return

        try {
            hookAudioAttributes(lpp)
            hookMediaPlayer(lpp)
            hookSoundPool(lpp)
            
            if (REQUEST_TRANSIENT_FOCUS) {
                hookAudioFocus(lpp)
            }
            
            if (SUPPRESS_IN_COMM_MODE) {
                hookAudioMode(lpp)
            }
            
            hookMediaPlayerForAndroidAuto(lpp)
            
            logInfo("Hooks cargados exitosamente en ${lpp.packageName}")
        } catch (e: Throwable) {
            logError("Error al cargar hooks", e)
        }
    }

    /**
     * Hook para AudioAttributes.Builder.build()
     */
    private fun hookAudioAttributes(lpp: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderClass = XposedHelpers.findClass(
                "android.media.AudioAttributes\$Builder",
                lpp.classLoader
            )
            
            XposedHelpers.findAndHookMethod(
                builderClass,
                "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val original = param.result as? AudioAttributes ?: return
                        val fixed = fixAudioAttributes(original)
                        
                        if (original.usage != fixed.usage) {
                            logInfo("AudioAttributes.build() ${original.usage} -> ${fixed.usage}")
                            param.result = fixed
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            logError("Error al hookear AudioAttributes", e)
        }
    }

    /**
     * Hook para MediaPlayer.setAudioAttributes()
     */
    private fun hookMediaPlayer(lpp: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.media.MediaPlayer",
                lpp.classLoader,
                "setAudioAttributes",
                AudioAttributes::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args[0] as? AudioAttributes ?: return
                        val fixed = fixAudioAttributes(original)
                        
                        if (original.usage != fixed.usage) {
                            logInfo("MediaPlayer.setAudioAttributes() ${original.usage} -> ${fixed.usage}")
                            param.args[0] = fixed
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            logError("Error al hookear MediaPlayer.setAudioAttributes", e)
        }
    }

    /**
     * Hook para SoundPool.Builder.setAudioAttributes()
     */
    private fun hookSoundPool(lpp: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderClass = XposedHelpers.findClass(
                "android.media.SoundPool\$Builder",
                lpp.classLoader
            )
            
            XposedHelpers.findAndHookMethod(
                builderClass,
                "setAudioAttributes",
                AudioAttributes::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args[0] as? AudioAttributes ?: return
                        val fixed = fixAudioAttributes(original)
                        
                        if (original.usage != fixed.usage) {
                            logInfo("SoundPool.setAudioAttributes() ${original.usage} -> ${fixed.usage}")
                            param.args[0] = fixed
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            logError("Error al hookear SoundPool", e)
        }
    }

    /**
     * Hook para manejar Audio Focus en start()/stop()/release()
     */
    private fun hookAudioFocus(lpp: XC_LoadPackage.LoadPackageParam) {
        try {
            val mediaPlayerClass = XposedHelpers.findClass(
                "android.media.MediaPlayer",
                lpp.classLoader
            )
            
            XposedHelpers.findAndHookMethod(
                mediaPlayerClass,
                "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        requestTransientFocus(param.thisObject)
                    }
                }
            )
            
            listOf("stop", "release").forEach { methodName ->
                XposedHelpers.findAndHookMethod(
                    mediaPlayerClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            abandonFocus(param.thisObject)
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            logError("Error al hookear AudioFocus", e)
        }
    }

    /**
     * Hook para bloquear MODE_IN_COMMUNICATION si causa problemas
     */
    private fun hookAudioMode(lpp: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.media.AudioManager",
                lpp.classLoader,
                "setMode",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val mode = param.args[0] as? Int ?: return
                        
                        if (mode == AudioManager.MODE_IN_COMMUNICATION) {
                            logInfo("Bloqueando MODE_IN_COMMUNICATION")
                            param.result = null
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            logError("Error al hookear AudioMode", e)
        }
    }

    /**
     * Hooks para MediaPlayer para crear y actualizar MediaSession
     */
    private fun hookMediaPlayerForAndroidAuto(lpp: XC_LoadPackage.LoadPackageParam) {
        try {
            val mediaPlayerClass = XposedHelpers.findClass(
                "android.media.MediaPlayer",
                lpp.classLoader
            )
            
            // Hook start() para crear/activar MediaSession
            XposedHelpers.findAndHookMethod(
                mediaPlayerClass,
                "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        getPlayerContext(param.thisObject)?.let { context ->
                            ensureMediaSessionActive(context)
                            updatePlaybackState(PlaybackState.STATE_PLAYING)
                        }
                    }
                }
            )
            
            // Hook pause()
            XposedHelpers.findAndHookMethod(
                mediaPlayerClass,
                "pause",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        updatePlaybackState(PlaybackState.STATE_PAUSED)
                    }
                }
            )
            
            // Hook stop()
            XposedHelpers.findAndHookMethod(
                mediaPlayerClass,
                "stop",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        updatePlaybackState(PlaybackState.STATE_STOPPED)
                    }
                }
            )
        } catch (e: Throwable) {
            logError("Error al hookear MediaPlayer para Android Auto", e)
        }
    }

    /**
     * Corrige AudioAttributes para usar el USAGE correcto
     */
    private fun fixAudioAttributes(attributes: AudioAttributes): AudioAttributes {
        return if (attributes.usage == AudioAttributes.USAGE_MEDIA || 
                   attributes.usage == TARGET_USAGE) {
            attributes
        } else {
            AudioAttributes.Builder(attributes)
                .setUsage(TARGET_USAGE)
                .build()
        }
    }

    /**
     * Solicita audio focus transitorio
     */
    private fun requestTransientFocus(player: Any) {
        try {
            val context = getPlayerContext(player) ?: return
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(TARGET_USAGE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                
                val request = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { }
                    .build()
                
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Throwable) {
            logError("Error al solicitar audio focus", e)
        }
    }

    /**
     * Abandona audio focus
     */
    private fun abandonFocus(player: Any) {
        try {
            val context = getPlayerContext(player) ?: return
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(TARGET_USAGE)
                    .build()
                
                val request = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { }
                    .build()
                
                audioManager.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Throwable) {
            logError("Error al abandonar audio focus", e)
        }
    }

    /**
     * Obtiene el contexto del MediaPlayer
     */
    private fun getPlayerContext(player: Any): Context? {
        return try {
            XposedHelpers.getObjectField(player, "mContext") as? Context
        } catch (e: Throwable) {
            logError("Error al obtener contexto del player", e)
            null
        }
    }

    /**
     * Asegura que MediaSession esté activa
     */
    private fun ensureMediaSessionActive(context: Context) {
        synchronized(sessionLock) {
            val session = mediaSession?.get()
            
            // Si ya existe una sesión activa, no hacer nada
            if (session?.isActive == true) {
                return
            }
            
            // Limpiar sesión anterior si existe
            session?.let { releaseMediaSession(it) }
            
            // Crear nueva sesión
            createMediaSession(context)
        }
    }

    /**
     * Crea y configura una nueva MediaSession
     */
    private fun createMediaSession(context: Context) {
        try {
            val session = MediaSession(context, TAG)
            
            // Configurar flags para Android Auto
            session.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            
            // Configurar AudioAttributes
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(TARGET_USAGE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            session.setPlaybackToLocal(audioAttributes)
            
            // Configurar callbacks
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    logInfo("MediaSession.onPlay()")
                }
                
                override fun onPause() {
                    logInfo("MediaSession.onPause()")
                }
                
                override fun onStop() {
                    logInfo("MediaSession.onStop()")
                }
            })
            
            // Activar la sesión
            session.isActive = true
            mediaSession = WeakReference(session)
            
            // Crear notificación si es necesario
            ensureNotificationChannel(context)
            createMediaNotification(context, session)
            
            logInfo("MediaSession creada y activada")
        } catch (e: Throwable) {
            logError("Error al crear MediaSession", e)
        }
    }

    /**
     * Libera recursos de MediaSession
     */
    private fun releaseMediaSession(session: MediaSession) {
        try {
            if (session.isActive) {
                session.isActive = false
            }
            session.release()
            logInfo("MediaSession liberada")
        } catch (e: Throwable) {
            logError("Error al liberar MediaSession", e)
        }
    }

    /**
     * Actualiza el estado de reproducción de MediaSession
     */
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
                logInfo("PlaybackState actualizado a $state")
            } catch (e: Throwable) {
                logError("Error al actualizar PlaybackState", e)
            }
        }
    }

    /**
     * Asegura que el canal de notificación esté creado
     */
    private fun ensureNotificationChannel(context: Context) {
        // Double-checked locking para evitar race conditions
        if (notificationChannelCreated) return
        
        synchronized(sessionLock) {
            if (notificationChannelCreated) return
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val notificationManager = context.getSystemService(
                        Context.NOTIFICATION_SERVICE
                    ) as? NotificationManager ?: return
                    
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "InDrive Audio",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Mantiene el audio activo en Android Auto"
                        setShowBadge(false)
                    }
                    
                    notificationManager.createNotificationChannel(channel)
                    notificationChannelCreated = true
                    logInfo("Canal de notificación creado")
                } catch (e: Throwable) {
                    logError("Error al crear canal de notificación", e)
                }
            }
        }
    }

    /**
     * Crea y publica la notificación de media
     */
    private fun createMediaNotification(context: Context, session: MediaSession) {
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
                    setStyle(
                        Notification.MediaStyle()
                            .setMediaSession(session.sessionToken)
                    )
                }
            }.build()
            
            val notificationManager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as? NotificationManager ?: return
            
            notificationManager.notify(NOTIFICATION_ID, notification)
            logInfo("Notificación de media publicada")
        } catch (e: Throwable) {
            logError("Error al crear notificación de media", e)
        }
    }

    /**
     * Helper para logging de información
     */
    private fun logInfo(message: String) {
        XposedBridge.log("$TAG: $message")
    }

    /**
     * Helper para logging de errores
     */
    private fun logError(message: String, throwable: Throwable? = null) {
        XposedBridge.log("$TAG ERROR: $message")
        throwable?.let {
            XposedBridge.log("$TAG: ${it.message}")
            XposedBridge.log(it)
        }
    }
}
