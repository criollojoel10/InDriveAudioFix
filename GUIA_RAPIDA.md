# Guía Rápida — InDriveAudioFix v2.0.0

> Módulo LSPosed/Xposed que corrige el audio de inDrive en Android Auto.
> **Sin interfaz de usuario.** Funciona en segundo plano.

---

## Instalación

1. Descarga el APK desde [Releases](https://github.com/criollojoel10/InDriveAudioFix/releases)
2. Instálalo
3. LSPosed Manager → Activar módulo → Scope: **inDrive** (`sinet.startup.inDriver`)
4. Reinicia inDrive

✅ **No requiere configuración adicional.**

---

## ¿Qué cambió en v2.0.0?

| Antes (v1.x) | Ahora (v2.0.0) |
|---|---|
| MediaSession solo durante reproducción | MediaSession **persistente** (siempre activa) |
| Notificaciones solo con música | ✅ Notificaciones **sin música también** |
| Sin hook de notificaciones | ✅ Hook de `NotificationManager.notify()` |
| compileSdk 34 | compileSdk 35 (Android 15) |

---

## Cómo funciona (30 segundos)

```
inDrive → LSPosed carga módulo
  ├─ Crea MediaSession persistente → Android Auto ve la app
  ├─ Corrige AudioAttributes → USAGE_NAVIGATION_GUIDANCE
  ├─ Inyecta MediaSession en notificaciones
  └─ ✅ Audio siempre por los parlantes del auto
```

## Logs

```bash
adb logcat | grep InDriveAudioFix
```

| Log | Qué significa |
|---|---|
| `✅ MediaSession persistente creada y activa` | Funcionando |
| `🔔 Notificación persistente publicada` | Todo ok |
| `AudioAttributes.build(): X → 12` | Atributo corregido |
| `❌ Error crítico al instalar hooks` | Reportar issue |

---

## Archivos

| Archivo | Para qué |
|---|---|
| `app-debug.apk` | Instalación directa (sin minify) |
| `app-release.apk` | Versión optimizada (con minify) |

---

## Compilar

```bash
./gradlew assembleDebug   # APK debug
./gradlew assembleRelease # APK release
```

---

## Soporte

Issues → https://github.com/criollojoel10/InDriveAudioFix/issues
