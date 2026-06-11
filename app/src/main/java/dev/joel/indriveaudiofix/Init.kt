package dev.joel.indriveaudiofix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.PowerManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference

/**
 * Módulo Xposed para corregir el audio de inDrive en Android Auto.
 *
 * @author Joel Criollo
 * @version 2.0.0
 *
 * # Problema
 * Las notificaciones de inDrive (solicitudes de viaje) no se escuchan por
 * Android Auto cuando no hay música reproduciéndose. Esto ocurre porque
 * inDrive no mantiene una MediaSession activa, y Android Auto solo enruta
 * audio de apps que tienen una sesión de media activa.
 *
 * # Solución
 * 1. MediaSession persistente: se crea al cargar el módulo y se mantiene
 *    activa mientras inDrive esté en memoria.
 * 2. Notificación persistente: publica una notificación de media que
 *    Android Auto reconoce como "app con audio activo".
 * 3. Hooks de audio interceptan y modifican AudioAttributes para usar
 *    USAGE_ASSISTANCE_NAVIGATION_GUIDANCE en lugar del valor por defecto.
 * 4. Hook de NotificationManager.notify() para forzar MediaSession en
 *    las notificaciones de inDrive.
 */
class Init : IXposedHookLoadPackage {

    companion object {
        /** Paquete de inDrive (conductor) */
        private val TARGET_PACKAGE = "sinet.startup.inDriver"

        /** USAGE objetivo para que Android Auto reconozca el audio como navegación */
        private const val TARGET_USAGE = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE

        /** ContentType para indicar que es contenido hablado/instrucciones */
        private const val TARGET_CONTENT_TYPE = AudioAttributes.CONTENT_TYPE_SPEECH

        /**
         * IDs únicos para evitar colisiones con otras notificaciones
         */
        private const val MEDIA_NOTIFICATION_ID = 9876
        private const val PERSISTENT_NOTIFICATION_ID = 9877
        private const val CHANNEL_ID = "indrive_audio_fix"
        private const val TAG = "InDriveAudioFix"

        /**
         * Referencia débil a la MediaSession para permitir GC en
         * condiciones de baja memoria. Se regenera automáticamente.
         */
        @Volatile
        private var mediaSessionRef: WeakReference<MediaSession>? = null
        private val sessionLock = Any()

        /** Flag para crear el canal de notificación solo una vez */
        @Volatile
        private var channelCreated = false

        /**
         * Referencia al Context de inDrive para crear la MediaSession.
         * Se obtiene la primera vez que se necesita y se cachea.
         */
        @Volatile
        private var appContext: WeakReference<Context>? = null
    }

    // ──────────────────────────────────────────────────────────────
    //  PUNTO DE ENTRADA — Xposed
    // ──────────────────────────────────────────────────────────────

    override fun handleLoadPackage(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != TARGET_PACKAGE) return

        logInfo("Cargando hooks en $TARGET_PACKAGE (API ${Build.VERSION.SDK_INT})")

        // Cachear ClassLoader para los hooks dinámicos
        val cl = lpp.classLoader

