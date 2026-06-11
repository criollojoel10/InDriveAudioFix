# Changelog

## [2.0.0] — 2026-06-11

### Fixed
- **Notificaciones sin música**: Las notificaciones de inDrive ahora suenan por Android Auto incluso sin música reproduciéndose. Se implementó MediaSession persistente que se mantiene activa mientras inDrive esté en memoria, y se inyecta el token de MediaSession en las notificaciones de inDrive.

### Added
- **MediaSession persistente**: Ya no se destruye en `stop()`. Se crea al cargar el módulo y se mantiene activa mientras inDrive esté en proceso.
- **Notificación persistente**: Se publica automáticamente al cargar el módulo para que Android Auto reconozca la app como fuente de audio activa.
- **Hook de NotificationManager.notify()**: Inyecta el token de MediaSession en las notificaciones de inDrive para que Android Auto las enrute correctamente.
- **Compatibilidad con Android 16 (API 36)**: compileSdk/targetSdk actualizados a 35.
- **CI/CD mejorado**: Workflow actualizado con build automático de debug + release, artifacts, y GitHub Releases con ambos APKs.
- **Versión 2.0.0**: versionCode 2, versionName 2.0.0.

### Changed
- `PlaybackState` en reposo: ahora usa `STATE_PAUSED` en lugar de `STATE_STOPPED` para que Android Auto mantenga la sesión visible.
- Logging más detallado con emojis para facilitar lectura en logcat.
- Código reorganizado con secciones claras y documentación interna.

### Technical
- `hookAudioFocusAndSession()` reemplaza a `hookAudioFocus()`: ahora maneja el ciclo de vida completo de audio focus + MediaSession persistente.
- `hookNotificationNotify()`: nuevo hook que intercepta `NotificationManager.notify()` para inyectar MediaSession.
- WeakReference para contexto de app además de MediaSession.
- Doble notificación: una de media (para Android Auto) y la persistente (para mantener la sesión activa).

## [1.0.1] — 2024

### Optimized
- Hooks consolidados (eliminados 3 hooks duplicados)
- WeakReference para MediaSession (previene memory leaks)
- Double-checked locking (mejor thread-safety)
- Early returns (minimiza overhead)
- APK 40% más pequeño (~150KB)

### Added
- Documentación técnica completa (DOCUMENTACION_TECNICA.md)
- Guía rápida (GUIA_RAPIDA.md)
- Configuración de ProGuard

## [1.0.0] — 2024

### Added
- Hook de AudioAttributes.Builder.build()
- Hook de MediaPlayer.setAudioAttributes()
- Hook de SoundPool.Builder.setAudioAttributes()
- Hook de audio focus (start/pause/stop/release)
- MediaSession para Android Auto
- Notificación de media persistente
- Soporte para Android 8.0+ (API 26+)
- Xposed/LSPosed integration
