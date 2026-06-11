# 🎧 InDriveAudioFix

> Módulo LSPosed/Xposed que corrige el audio de inDrive en Android Auto.

[![Build](https://github.com/criollojoel10/InDriveAudioFix/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/criollojoel10/InDriveAudioFix/actions/workflows/build-and-test.yml)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## 📋 Tabla de Contenidos

- [El Problema](#el-problema)
- [La Solución](#la-solución)
- [Instalación](#instalación)
- [Cómo Funciona](#cómo-funciona)
- [Compilar desde Código](#compilar-desde-código)
- [Logs y Debugging](#logs-y-debugging)
- [Documentación](#documentación)
- [Roadmap](#roadmap)

---

## 🚨 El Problema

**inDrive + Android Auto = audio roto.**

Las notificaciones de inDrive (solicitudes de viaje) solo se escuchan por los parlantes del auto cuando hay música reproduciéndose. Sin música (Spotify, YT Music, etc.), las notificaciones **no se escuchan por Android Auto**.

### Causa raíz

```
inDrive no mantiene una MediaSession activa
  → Android Auto no reconoce inDrive como app de audio
  → Sin música: no hay sesión de audio → las notificaciones no se enrutan al auto
  → Con música: Spotify tiene MediaSession → las notificaciones se "cuelan" (duck)
```

## ✅ La Solución

**InDriveAudioFix** intercepta las llamadas de audio de inDrive mediante Xposed y:

1. **MediaSession persistente** — se crea al cargar el módulo y se mantiene activa mientras inDrive esté en memoria. Android Auto siempre ve a inDrive como una app con audio disponible.

2. **Notificación persistente** — se publica automáticamente para que Android Auto mantenga la sesión de audio activa incluso cuando inDrive no está reproduciendo nada.

3. **Corrección de AudioAttributes** — fuerza `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` en todos los sonidos de inDrive para que Android Auto los priorice como navegación.

4. **Inyección en notificaciones** — intercepta `NotificationManager.notify()` e inyecta el token de MediaSession en las notificaciones de inDrive.

---

## 📲 Instalación

### Requisitos

- Dispositivo **root** con **KernelSU**, **Magisk** o similar
- **LSPosed** Framework instalado (Zygisk o tradicional)
- Android **8.0+** (API 26+), probado en Android 16

### Pasos

1. Descarga el APK desde [GitHub Releases](https://github.com/criollojoel10/InDriveAudioFix/releases)
2. Instálalo como app normal
3. Abre **LSPosed Manager**
4. Activa el módulo **InDriveAudioFix**
5. En **Scope**, selecciona **inDrive** (`sinet.startup.inDriver`)
6. Reinicia inDrive (force stop o reboot)

> ⚡ Sin configuración adicional. El módulo funciona en segundo plano sin interfaz de usuario.

---

## ⚙️ Cómo Funciona

```
┌─────────────────────────────────────────────────┐
│               inDrive se inicia                  │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│         LSPosed carga InDriveAudioFix            │
│  ┌─────────────────────────────────────────┐    │
│  │  hookAudioAttributes()                  │    │
│  │  └─ Fuerza USAGE_NAVIGATION_GUIDANCE    │    │
│  ├─────────────────────────────────────────┤    │
│  │  hookMediaPlayer()                      │    │
│  │  └─ Corrige AudioAttributes asignados   │    │
│  ├─────────────────────────────────────────┤    │
│  │  hookAudioFocusAndSession()             │    │
│  │  ├─ Crea MediaSession persistente       │    │
│  │  ├─ Gestiona audio focus                │    │
│  │  └─ Publica notificación persistente    │    │
│  ├─────────────────────────────────────────┤    │
│  │  hookNotificationNotify()               │    │
│  │  └─ Inyecta MediaSession en notifs      │    │
│  └─────────────────────────────────────────┘    │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│          Android Auto reconoce el audio          │
│  ┌─────────────────────────────────────────┐    │
│  │  ✅ MediaSession activa → app visible   │    │
│  │  ✅ Notificaciones enrutadas al auto    │    │
│  │  ✅ AudioAttributes de navegación       │    │
│  │  ✅ Funciona con y sin música           │    │
│  └─────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

### Hooks implementados

| Hook | Método | Propósito |
|---|---|---|
| `hookAudioAttributes` | `AudioAttributes.Builder.build()` | Fuerza USAGE de navegación |
| `hookMediaPlayer` | `MediaPlayer.setAudioAttributes()` | Corrige atributos directos |
| `hookSoundPool` | `SoundPool.Builder.setAudioAttributes()` | Corrige sonidos cortos |
| `hookAudioFocusAndSession` | `MediaPlayer.start/pause/stop/release()` | Gestiona focus + sesión |
| `hookNotificationNotify` | `NotificationManager.notify()` | Inyecta MediaSession |

---

## 🔧 Compilar desde Código

```bash
# Clonar
git clone https://github.com/criollojoel10/InDriveAudioFix.git
cd InDriveAudioFix

# Compilar debug
./gradlew assembleDebug

# Compilar release (minificado)
./gradlew assembleRelease

# Outputs
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

> **Nota:** Requiere JDK 17 y Android SDK. El APK se firma con debug keystore automáticamente.

---

## 📊 Logs y Debugging

```bash
# Ver todos los logs del módulo
adb logcat | grep InDriveAudioFix

# Solo errores
adb logcat | grep "InDriveAudioFix ❌"

# Ver audio focus
adb logcat | grep -E "AudioFocus|requestAudioFocus"

# Ver MediaSession
adb logcat | grep -E "MediaSession|PlaybackState"
```

### Mensajes clave

| Mensaje | Significado |
|---|---|
| `✅ Todos los hooks instalados correctamente` | Módulo funcionando |
| `✅ MediaSession persistente creada y activa` | Sesión de audio activa |
| `🔔 Notificación persistente publicada` | Notificación visible |
| `AudioAttributes.build(): X → 12` | Atributo corregido a navegación |
| `❌ Error crítico al instalar hooks` | Algo falló (reportar issue) |

---

## 📚 Documentación

| Archivo | Contenido |
|---|---|
| [`DOCUMENTACION_TECNICA.md`](DOCUMENTACION_TECNICA.md) | Documentación técnica completa (arquitectura, APIs, debugging) |
| [`GUIA_RAPIDA.md`](GUIA_RAPIDA.md) | Guía de referencia rápida |
| [`CHANGELOG.md`](CHANGELOG.md) | Historial de cambios por versión |

---

## 🗺️ Roadmap

- [x] **v1.0.0** — AudioAttributes fix + MediaSession básica
- [x] **v1.0.1** — Hooks consolidados, APK optimizado, documentación
- [x] **v2.0.0** — MediaSession persistente, notificaciones sin música, Android 16 ✓
- [ ] **v2.1.0** — OwnTracks integration para GPS secundario en Nodo-02
- [ ] **v2.2.0** — Auto-recovery de MediaSession, watchdog interno
- [ ] **v3.0.0** — Valhalla map-matching para rutas precisas

---

## 🤝 Contribuir

1. Fork del repo
2. Crea tu rama: `git checkout -b feature/mi-mejora`
3. Commit: `git commit -m 'feat: mi mejora'`
4. Push: `git push origin feature/mi-mejora`
5. Abre un Pull Request

---

## 📄 Licencia

MIT — Haz lo que quieras, pero bajo tu propia responsabilidad.

---

## 👤 Autor

**Joel Criollo Abarca** — [@criollojoel10](https://github.com/criollojoel10)

---

<p align="center">
  <sub>Hecho con 🥭 para la comunidad de Riobamba</sub>
</p>
