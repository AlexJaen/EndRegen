# EndRegen

Plugin para **Paper 1.21.x / 26.x** (Java 21) que hace aparecer al **Dragón del End** en días y horas fijos, **sin reiniciar el servidor**, con vida y daño configurables, bossbar con la vida (`575/1000`), top de daño y reparto de drops entre el top 3.

## Características

- Horario configurable (`schedule.times`, p. ej. `"SATURDAY 20:00"`, zona horaria, avisos previos, recuperación tras caída).
- Solo el plugin puede crear al dragón; cualquier otro spawn se cancela. Regeneración opcional de los cristales de los pilares.
- Vida del dragón y multiplicadores de daño (melee / aliento) por config.
- Bossbar propia con la vida (`%health%/%max%`) para jugadores cerca del dragón.
- Top de daño al dragón y mensaje de muerte con medallas.
- Drops con `chance` y `min`/`max` de unidades; cada unidad se sortea entre el top N ponderando por % de daño. Tipos `ITEM` (vanilla o con nombre/lore/encantamientos, opción `no-use`) y `COMMAND` (`%player%`).
- Sin huevo de dragón como bloque; se abre el portal de salida al morir.
- Opcional: deshabilitar el portal del End desde el mundo normal (`portals.block-end-portal-from-overworld`).
- Placeholders PlaceholderAPI (opcional): `%endregen_next%`, `_next_seconds`, `_next_date`, `_status`, `_alive`, `_health`, `_top_N_name|damage|percent`, `_my_damage`, `_my_percent`.
- La isla **no se toca**: se protege con regiones de WorldGuard (ver más abajo).

## Comandos (permiso `endregen.admin`, por defecto solo op)

`/endregen status | top | spawn | remove | crystals | give <jugador> <idDrop> [cantidad] | reload`

## Compilar

```
mvn clean package
```

El jar queda en `target/endregen-<versión>.jar`; cópialo a `plugins/` y reinicia el servidor.

## Protección de la isla (WorldGuard)

El plugin no modifica el terreno. Define una región que cubra la isla con, por ejemplo: `build allow`, `block-break deny`, `block-place deny`, `enderdragon-block-damage deny`, explosiones en `deny`, `pvp deny`. No uses `build deny`: impediría golpear los cristales de los pilares y el dragón se curaría sin parar.

## Notas

- La vida máxima está limitada por `spigot.yml` → `settings.attribute.maxHealth.max` (2048 por defecto).
- El daño por explosiones (camas, cristales) no cuenta para el top.
- Compatibilidad probada en Paper 26.2; la API usada es la de Paper 1.21.11.
