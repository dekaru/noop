# BLOCKERS — SIS-609 (ZJS-F9, ronda 2) — 20-oct-2026

Estado: **el fix autorizado de la verificación de dependencias está hecho y verificado**; lo que
sigue pendiente es la ejecución de la prueba dentro del módulo Android, y eso lo bloquea el disco
de la máquina (no el código).

## 1. Lo que pedía el review (SIS-606) y qué se hizo

El review reproductions el bloqueo: la build moría antes de compilar con

```
Dependency verification failed for configuration 'classpath'
  kotlin-reflect-2.2.20.jar / .pom   (org.jetbrains.kotlin:kotlin-reflect:2.2.20)
  kotlin-stdlib-2.2.20.jar / .module  (org.jetbrains.kotlin:kotlin-stdlib:2.2.20)
```

ADDENDUM §1 de la spec lo autoriza explícitamente (checksums exactos, prohibido `--no-verification`).

**Hecho**: `android/gradle/verification-metadata.xml` tiene ahora las 4 entradas de Kotlin 2.2.20
con el sha256 exacto de los artefactos que Gradle descargó de MavenRepo:

| artefacto | sha256 |
|---|---|
| kotlin-reflect-2.2.20.jar | `8209083a4a7c4e476d9842b078308fa96a96f07a6bd99f05f3586ee13289ab26` |
| kotlin-reflect-2.2.20.pom | `4e27474066db83fba2c59074289ba712be8c85157cde0b905029a445c275f5ba` |
| kotlin-stdlib-2.2.20.jar  | `8836ccffd3585fadda9901244b20d42901d2f3cd581058d8434e2ffabcf3a3e7` |
| kotlin-stdlib-2.2.20.module | `c918f5214d021a72e3767f2756e97d103a526e04f1423da3663efdfb5847db95` |

**Verificado**: con esas entradas la build pasa la puerta de verificación y configura el proyecto
(`> Configure project :` + resolución del classpath de la app). Antes moría en la verificación.

## 2. El blocker que sigue: DISCO (no código)

La prueba del criterio (`:app:testFullDebugUnitTest --tests '*Journal*'`) necesita:

| consumidor | tamaño medido/necesario |
|---|---|
| caché de dependencias Gradle (modules-2 + transforms 9.2.1 + jars de la API) | **~1.0–1.2 GB** (medido: 588 MB solo con el classpath del buildscript, sube a ~820 MB al configurar la app) |
| salidas de build (544 archivos main + 778 test) | **~0.4–0.7 GB** |
| SDK de Android (platform 35 + build-tools 34.0.0) | 293 MB |
| distribución Gradle 9.2.1 | 144 MB |
| **total** | **~1.9–2.3 GB** |

Espacio libre real de la máquina durante la sesión (20-oct 10:34–10:57 CST):

```
/mnt/volume-molty-1   9.8G total · 88 % usado · 1.2 GB libres   (sube a 1.4 GB tras mover a frío)
/                     38G total · 97–100 % usado · 0.24–0.87 GB libres
```

Faltan ~0.5 GB. Con la escalera de disco lanzándose cada ~15 min (el volumen está en nivel crítico
porque su piso de aviso son 4 GB y el volumen tiene 9.8 GB), bajar de 300 MB en cualquiera de los
dos discos es el stop que fija la spec: "Si baja de 300 MB durante la descarga: detener y avisar".
Por eso el build se paró dos veces por disco, no por código.

**No se agranda el disco a la fuerza**: mover a frío datasets vivos (alpha-tv-data tiene
`.no-tier-move`, kucoin/solarwinds es el pipeline de logs del paper) está prohibido o no es
sancionado. Lo único que se movió a frío fue `data/mnk62/ab-v11` (experimento A/B terminado del
13-oct, sin referencias, sin FDs abiertos): 660 MB con manifiesto en `MANIFEST.tsv` y reversible.

## 3. Evidencia que sí se obtuvo: las pruebas se ejecutaron de verdad

Como el build Android no cabe, se montó un **harness JVM** (fuera del repo, en el volumen) que
compila y ejecuta **los mismos archivos del repo, sin copiarlos**:

- producción: `android/app/src/main/java/com/noop/ui/JournalPrefill.kt`
- tests: `android/app/src/test/java/com/noop/ui/JournalPrefillTest.kt`
  y `android/app/src/test/java/com/noop/journalsync/JournalSyncPairingTest.kt`

