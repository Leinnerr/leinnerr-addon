# Leinnerr Addon (Meteor Client) - Minecraft 1.21.5

Addon para servidores anarquicos. Categoria propia: **Leinnerr**.

| Modulo | Nombre en Meteor |
|---|---|
| JugadorTP (antes PlayerTP) | `jugadorTP` |
| InfiniteAnchor | `infinite-anchor` |
| InfiniteReach | `infinite-reach` |
| AntiSpamKills | `anti-spam-kills` |
| Surround (portado de Shoreline) | `surround-plus` |
| AutoCrawlTrap (portado de Shoreline) | `auto-crawl-trap` |
| Phase (portado de Shoreline) | `phase` |
| L anchor (AnchorAura propio) | `l-anchor` |

## Compilar

1. JDK 21.
2. `./gradlew build` (Gradle 8.13, Loom `1.10-SNAPSHOT`) -> jar en `build/libs/`.
3. Pon el jar en `mods/` junto a Fabric Loader, Fabric API y Meteor Client para 1.21.5.

Versiones en `gradle.properties`: Minecraft 1.21.5, Yarn `1.21.5+build.1`, Loader 0.16.14,
Meteor `1.21.5-SNAPSHOT`.

## Cambios 1.21.1 -> 1.21.5

Todo lo que depende de la version esta en `compat/Compat.java`:

- **Paquetes de movimiento** (`PlayerMoveC2SPacket`): desde 1.21.2 todos los constructores
  llevan el flag `horizontalCollision`.
- **VehicleMoveC2SPacket**: ahora es un record `(position, yaw, pitch, onGround)`; se crea con
  `VehicleMoveC2SPacket.fromVehicle(entity)`.
- `build.gradle` / `gradle.properties` / wrapper actualizados (Loom 1.10, Gradle 8.13,
  Meteor `1.21.5-SNAPSHOT`).

El resto de modulos (PlayerTP, InfiniteAnchor, InfiniteReach, AntiSpamKills) no necesitaron
cambios de codigo.

## Surround (`surround-plus`)

Portado del `SurroundModule` de Shoreline usando solo la API de Meteor. El nombre es distinto
del `surround` de Meteor para que no choquen las configuraciones guardadas.

Ajustes: `timing` (Vanilla/Sequential), `replace` (Normal/Strict/Fast), `blocks`, `auto-disable`,
`pause-on-use`, `place-range`, `blocks-per-tick`, `place-delay`, `attack-crystals`, `extend`,
`cover-head`, `support`, `rotate`, `strict-direction`, `stop-motion` y ajustes de render.

Lo que cambia respecto al original:

- **Sin MineExtend**: dependia del `AutoMineModule` y del `BlockManager` de Shoreline.
- **Sin comprobacion de SelfTrap ni AirPlace**: `cover-head` solo coloca el bloque si tiene un
  vecino donde apoyarse.
- **Colocacion propia** (en vez de `Managers.INTERACT`): elige la cara contra la que hacer click,
  hace el swap de hotbar con `InvUtils.swap/swapBack` y hace la rotacion silenciosa mandando un
  paquete de rotacion y otro de resincronizacion al final.
- No se hace click contra bloques que abren una GUI (cofres, mesas, puertas...) salvo que
  vayas agachado.
- **Sequential**: los paquetes llegan en el hilo de red, asi que la colocacion se pasa al hilo
  principal con `mc.execute`.
- Solo busca bloques en la **hotbar**.

## AutoCrawlTrap (`auto-crawl-trap`)

Pone un bloque encima de un enemigo que esta gateando para que no pueda levantarse. Solo funciona
contra jugadores que ya estan en pose de gateo (de pie su hitbox ocupa ese bloque y no se puede colocar).

- Comparte la logica de colocacion con Surround en `utils/BlockPlacer.java`.
- **Sin `PreventMine`**: dependia del `BlockManager` de Shoreline (progreso de minado de otros jugadores).
  El bloque roto se repone igualmente al recibir el paquete del servidor.
- `MultiTask` pasa a `pause-on-use` (invertido). Solo usa bloques de la hotbar.

## Phase (`phase`)

Modos: Normal, Sand, Climb, Pearl, Clip. Usa el `CollisionShapeEvent` de Meteor para quitar
colisiones, y **un mixin propio** (`mixin/ClientPlayerEntityMixin`, registrado en
`leinnerr-addon.mixins.json`) que cancela `pushOutOfBlocks` mientras Phase esta activo.

- **Sin `Raytrace`** (usaba el PearlManager de Shoreline para predecir donde cae la perla).
- **Pearl**: si la perla esta en el inventario (no en la hotbar) se pasa a la mano izquierda con un
  clic SWAP (boton 40) y se devuelve despues; `swap-alternative` fuerza ese metodo tambien con la
  perla en la hotbar. `attack` ataca item frames y rompe andamios que estorban.
