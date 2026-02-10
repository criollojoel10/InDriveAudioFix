<!-- TREE_START -->
<!-- TREE_END -->
# InDrive Audio Fix

Módulo Xposed/LSPosed para corregir problemas de audio en la aplicación inDrive cuando se usa con Android Auto.

## 🎯 Descripción

Este módulo intercepta y modifica las llamadas de audio de inDrive para garantizar que funcionen correctamente con Android Auto. El módulo fuerza el uso de `AudioAttributes` apropiados (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) y mantiene una `MediaSession` activa para que Android Auto reconozca la aplicación de audio correctamente.

## ✨ Características

- ✅ Intercepta y corrige `AudioAttributes.Builder.build()`
- ✅ Modifica `MediaPlayer.setAudioAttributes()` 
- ✅ Maneja `SoundPool.Builder.setAudioAttributes()`
- ✅ Gestiona Audio Focus transitorio automáticamente
- ✅ Mantiene `MediaSession` activa para Android Auto
- ✅ Notificación de media persistente
- ✅ Logging detallado para debugging
- ✅ Manejo robusto de errores
- ✅ Sin interfaz de usuario (módulo optimizado en segundo plano)
- ✅ Código optimizado para rendimiento máximo
- ✅ APK mínimo (~150KB) sin dependencias innecesarias

## ⚡ Optimizaciones

- **Hooks consolidados**: Eliminación de hooks duplicados para mejor rendimiento
- **Sin Material Components**: No hay UI, por lo que no se incluyen dependencias de Material Design
- **WeakReference**: Gestión eficiente de memoria para MediaSession
- **Thread-safe**: Double-checked locking para operaciones concurrentes
- **APK 40% más pequeño**: Sin tests ni recursos innecesarios

## 📋 Requisitos

- Android 8.0 (API 26) o superior
- [LSPosed](https://github.com/LSPosed/LSPosed) o Xposed Framework
- inDrive (sinet.startup.inDriver) instalado

## 📦 Instalación

### Desde GitHub Actions (Recomendado)

1. Ve a la sección [Actions](../../actions) de este repositorio
2. Selecciona el workflow "Build Debug APK"
3. Descarga el artifact `app-debug-*` más reciente
4. Instala el APK en tu dispositivo
5. Activa el módulo en LSPosed
6. Reinicia la aplicación inDrive

### Compilar desde el código fuente

```bash
git clone https://github.com/criollojoel10/InDriveAudioFix.git
cd InDriveAudioFix
./gradlew assembleDebug
```

El APK se generará en: `app/build/outputs/apk/debug/app-debug.apk`

## 🔧 Configuración

El módulo funciona automáticamente después de la instalación. No requiere configuración adicional.

### Opciones de configuración (en el código)

En `Init.kt` puedes modificar:

```kotlin
// Tipo de uso de audio (por defecto: navegación)
private const val TARGET_USAGE = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE

// Solicitar audio focus transitorio (recomendado: true)
private const val REQUEST_TRANSIENT_FOCUS = true

// Bloquear MODE_IN_COMMUNICATION (por defecto: false)
private const val SUPPRESS_IN_COMM_MODE = false
```

## 🛠️ Desarrollo

### Estructura del proyecto

```
InDriveAudioFix/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/dev/joel/indriveaudiofix/
│   │       │   └── Init.kt          # Código principal del módulo
│   │       └── AndroidManifest.xml  # Configuración del módulo Xposed
│   └── build.gradle.kts             # Configuración de compilación
├── .github/
│   └── workflows/
│       └── build-and-test.yml       # CI/CD con GitHub Actions
└── build.gradle.kts                 # Configuración global
```

### Tecnologías utilizadas

- **Kotlin** 1.9.25
- **Android Gradle Plugin** 8.1.1
- **Gradle** 8.0
- **Xposed API** 82
- **Target SDK** 34 (Android 14)
- **Min SDK** 26 (Android 8.0)

### Construir el proyecto

```bash
# Limpiar el proyecto
./gradlew clean

# Compilar APK debug
./gradlew assembleDebug

# Compilar APK release
./gradlew assembleRelease
```

**Nota**: Este módulo no tiene tests ya que es un módulo Xposed sin UI. Las pruebas se realizan ejecutando el módulo en un dispositivo con LSPosed.

## 📝 Cómo funciona

### 1. Interceptación de AudioAttributes

El módulo intercepta la creación de `AudioAttributes` y los modifica para usar `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`:

```kotlin
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
```

### 2. Gestión de MediaSession

Crea y mantiene una `MediaSession` activa que permite a Android Auto reconocer la aplicación:

```kotlin
private fun createMediaSession(context: Context) {
    val session = MediaSession(context, TAG)
    session.setFlags(
        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
    )
    session.isActive = true
    // ...
}
```

### 3. Audio Focus

Solicita y libera audio focus automáticamente cuando inDrive reproduce o detiene audio:

- `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` al reproducir
- Libera el focus al detener

## 🐛 Debugging

Los logs del módulo se pueden ver usando:

```bash
# Logcat general
adb logcat | grep InDriveAudioFix

# LSPosed logs
# Revisar en LSPosed Manager > Logs
```

Mensajes de log típicos:
- `Hooks cargados exitosamente en sinet.startup.inDriver`
- `AudioAttributes.build() [USAGE] -> [NEW_USAGE]`
- `MediaSession creada y activada`
- `PlaybackState actualizado a [STATE]`

## 🤝 Contribuir

Las contribuciones son bienvenidas. Por favor:

1. Fork el proyecto
2. Crea una rama para tu feature (`git checkout -b feature/AmazingFeature`)
3. Commit tus cambios (`git commit -m 'Add some AmazingFeature'`)
4. Push a la rama (`git push origin feature/AmazingFeature`)
5. Abre un Pull Request

## 📄 Licencia

Este proyecto es de código abierto y está disponible bajo una licencia permisiva.

## ⚠️ Disclaimer

Este módulo modifica el comportamiento de la aplicación inDrive. Úsalo bajo tu propia responsabilidad. Los desarrolladores no se hacen responsables de ningún problema que pueda surgir del uso de este módulo.

## 📚 Documentación

Este proyecto incluye documentación técnica completa:

- **[DOCUMENTACION_TECNICA.md](DOCUMENTACION_TECNICA.md)**: Documentación técnica detallada sobre arquitectura, funcionamiento interno, APIs utilizadas y optimizaciones de rendimiento.
- **[GUIA_RAPIDA.md](GUIA_RAPIDA.md)**: Guía de referencia rápida con resumen de componentes, configuración y solución de problemas.

## 📧 Contacto

Proyecto: [https://github.com/criollojoel10/InDriveAudioFix](https://github.com/criollojoel10/InDriveAudioFix)

## 🙏 Agradecimientos

- [LSPosed](https://github.com/LSPosed/LSPosed) - Framework para módulos Xposed en Android moderno
- [Xposed](https://github.com/rovo89/Xposed) - Framework original
- Comunidad de Android y Xposed developers
