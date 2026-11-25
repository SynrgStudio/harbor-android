# Harbor para Android

Versión para celular de Harbor: películas, series y anime con fichas y portadas de TMDB (sin clave: llegan por el addon de TMDB; con una clave propia, directo), fuentes de tus addons de Stremio (Torrentio, Comet…) y reproducción de torrents con el mismo motor que Harbor de escritorio (librqbit).

- Inicio con carrusel, "Seguir viendo", tendencias, populares, anime y más.
- Ficha con temporadas, episodios, reparto y recomendaciones.
- Selector de fuentes ordenado por caché de debrid, calidad y seeds, con filtros por resolución.
- Reproductor propio (Media3 + FFmpeg para DTS/TrueHD): gestos, ±10 s, audio y subtítulos (del archivo o de internet), velocidad, ventana flotante y siguiente episodio automático.
- Cuenta de Stremio: trae tus addons y sincroniza "Seguir viendo" y "Mi lista" con Stremio y Harbor de escritorio. Los addons también se pueden agregar por URL o con los enlaces `stremio://`.

## Instalar en el teléfono

1. `.\scripts\build-native.ps1` (solo la primera vez o si cambia `native/`).
2. Con la depuración inalámbrica activada: `adb pair <ip:puerto> <código>` y `adb connect <ip:puerto>`.
3. `.\scripts\run.ps1 -Release -Serial <ip:puerto>`.

O copiá `app\build\outputs\apk\release\app-arm64-v8a-release.apk` al teléfono e instalalo a mano.