- `isPhasing()` usa la forma de colision del bloque en vez de `BlockState.isSolid()`.
- En Normal, la comparacion de altura se hace en coordenadas del mundo (posicion del bloque + tope de su forma).
- Los modos Normal/Sand/Climb afectan a la colision de todas las entidades del cliente mientras esten activos,
  igual que en el original.

## L anchor (`l-anchor`)

AnchorAura de alcance normal (no es el InfiniteAnchor). Cada tick: elige al objetivo, busca en un radio
alrededor de el la mejor posicion de ancla (daño al objetivo menos daño propio, calculado con `DamageUtils.anchorDamage`
de Meteor, que tiene en cuenta armadura, encantamientos y bloques en medio) y entonces:
detona un ancla ya puesta (la carga con piedra luminosa si hace falta) o coloca una nueva.
Con `fast-break` coloca, carga y detona en el mismo tick.

- Necesita anclas y piedra luminosa en la hotbar. No funciona en el Nether (se desactiva solo).
- No debes llevar piedra luminosa en la mano izquierda: impide detonar (el modulo te avisa).
- Ajustes de seguridad: `max-self-damage`, `anti-suicide`, `min-health`, `lethal-override`.

## JugadorTP (`jugadorTP`)

Es el PlayerTP renombrado. Nuevo ajuste `ignore-friends` (activado por defecto): en modo Nearest
no elige a tus amigos. En modo Specific no se aplica, porque ahi nombras tu al jugador.
Al cambiar el nombre del modulo, sus ajustes guardados con el nombre anterior (`player-tp`) no se cargan.

## BotJoiner (`bot-joiner`) + comando `.bots`

Mete bots (solo servidores **cracked/offline**) y los manda a Anarquico:
conecta -> manda su comando (`/register ...` o `/login ...`) -> espera el mensaje de "logueado"
-> selecciona la brujula (hotbar) -> click derecho -> click en el slot del menu "Modalidades".

Ajuste `bots`, una linea por bot: `Nombre;contrasena` (ej. `Leinn_01;1234`). El bot escribe solo `/login` o `/register`
segun lo que pida el servidor. Tambien vale un comando fijo: `Leinn_01;/register 1234 1234`. Sin nada tras el `;` no hace login.
Ajustes de lobby: `compass-slot` (0 = primer slot de la hotbar), `menu-slot` (por defecto **20**, contando
desde 0: fila 3, columna 3), `login-success-text`, esperas y reintentos.

Auto-respawn: ajuste `auto-respawn` (activado por defecto) y `respawn-delay` (0 = instantaneo). Al morir, el bot manda el
respawn en el mismo momento en que el servidor avisa de la muerte, sin pantalla de muerte.

Farm de respawn en cama: `.bots farm <all|nombre>`
1. Cada bot listo (ya dentro de Anarquico) manda `/tpa <owner>` (ajuste `owner`, seccion "Farm / cama"), escalonados por `tpa-interval`.
2. Tu aceptas el tpa y te quedas quieto, pegado a la cama (el servidor exige estar a menos de 3 bloques; la cama se busca sola junto al owner).
3. Al llegar, los bots se turnan de UNO en UNO: click derecho a la cama, esperan el mensaje de "respawn guardado"
   (`bed-success-text`) y se levantan para dejarla libre al siguiente.
4. Cuando un bot lo logra, la consola muestra: `[Bot] OK - tomo el respawn de forma correcta en la cama.`
Ojo: no uses camas en el Nether/End (explotan).

Emulacion de cliente (`client-emulation`, activada por defecto): los bots confirman cada teletransporte, devuelven
su posicion, mandan su posicion cada segundo y "fin de tick" en cada tick, y avisan "Player Loaded" (1.21.5), igual que
un cliente real o mineflayer. Asi no tienen la inmunidad de ~3 s tras revivir y mueren al instante.

Consola (ventana aparte, se abre al activar el modulo o con `.bots console`):

| Escribes | Pasa |
|---|---|
| `Leinn_01: hola` | ese bot dice "hola" |
| `all: /spawn` | todos ejecutan `/spawn` |
| `!list` / `!leave all` | listar / sacar bots |

Por chat del juego: `.bots join`, `.bots leave [nombre]`, `.bots say <nombre|all> <texto>`, `.bots list`, `.bots console`.

Toda la libreria (MCProtocolLib) va metida en el jar via la configuracion `shade` de `build.gradle`.
Todo lo que depende de la version de la libreria esta en `bots/BotProtocol.java`.

## Aviso

El modulo BotJoiner tampoco se ha compilado. Este codigo **no se ha compilado ni probado** (el entorno donde se escribio no tiene `javac` ni
acceso a red). Las firmas de los paquetes se comprobaron contra la documentacion de Yarn 1.21.5,
pero el resto (Meteor `1.21.5-SNAPSHOT`) esta escrito de memoria. Si el compilador se queja de
algun nombre, pega el error y se corrige.
