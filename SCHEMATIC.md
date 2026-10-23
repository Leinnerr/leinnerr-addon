# Modulo schematic (render + place)

Archivos nuevos: `schematic/` (lector y estado), `modules/SchematicModule.java`,
`commands/SchematicCommand.java`. Cambiado: `LeinnerrAddon.java` (registro).

## Uso
1. `.schem folder` muestra la carpeta (`<carpeta del juego>/leinnerr/schematics`). Copia ahi tus `.litematic`, `.schem` o `.nbt`.
2. `.schem list` y `.schem load <nombre>` (se coloca donde estas parado).
3. Ajusta donde va: `.schem origin` (donde estas), `.schem origin look` (encima del bloque que miras),
   `.schem origin x y z`, `.schem move x y z`, `.schem rotate [90|180|270]`.
   El origen es la esquina de MENOR x/y/z del schematic.
4. Activa el modulo `schematic` y marca las casillas: `render` (dibuja lo que falta/esta mal) y/o `place` (lo construye). Cada casilla muestra solo sus ajustes.
5. `.schem info` muestra tamano, bloques y origen. `.schem unload` lo descarga.

## Limitaciones
- El overlay son cajas de color (color de mapa del bloque), no los modelos con textura de Litematica.
- Solo se copia el bloque y su estado: no el contenido de cofres, textos de carteles ni otros datos (NBT de bloque).
- Losas dobles, mitades superiores de puertas/plantas y cabeceras de cama no se colocan solas: se colocan con su pareja.
- `state-mode = Exact` solo prueba 4 orientaciones horizontales + arriba/abajo; bloques con 16 rotaciones
  (carteles de pie, estandartes) no saldran exactos: usa `Prefer` o `Block`.
- Necesitas los bloques en el inventario (en creativo, `creative-pick` los pide solo).