Con el **Gradle 9.2.1 de la máquina** (`/mnt/volume-molty-1/data/noop-gradle/gradle-9.2.1`):

```
JournalPrefillTest > sync active with yesterday rows shows banner only, no upsert PASSED
JournalPrefillTest > non-today day offset never prefills PASSED
JournalPrefillTest > sync inactive with yesterday rows upserts as today (original behaviour) PASSED
JournalPrefillTest > today already has answers never prefills PASSED
JournalPrefillTest > no yesterday rows never prefills PASSED
JournalPrefillTest > banner reload never supplies answers and real today answers stop prefill PASSED
JournalSyncPairingTest > valid json parses all four fields PASSED
JournalSyncPairingTest > cf fields are optional PASSED
JournalSyncPairingTest > garbage returns null without crashing PASSED
JournalSyncPairingTest > extra keys are ignored and strings are trimmed PASSED
JournalSyncPairingTest > missing token is an error PASSED
JournalSyncPairingTest > missing url is an error PASSED
BUILD SUCCESSFUL in 49s
```

12/12 casos en verde (6 de `JournalPrefillTest` + 6 de `JournalSyncPairingTest`).

Eso cubre los 4 casos que pide el criterio 2 de la spec (a) sync activo → banner sin upsert,
(b) sync inactivo → upsert como hoy, (c) dayOffset != 0 → nunca prefill, (d) hoy con respuestas →
nunca prefill. Lo que NO cubre es que compile el resto del módulo (Compose/Room/Android).

## 4. Cómo se cierra el criterio cuando haya disco

```bash
# 1. SDK y Gradle 9.2.1 fuera de las rutas que purga el cleanup (§5)
bash /root/.gradle-noop/install-toolchain.sh

# 2. build con el lock de la escalera (si no, el cleanup mata el daemon a mitad)
flock /run/lock/disk-ladder.lock \
  /mnt/volume-molty-1/data/noop-gradle/gradle-9.2.1/bin/gradle \
  -I /root/.gradle-noop/sis609-build-dir.gradle \
  --project-cache-dir /mnt/volume-molty-1/data/noop-build/project-cache \
  -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m" \
  -Dkotlin.daemon.jvmargs="-Xmx2048m" \
  :app:testFullDebugUnitTest --tests '*Journal*'
```

Hace falta ~1 GB libre en `/mnt/volume-molty-1` (o en `/`) **por encima** de lo que hay ahora.

## 5. Hallazgos estructurales (para que la próxima ronda no los repita)

1. **El purgador borra el toolchain del volumen.** `auto-cleanup-disk.sh` §10/§10b borran
   `$GRADLE_USER_HOME/{caches,wrapper/dists}` y `/mnt/volume-molty-1/android/sdk` cuando el volumen
   está a **≥ 80 % usado** (hoy está a 86–96 %, o sea, **siempre armado**). El 20-oct 10:35 borró
   el SDK y la distribución de Gradle **en medio** de esta build. Instalación usada: SDK en
   `/mnt/volume-molty-1/data/noop-android-sdk`, Gradle en `.../data/noop-gradle`, `GRADLE_USER_HOME`
   en `/root/.gradle-noop` (fuera del volumen), salidas de build en `.../data/noop-build`.
2. **La escalera mata los daemons de Gradle.** Su etapa `heavy` hace
   `pkill -f org.gradle.launcher.daemon.bootstrap.GradleDaemon` (a todos, tenga el `GRADLE_USER_HOME`
   que tenga) y borra el SDK del volumen. Se lalauza `master-cron.sh` con
   `flock -n /run/lock/disk-ladder.lock`; **tomando ese lock la build sobrevive** (el lock se suelta
   al terminar).
3. **El `gradle` del PATH es 4.4.1** y deja un daemon con el candado de build: usar siempre el
   binario 9.2.1 y cerrar con `--stop` al final.
4. **El hot disk está al 97–100 %** con 3.4 GB ocupados por `~/.hermes` (6.9 GB) y
   `~/.openclaw/workspace` (5.9 GB). Liberar ~2 GB de ahí (o del volumen) es lo que desbloquea
   cualquier build Android en esta máquina; hoy no hay regenerables suficientes: el
   `auto-cleanup-disk.sh --stage cache` no encuentra ni un KB.
