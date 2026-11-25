# Harbor para Android

App de películas y series para Android que funciona con tu cuenta de Stremio:

- Fichas y portadas de TMDB en español, sin configurar nada.
- Tus addons, tu "Seguir viendo" y tu lista sincronizados con Stremio.
- Un reproductor propio, rápido y con gestos.

## Qué tiene

- **Inicio:** carrusel, "Seguir viendo", tendencias, populares, estrenos, anime y más.
- **Fichas:** temporadas, episodios, reparto y títulos parecidos. Sin clave llegan por el addon de TMDB; con una clave propia de TMDB en Ajustes, directo de TMDB.
- **Cuenta de Stremio:** entrás una vez y trae tus addons, tu "Seguir viendo" y tu lista. Desde ahí los mantiene iguales en Stremio, Harbor de escritorio y el celular.
- **Fuentes:** vienen de los addons de tu cuenta, o de los que agregues por URL o con enlaces `stremio://`. Se ordenan por caché de debrid, calidad y seeds, y se pueden filtrar por resolución.
- **Reproductor:**
  - Media3 + FFmpeg, también para DTS y TrueHD.
  - Gestos de brillo, volumen y búsqueda.
  - Audio y subtítulos del archivo o de internet.
  - Velocidad y ventana flotante.
  - Siguiente episodio automático.
  - Torrents en streaming con librqbit.

Harbor no aloja ni provee contenido: muestra lo que devuelven los addons que configures.

## Instalar

1. Bajá el APK **arm64-v8a** del [último release](https://github.com/SynrgStudio/harbor-android/releases/latest).
   - Para recibir las actualizaciones solo, agregá este repo en [Obtainium](https://github.com/ImranR98/Obtainium).
2. Certificado de firma (SHA-256):
   `E2:FA:56:B9:3D:A9:A7:ED:2D:09:D3:23:58:A6:18:16:2F:97:55:99:18:E3:C6:F5:24:00:72:B4:AF:54:B0:B8`

Requiere Android 10 o superior.

## Compilar

En Windows, con Java 17, el Android SDK + NDK y Rust:

1. `.\scripts\build-native.ps1` compila el motor de torrents (`native/`). Va solo la primera vez o si cambia `native/`.
2. `.\scripts\run.ps1 -Release -Serial <ip:puerto>` compila e instala en el teléfono por depuración inalámbrica.

Sin clave de release en `local.properties`, los builds se firman con la clave de debug.

## Licencia

[GPL-3.0-or-later](LICENSE). Usa el decodificador FFmpeg de Jellyfin (GPL-3.0) y [librqbit](https://github.com/ikatson/rqbit) (Apache-2.0). La fuente Inter va con la licencia SIL OFL.