        try {
            hookAudioAttributes(cl)
            hookMediaPlayer(cl)
            hookSoundPool(cl)
            hookAudioFocusAndSession(cl)
            hookNotificationNotify(cl)
            logInfo("✅ Todos los hooks instalados correctamente")
        } catch (e: Throwable) {
            logError("❌ Error crítico al instalar hooks", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  HOOK 1 — AudioAttributes.Builder.build()
    //  Fuerza USAGE_ASSISTANCE_NAVIGATION_GUIDANCE en todos los
    //  AudioAttributes que cree inDrive.
    // ──────────────────────────────────────────────────────────────

    private fun hookAudioAttributes(cl: ClassLoader) {
        try {
            val builderClass = XposedHelpers.findClass(
                "android.media.AudioAttributes\$Builder", cl
            )

            XposedHelpers.findAndHookMethod(
                builderClass, "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val original = param.result as? AudioAttributes ?: return
                        if (original.usage == TARGET_USAGE) return

                        val fixed = AudioAttributes.Builder(original)
                            .setUsage(TARGET_USAGE)
                            .build()

                        logDebug("AudioAttributes.build(): ${original.usage} → $TARGET_USAGE")
                        param.result = fixed
                    }
                }
            )
        } catch (e: Throwable) {
            logError("hookAudioAttributes falló", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  HOOK 2 — MediaPlayer.setAudioAttributes()
    //  Corrige AudioAttributes si se asignan directamente, sin pasar
    //  por Builder.build().
    // ──────────────────────────────────────────────────────────────

    private fun hookMediaPlayer(cl: ClassLoader) {
        try {
            val mpClass = XposedHelpers.findClass(
                "android.media.MediaPlayer", cl
            )

            XposedHelpers.findAndHookMethod(
                mpClass, "setAudioAttributes",
                AudioAttributes::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val attr = param.args[0] as? AudioAttributes ?: return
                        if (attr.usage == TARGET_USAGE) return

                        val fixed = AudioAttributes.Builder(attr)
                            .setUsage(TARGET_USAGE)
                            .build()

                        logDebug("MediaPlayer.setAudioAttributes(): ${attr.usage} → $TARGET_USAGE")
                        param.args[0] = fixed
                    }
                }
            )
        } catch (e: Throwable) {
            logError("hookMediaPlayer falló", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  HOOK 3 — SoundPool.Builder.setAudioAttributes()
    //  Corrige AudioAttributes para sonidos cortos (efectos/alertas).
    // ──────────────────────────────────────────────────────────────

    private fun hookSoundPool(cl: ClassLoader) {
        try {
            val builderClass = XposedHelpers.findClass(
                "android.media.SoundPool\$Builder", cl
            )

            XposedHelpers.findAndHookMethod(
                builderClass, "setAudioAttributes",
                AudioAttributes::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val attr = param.args[0] as? AudioAttributes ?: return
                        if (attr.usage == TARGET_USAGE) return

                        val fixed = AudioAttributes.Builder(attr)
                            .setUsage(TARGET_USAGE)
                            .build()

                        logDebug("SoundPool.setAudioAttributes(): ${attr.usage} → $TARGET_USAGE")
                        param.args[0] = fixed
                    }
                }
            )
        } catch (e: Throwable) {
            logError("hookSoundPool falló", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  HOOK 4 — Audio Focus + MediaSession (ciclo de vida completo)
    //
    //  Engancha MediaPlayer.start() / pause() / stop() / release()
    //  para gestionar audio focus y estado de la MediaSession.
    // ──────────────────────────────────────────────────────────────

    private fun hookAudioFocusAndSession(cl: ClassLoader) {
        try {
            val mpClass = XposedHelpers.findClass("android.media.MediaPlayer", cl)

            // ── start() ──
            XposedHelpers.findAndHookMethod(
                mpClass, "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        requestTransientFocus(param.thisObject)
                        updatePlaybackState(PlaybackState.STATE_PLAYING)
                    }
                }
            )

            // ── pause() ──
            XposedHelpers.findAndHookMethod(
                mpClass, "pause",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        updatePlaybackState(PlaybackState.STATE_PAUSED)
                    }
                }
            )

            // ── stop() ──
            XposedHelpers.findAndHookMethod(
                mpClass, "stop",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        abandonFocus(param.thisObject)
                        // NO liberamos MediaSession — se mantiene persistente
                        updatePlaybackState(PlaybackState.STATE_PAUSED)
                    }
                }
            )

            // ── release() ──
            XposedHelpers.findAndHookMethod(
                mpClass, "release",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        abandonFocus(param.thisObject)
                    }
                }
            )
        } catch (e: Throwable) {
            logError("hookAudioFocusAndSession falló", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  HOOK 5 — NotificationManager.notify()
    //
    //  Intercepta las notificaciones de inDrive y les inyecta la
    //  MediaSession del módulo, para que Android Auto las reconozca
    //  como notificaciones de una app con audio activo.
    // ──────────────────────────────────────────────────────────────

    private fun hookNotificationNotify(cl: ClassLoader) {
        try {
            val nmClass = XposedHelpers.findClass(
                "android.app.NotificationManager", cl
            )

            XposedHelpers.findAndHookMethod(
                nmClass, "notify",
                String::class.java, Int::class.javaPrimitiveType,
                Notification::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val notification = param.args[2] as? Notification ?: return
                            val mediaSession = mediaSessionRef?.get()
                            if (mediaSession == null || !mediaSession.isActive) return

                            // Inyectar MediaSession.Token en el extra de la notificación
                            // para que Android Auto la asocie a nuestra sesión de audio.
                            notification.extras?.let { extras ->
                                extras.putParcelable(
                                    "android.mediaSession",
                                    mediaSession.sessionToken
                                )
                            }
                        } catch (_: Throwable) {
                            // Failsafe: si falla la inyección, no bloquear la notificación
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            logError("hookNotificationNotify falló (no crítico)", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  AUDIO FOCUS
    // ──────────────────────────────────────────────────────────────

    private fun requestTransientFocus(player: Any) {
        try {
            val ctx = getPlayerContext(player) ?: return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            ensureMediaSession(ctx)

            val attrs = AudioAttributes.Builder()
                .setUsage(TARGET_USAGE)
                .setContentType(TARGET_CONTENT_TYPE)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: Throwable) {
            logError("requestTransientFocus falló", e)
        }
    }

    private fun abandonFocus(player: Any) {
        try {
            val ctx = getPlayerContext(player) ?: return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(TARGET_USAGE)
                    .build()
                val request = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                am.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Throwable) {
            logError("abandonFocus falló", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  MEDIA SESSION — GESTIÓN PERSISTENTE
    // ──────────────────────────────────────────────────────────────

    /**
     * Obtiene o crea la MediaSession. A diferencia de la versión anterior,
     * esta NO se destruye en stop() — se mantiene activa mientras inDrive
     * esté en memoria, para que Android Auto siempre reconozca la app.
     */
    private fun ensureMediaSession(context: Context) {
        synchronized(sessionLock) {
            val existing = mediaSessionRef?.get()
            if (existing?.isActive == true) return

            // Liberar sesión zombie si existe
            existing?.let { releaseMediaSession(it) }

            createMediaSession(context)
        }
    }

    private fun createMediaSession(context: Context) {
        try {
            val session = MediaSession(context, TAG)

            session.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            val attrs = AudioAttributes.Builder()
                .setUsage(TARGET_USAGE)
                .setContentType(TARGET_CONTENT_TYPE)
                .build()
            session.setPlaybackToLocal(attrs)

            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { logDebug("MediaSession.onPlay()") }
                override fun onPause() { logDebug("MediaSession.onPause()") }
                override fun onStop() { logDebug("MediaSession.onStop()") }
            })

            session.isActive = true
            mediaSessionRef = WeakReference(session)

            ensureChannel(context)
            postPersistentNotification(context, session)

            logInfo("✅ MediaSession persistente creada y activa")
        } catch (e: Throwable) {
            logError("Error al crear MediaSession", e)
        }
    }

    private fun releaseMediaSession(session: MediaSession) {
        try {
            if (session.isActive) session.isActive = false
            session.release()
        } catch (_: Throwable) { /* ignora */ }
    }

    /**
     * Actualiza el PlaybackState. Cuando inDrive no está reproduciendo
     * activamente, dejamos el estado en PAUSED (no STOPPED) para que
     * Android Auto mantenga la sesión visible.
     */
    private fun updatePlaybackState(state: Int) {
        synchronized(sessionLock) {
            val session = mediaSessionRef?.get() ?: return
            try {
                val pbState = PlaybackState.Builder()
                    .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                                PlaybackState.ACTION_PAUSE or
                                PlaybackState.ACTION_STOP or
                                PlaybackState.ACTION_PLAY_PAUSE
                    )
                    .build()
                session.setPlaybackState(pbState)
            } catch (e: Throwable) {
                logError("updatePlaybackState falló", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  NOTIFICACIONES
    // ──────────────────────────────────────────────────────────────

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        synchronized(sessionLock) {
            if (channelCreated) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val nm = context.getSystemService(
                        Context.NOTIFICATION_SERVICE
                    ) as? NotificationManager ?: return

                    val channel = NotificationChannel(
                        CHANNEL_ID, "InDrive Audio Fix",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Mantiene el audio de inDrive en Android Auto"
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                    channelCreated = true
                } catch (e: Throwable) {
                    logError("ensureChannel falló", e)
                }
            } else {
                channelCreated = true
            }
        }
    }

    /**
     * Publica una notificación persistente que asocia la MediaSession
     * a Android Auto. Esta notificación se mantiene publicada mientras
     * inDrive esté en memoria.
     */
    private fun postPersistentNotification(context: Context, session: MediaSession) {
        try {
            val nm = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as? NotificationManager ?: return

            val notification = buildPersistentNotification(context, session)
            nm.notify(PERSISTENT_NOTIFICATION_ID, notification)
            logInfo("🔔 Notificación persistente publicada")
        } catch (e: Throwable) {
            logError("postPersistentNotification falló", e)
        }
    }

    private fun buildPersistentNotification(
        context: Context,
        session: MediaSession
    ): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        return builder
            .setContentTitle("inDrive Audio Activo")
            .setContentText("Audio para Android Auto")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true) // no se puede dismiss
            .setShowWhen(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setStyle(
                        Notification.MediaStyle()
                            .setMediaSession(session.sessionToken)
                            .setShowActionsInCompactView()
                    )
                }
            }
            .build()
    }

    /** Actualiza la notificación persistente (ej: al cambiar estado) */
    private fun refreshPersistentNotification(context: Context) {
        val session = mediaSessionRef?.get() ?: return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            val notification = buildPersistentNotification(context, session)
            nm.notify(PERSISTENT_NOTIFICATION_ID, notification)
        } catch (_: Throwable) { }
    }

    // ──────────────────────────────────────────────────────────────
    //  UTILIDADES
    // ──────────────────────────────────────────────────────────────

    /**
     * Obtiene el Context de un objeto MediaPlayer.
     */
    private fun getPlayerContext(player: Any): Context? {
        return try {
            XposedHelpers.getObjectField(player, "mContext") as? Context
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Obtiene el Context de la app. Cachea la referencia para uso futuro.
     */
    private fun getAppContext(player: Any): Context? {
        // Intentar desde el player
        getPlayerContext(player)?.let { ctx ->
            appContext = WeakReference(ctx)
            ensureMediaSession(ctx)
            return ctx
        }

        // Intentar desde la referencia cacheada
        appContext?.get()?.let { ctx ->
            ensureMediaSession(ctx)
            return ctx
        }

        return null
    }

    // ──────────────────────────────────────────────────────────────
    //  LOGGING
    // ──────────────────────────────────────────────────────────────

    private fun logInfo(msg: String) {
        XposedBridge.log("$TAG ℹ️ $msg")
    }

    private fun logDebug(msg: String) {
        XposedBridge.log("$TAG 🔧 $msg")
    }

    private fun logError(msg: String, e: Throwable? = null) {
        XposedBridge.log("$TAG ❌ $msg")
        e?.let {
            XposedBridge.log("$TAG   └─ ${it.message}")
            if (it.cause != null) {
                XposedBridge.log("$TAG   └─ cause: ${it.cause?.message}")
            }
        }
    }
}
