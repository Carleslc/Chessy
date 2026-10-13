# Chessy — PRD (Product Requirements Document)

> Motor de ajedrez "constrictor": gana espacio, controla casillas y asfixia al rival limitando sus movimientos y posibilidades.

| | |
|---|---|
| Estado | Fase 0 completada · Fase 1 en curso |
| Lenguaje del motor | Kotlin (JVM 17+) en `engine/` |
| Herramientas | Python 3 en `tools/` |
| Protocolo | UCI |
| Plataforma de juego objetivo | Lichess (Bot API + `lichess-bot`) |
| Objetivo de fuerza | ≥ 2000 Elo |

---

## 1. Visión

Chessy es un motor de ajedrez propio cuya evaluación se basa principalmente en el concepto ajedrecístico de **espacio** y sus heurísticas relacionadas: **control de casillas**, **movilidad** y **restricción del rival**. Juega como una serpiente constrictora: no busca el golpe táctico inmediato, sino ir ganando terreno, quitar casillas a las piezas rivales, atrapar piezas, inhabilitar planes y esperar a que la posición contraria colapse.

Sin descuidar lo necesario para jugar bien (táctica, material, seguridad del rey, estructura de peones), el motor debe entender que **el valor de una pieza no es estático**: depende de su actividad y potencial. Un caballo que domina el centro puede valer más que una torre encerrada. Esto le debe permitir:

- jugar con desventaja material cuando la compensación posicional lo justifica;
- sacrificar piezas poco activas a cambio de mejor posición;
- preferir intercambios favorables (cambiar sus piezas malas por las buenas del rival);
- atrapar piezas rivales y reducir su movilidad;
- evitar simplificaciones que liberen al rival cuando este está ahogado.

## 2. Objetivos

1. **Fuerza**: nivel ≥ 2000 Elo (medido contra motores de referencia y en Lichess, ver §9).
2. **Estilo reconocible**: el motor debe maximizar espacio y control, y esto debe ser medible (§9.4).
3. **Evaluación reemplazable**: la búsqueda no depende de la implementación de la evaluación; se puede cambiar por otra (otra personalidad, una evaluación clásica, una red NNUE…) sin tocar la búsqueda.
4. **Legibilidad**: código claro y documentado, con nombres explícitos. El rendimiento se busca con buenas estructuras de datos (bitboards, sin asignaciones en el bucle de búsqueda), no con trucos ilegibles. Stockfish es la referencia de lo que *no* queremos en legibilidad.
5. **Interoperabilidad**: UCI completo para funcionar en cualquier GUI (Arena, Cute Chess, BanksiaGUI…), en herramientas de test (fastchess / cutechess-cli) y en Lichess vía `lichess-bot`.
6. **Reglas completas**: enroque, captura al paso, todas las promociones, jaque mate, ahogado, regla de 50 movimientos, triple repetición, material insuficiente.

### No objetivos (por ahora)

- Competir con Stockfish o motores top (> 3000).
- Jugar en Chess.com: su API pública es de solo lectura y su política de juego limpio prohíbe la asistencia de motores en partidas; **no existe una vía para bots propios**. Se usará Lichess.
- Variantes (Chess960 queda como posible extensión futura).
- GUI propia para jugar (se usan GUIs UCI existentes; el visor de `tools/` es para análisis).

## 3. Casos de uso

- Jugar contra Chessy desde una GUI UCI local.
- Chessy jugando como **BOT** en Lichess (retos de humanos y de otros bots).
- Analizar una posición (FEN) o una partida (PGN) viendo el desglose de la evaluación (cuánto aporta el espacio, la movilidad, el material…).
- Comparar versiones del motor entre sí (SPRT) y contra motores de referencia.
- Experimentar con otras evaluaciones/personalidades cambiando solo el evaluador.

## 4. Arquitectura

### 4.1 Estructura del repositorio

```
Chessy/
├── docs/            PRD y documentación
├── engine/          Motor en Kotlin (Gradle)
│   └── src/main/kotlin/chessy/
│       ├── core/    Tablero (bitboards), ataques, movimientos, FEN, Zobrist, perft
│       ├── search/  Búsqueda (alfa-beta, TT, quiescencia, ordenación…)
│       ├── eval/    Interfaz Evaluator + implementaciones (Material, Constrictor…)
│       ├── uci/     Protocolo UCI y gestión del tiempo
│       └── Main.kt  CLI (uci por defecto, perft, divide, eval, bench…)
└── tools/           Herramientas Python alrededor del motor
    ├── board.py, game.py, app/, utils/   Visor de FEN/PGN (PyQt5 + python-chess)
    ├── analysis/    (futuro) análisis de partidas, gráficas de espacio/movilidad
    ├── match/       (futuro) scripts de fastchess/cutechess, SPRT
    ├── tuning/      (futuro) Texel tuning de pesos de evaluación
    └── lichess/     (futuro) configuración de lichess-bot
```

La rama `kotlin-old` conserva la primera implementación (2018–2019), arreglada para ser jugable en consola (ver §12).

### 4.2 Módulos del motor

```
            ┌─────────┐
  GUI ⇄ UCI │  uci/   │──── tiempo, opciones, info
            └────┬────┘
                 │ go / stop
            ┌────▼────┐   evaluate(pos)   ┌──────────┐
            │ search/ │──────────────────▶│  eval/   │  (interfaz Evaluator)
            └────┬────┘                   └────┬─────┘
                 │ make/unmake, movegen        │ ataques, bitboards
            ┌────▼─────────────────────────────▼────┐
            │                 core/                  │
            └────────────────────────────────────────┘
```

- `core` no depende de nada. `eval` depende solo de `core`. `search` depende de `core` y de la *interfaz* de `eval`. `uci` lo une todo.

## 5. Diseño técnico

### 5.1 Representación del tablero (core)

- **Bitboards** (`Long`, 64 bits), mapeo **LERF** (a1 = 0, b1 = 1, …, h8 = 63).
- Un bitboard por (color, tipo de pieza) → 12, más ocupación por color y total.
- **Mailbox** redundante (`IntArray(64)`) para saber qué pieza hay en una casilla en O(1).
- Estado: bando al mover, derechos de enroque (4 bits), casilla de captura al paso, contador de 50 movimientos, número de jugada, clave Zobrist.
- **Hacer/deshacer** (make/unmake) sobre la misma posición con una pila de estados (sin copiar tableros, sin asignar objetos).
- **Ataques**:
  - Caballo, rey y peón: tablas precalculadas por casilla.
  - Piezas deslizantes: *classical approach* con rayos precalculados (8 direcciones) y el primer bloqueador vía `numberOfTrailingZeros`/`numberOfLeadingZeros`. Es legible y suficientemente rápido. Más adelante, si hace falta, se puede cambiar por *magic bitboards* detrás de la misma API (`bishopAttacks(sq, occ)`, `rookAttacks(sq, occ)`).
  - `attackersTo(square, occupancy)` y `isAttacked(square, byColor)` — imprescindibles también para la evaluación (control de casillas) y para SEE.
- **Movimientos** codificados en un `Int` (16 bits: origen 6, destino 6, flags 4: silencioso, doble avance, enroques, captura, al paso, promociones). Listas de movimientos en `IntArray` preasignados por ply.
- **Generación**: pseudo-legal + filtro de legalidad (hacer → ¿rey atacado? → deshacer). Más adelante, si es un cuello de botella, generación legal con pins/jaques.
- **Zobrist hashing** incremental (piezas, bando, enroques, columna de al paso) con semilla fija (reproducible).
- **FEN**: lectura y escritura.
- **Perft** y **divide** para validar la generación.

### 5.2 Búsqueda (search)

Base:
- Negamax con poda alfa-beta, **iterative deepening**, puntuaciones de mate con distancia (`MATE - ply`).
- **Quiescence search** (capturas y promociones; jaques opcional) con *stand pat* y *delta pruning*.
- **Ordenación de jugadas**: jugada de la TT → capturas buenas (SEE ≥ 0, MVV-LVA) → promociones → *killers* → *countermove* → *history* → capturas malas.
- **Tabla de transposición** (Zobrist): entradas con profundidad, tipo de cota (EXACT/LOWER/UPPER), mejor jugada y ajuste de puntuaciones de mate por ply; reemplazo por profundidad/edad.
- Detección de **repetición** y regla de los 50 movimientos dentro de la búsqueda.

Avanzado (cada técnica entra solo si gana en SPRT):
- **PVS** (Principal Variation Search) y **aspiration windows**.
- **Null move pruning** (desactivado en finales con riesgo de zugzwang).
- **LMR** (Late Move Reductions), **futility** / **reverse futility pruning**, **late move pruning**.
- **Extensión por jaque**.
- **SEE** (Static Exchange Evaluation): atacantes y defensores sobre una casilla, para ordenar y podar capturas.
- Más adelante: Lazy SMP (multihilo), *pondering*.

Gestión del tiempo: presupuesto por jugada a partir de `wtime/btime/winc/binc/movestogo`, con margen de seguridad; soporta `depth`, `nodes`, `movetime`, `infinite` y `stop`.

### 5.3 Evaluación (eval)

#### Interfaz reemplazable

```kotlin
interface Evaluator {
    /** Puntuación en centipeones desde el punto de vista del bando que mueve. */
    fun evaluate(position: Position): Int

    /** Desglose por términos (para depurar, analizar y ajustar). */
    fun explain(position: Position): EvalTrace
}
```

- La búsqueda solo conoce `Evaluator`. La implementación se elige con la opción UCI `setoption name Evaluator value <Constrictor|Material|…>`.
- Los **pesos** viven en una estructura de datos (cargable desde fichero) para poder ajustarlos sin recompilar y hacer *Texel tuning*.
- **Evaluación cónica** (*tapered*): cada término tiene valor de medio juego (mg) y de final (eg), interpolados según la fase (material no-peón restante).
- La evaluación es simétrica por defecto (evaluar una posición y su espejo da lo mismo con signo opuesto) — se comprueba con un test. La "personalidad" se expresa con los pesos.
- `explain` + comando CLI `eval <fen>` muestran cada término por bando, incluido el **valor dinámico de cada pieza**.

#### Evaluador "Constrictor" (principal)

Prioridad alta (núcleo del estilo):

| Término | Idea | Implementación con bitboards |
|---|---|---|
| **Espacio** | Casillas seguras ganadas en el centro y en el propio campo detrás de la cadena de peones | Casillas de las columnas c–f, filas 2–4 (relativas), no atacadas por peones rivales ni ocupadas por peones propios; doble valor si están detrás de un peón propio. Peso escalado por el número de piezas en el tablero (el espacio importa más con muchas piezas) y principalmente en mg |
| **Control de casillas** | Quién domina cada casilla del tablero | Mapa de control por casilla (número de atacantes y atacante de menor valor por bando). Bonus por casillas dominadas, más en el campo rival, en el centro y en la zona del rey rival |
| **Movilidad segura** | Movilidad = actividad = valor | Por pieza: casillas alcanzables del "área de movilidad" (excluye casillas atacadas por peones rivales y ocupadas por peones/rey propios). Tabla no lineal `Mobility[tipo][n]` (mg/eg) |
| **Restricción del rival** | Asfixiar: quitarle casillas | Casillas atacadas por ambos donde el rival no tiene control suficiente; movilidad segura del rival; bonus por casillas de entrada (puestos) negadas al rival |
| **Piezas atrapadas** | Piezas sin futuro | Penalización a piezas con ≤ 1–2 casillas seguras, mayor si además están atacadas; torre encerrada por su propio rey, alfil atrapado (a7/h7…) |
| **Valor dinámico de piezas** | El material depende de la actividad | Valor efectivo = base + movilidad + puesto avanzado − atrapada − alfil malo. Esto permite sacrificios y cambios "malo por bueno" |

Prioridad normal (jugar correctamente):

- **Material** base (mg/eg) y pareja de alfiles.
- **Tablas de casillas** (PST) suaves; el espacio y la movilidad hacen la mayor parte del trabajo posicional.
- **Amenazas**: piezas colgadas, piezas atacadas por otras de menor valor, avances de peón seguros que amenazan piezas, jaques seguros disponibles.
- **Seguridad del rey**: unidades de ataque sobre la zona del rey, escudo de peones, columnas abiertas cerca del rey.
- **Estructura de peones**: doblados, aislados, retrasados, pasados (por fila, camino libre, proximidad de reyes en el final), conectados, cadenas.
- **Puestos avanzados** (outposts) para caballos y alfiles; torres en columna abierta/semiabierta y en 7ª.
- **Tempo**.
- **Contempt**: penalización ligera de las tablas cuando no estamos peor, y preferencia por mantener piezas cuando tenemos ventaja de espacio (el bando apretado quiere cambiar piezas).

Ideas recogidas de la rama `python` (README de 2022) a evaluar experimentalmente:
- Valores de material en centipeones P=100, N=320, B=330, R=500, Q=900.
- Valor de pieza multiplicado por un factor de control (casillas controladas / máximas: P=2, N=8, B=13, R=14, Q=27, K=8). *Nota*: un término multiplicativo es más inestable y difícil de ajustar que la suma de una tabla de movilidad; se probará como alternativa contra la tabla aditiva.
- Mapas de ataque (∑ piezas atacadas × valor), mapas de defensa (∑ piezas defendidas × valor × 0.5), clavadas, peón aislado de dama (IQP).

### 5.4 UCI

Comandos: `uci`, `isready`, `ucinewgame`, `position [startpos|fen …] [moves …]`, `go [wtime btime winc binc movestogo depth nodes movetime infinite]`, `stop`, `quit`, `setoption`.
Salida `info depth seldepth score cp|mate nodes nps time pv hashfull`.
Opciones: `Hash`, `Threads` (futuro), `Evaluator`, `Contempt`, `Move Overhead`, `UCI_ShowWDL` (opcional).
La búsqueda corre en un hilo aparte para atender `stop`/`isready`.

Comandos extra (no UCI) para depurar: `d` (mostrar tablero + FEN + clave), `perft N`, `eval` (desglose de evaluación), `bench` (posiciones fijas → nodos y nps, útil para detectar cambios de comportamiento).

## 6. Requisitos no funcionales

- JVM 17+, sin dependencias en tiempo de ejecución salvo la stdlib de Kotlin.
- Sin asignaciones de objetos en el bucle caliente (movimientos como `Int`, listas preasignadas, make/unmake).
- Rendimiento orientativo (a medir en la máquina de desarrollo):
  - perft ≥ 5 M nodos/s (un hilo, sin *bulk counting*);
  - búsqueda ≥ 500 k nps con evaluación de material y ≥ 150 k nps con la evaluación Constrictor completa.
- Determinismo: misma posición + mismos límites de nodos → misma jugada (para `bench` y regresiones).
- Tests automáticos (`./gradlew test`) rápidos (< 1 min); tests lentos marcados aparte.

## 7. Herramientas (tools/)

- **Visor** (ya existe): FEN y PGN con PyQt5 + `python-chess`.
- **Análisis de partidas**: recorrer un PGN preguntando al motor (UCI) la evaluación y su desglose, y graficar espacio, movilidad propia/rival y material a lo largo de la partida.
- **Validación**: comparar `divide` del motor con `python-chess` en posiciones aleatorias para localizar errores de generación.
- **Matches**: scripts para `fastchess`/`cutechess-cli` (SPRT entre versiones, partidas contra Stockfish limitado).
- **Tuning**: Texel tuning de los pesos sobre posiciones etiquetadas.
- **Lichess**: configuración de `lichess-bot` apuntando al ejecutable UCI del motor.
- **CLI**: lanzadores para jugar contra el motor en consola o en una GUI.

## 8. Despliegue

- Empaquetado: `./gradlew installDist` → script `engine/build/install/chessy/bin/chessy` (UCI). Opcional: GraalVM `native-image` para arrancar al instante.
- **Lichess**: cuenta nueva convertida a BOT (irreversible; la cuenta no puede volver a jugar como humano) + `lichess-bot`. Los bots solo juegan retos (no torneos ni emparejamiento por pools).
- Opcional: libro de aperturas (Polyglot) y tablas de finales Syzygy en fases avanzadas.

## 9. Testing y medición

1. **Corrección**: perft de las posiciones de referencia de chessprogramming.org (inicial, Kiwipete, posiciones 3–6), consistencia de Zobrist (incremental = recalculada), ida y vuelta de FEN, simetría de la evaluación.
2. **Táctica**: suites EPD (Win At Chess, etc.) con tiempo fijo; mates en N.
3. **Posicional**: Strategic Test Suite (STS), con especial atención a las categorías de control del centro, espacio y juego de peones.
4. **Estilo**: métricas por partida: movilidad media del rival, diferencial de espacio, número de piezas rivales atrapadas, % de partidas ganadas con material inferior.
5. **Fuerza**:
   - SPRT (fastchess/cutechess-cli) para aceptar cada cambio: `elo0=0, elo1=5` (o similar) a control de tiempo corto.
   - Calibración contra Stockfish con `UCI_LimitStrength`/`UCI_Elo` y contra motores de la lista CCRL de nivel conocido.
   - Rating del BOT en Lichess.

## 10. Fases

Cada fase termina con sus criterios de aceptación cumplidos y fusionada en `master`.

### Fase 0 — Reorganización ✅
- Rama `kotlin` → `kotlin-old`; contenido de `python` → `master` en `tools/`.
- PRD.
- Arreglos en `kotlin-old` para que sea jugable en consola (§12).

### Fase 1 — Núcleo de reglas (core)
- Bitboards, casillas, piezas, tablas de ataques (incluidas deslizantes con rayos).
- Codificación de movimientos, listas preasignadas.
- Posición con make/unmake, enroque, al paso, promociones, contador de 50 movimientos.
- Zobrist incremental.
- FEN (lectura/escritura), notación UCI de movimientos (`e2e4`, `e7e8q`).
- Perft/divide + CLI (`perft`, `divide`, `d`).
- **Aceptación**: perft correcto en las 6 posiciones de referencia (tests), Zobrist consistente, FEN ida y vuelta, rendimiento de perft medido.

### Fase 2 — Protocolo y juego básico
- Bucle UCI completo (§5.4) con búsqueda en hilo aparte y `stop`.
- Detección de tablas: repetición (historial de claves), 50 movimientos, material insuficiente.
- Jugador provisional (aleatorio o material a 1 ply) para validar el protocolo.
- Tools: script para jugar contra el motor y enganche con el visor; prueba local de `lichess-bot`.
- **Aceptación**: 1000 partidas en fastchess sin jugadas ilegales, *timeouts* ni errores de protocolo.

### Fase 3 — Búsqueda base + evaluación base
- Interfaz `Evaluator` + `MaterialEvaluator` (material + PST cónicas).
- Negamax alfa-beta, iterative deepening, quiescence, MVV-LVA, mates con distancia, gestión de tiempo.
- `bench`.
- **Aceptación**: resuelve mates en 1–3 y un subconjunto de WAC; gana claramente al jugador de la Fase 2. Estimación ~1500–1800.

### Fase 4 — Búsqueda avanzada
- TT, PVS, aspiration windows, killers/history/countermove, null move, LMR, futility, extensión por jaque, SEE.
- **Aceptación**: cada técnica aceptada por SPRT; estimación ≥ 2000 con la evaluación base.

### Fase 5 — Evaluación Constrictor
- Términos de §5.3 (prioridad alta primero), `explain`/`eval`, pesos en fichero, opción UCI `Evaluator`.
- Tools: análisis de partidas con gráficas de espacio/movilidad.
- **Aceptación**: SPRT positivo frente al `MaterialEvaluator` con la misma búsqueda; mejora en las categorías de STS relacionadas; métricas de estilo (§9.4) claramente distintas de la evaluación base.

### Fase 6 — Ajuste y medición
- Texel tuning de pesos, ajuste de parámetros de búsqueda, calibración contra Stockfish limitado / CCRL.
- **Aceptación**: ≥ 2000 Elo medido.

### Fase 7 — Despliegue y extras
- BOT en Lichess con `lichess-bot`, empaquetado.
- Opcionales: libro de aperturas, Syzygy, pondering, Lazy SMP, evaluadores alternativos (p. ej. NNUE) gracias a la interfaz.

## 11. Riesgos

| Riesgo | Mitigación |
|---|---|
| Una evaluación pesada (mapas de control) reduce la profundidad y cuesta más Elo del que aporta | Medir nps; calcular ataques una vez por evaluación y reutilizarlos en todos los términos; *lazy eval* (salir pronto si el material está muy fuera de la ventana); SPRT de cada término |
| Sobrevalorar el espacio → regalar material o ignorar táctica | Quiescence + SEE + amenazas; Texel tuning; suites tácticas como regresión |
| Errores sutiles de reglas | Perft exhaustivo + validación cruzada con python-chess |
| Pérdida de legibilidad por optimizar | Optimizar solo lo que muestre el profiler; API estable para cambiar implementaciones (p. ej. magic bitboards) |
| JVM: calentamiento y GC | Sin asignaciones en búsqueda; `bench` al iniciar opcional; GraalVM native-image |
| La cuenta BOT de Lichess es irreversible | Usar una cuenta nueva dedicada |

## 12. Legado: rama `kotlin-old`

Primera implementación (2018–2019): piezas como objetos, tablero como mapa/matriz, minimax alfa-beta. Se corrigieron los errores graves para dejarla jugable en consola:

1. Signo de la evaluación invertido según la paridad de la profundidad (a profundidad 2 evitaba el mate en 1) → negamax.
2. Profundidad cambiando en mitad de la búsqueda raíz → iterative deepening con profundidad uniforme por iteración.
3. Caché que guardaba cotas alfa/beta como valores exactos y copiaba la puntuación al rival → TT con tipo de cota.
4. `setCheck` modificaba la tabla del caballo (estado global) → tabla del rey según la fase de la partida.
5. Tablas puntuadas como −10000 → 0.
6. Ahogado detectado con movimientos pseudo-legales → estado calculado con movimientos legales y detección de ataques al rey.
7. Ordenación que generaba y evaluaba el tablero siguiente para cada jugada (y fugas de memoria por cachés que retenían el árbol) → MVV-LVA barato.

Sigue sin enroque, captura al paso, subpromoción ni UCI: queda como referencia histórica.

## 13. Referencias

- https://www.chessprogramming.org/Getting_Started
- https://www.chessprogramming.org/UCI
- https://www.chessprogramming.org/Bitboards · https://www.chessprogramming.org/Classical_Approach
- https://www.chessprogramming.org/Encoding_Moves · https://www.chessprogramming.org/Perft_Results
- https://www.chessprogramming.org/Space · https://www.chessprogramming.org/Mobility · https://www.chessprogramming.org/Square_Control
- https://www.chessprogramming.org/Static_Exchange_Evaluation · https://www.chessprogramming.org/MVV-LVA
- https://www.chessprogramming.org/Tapered_Eval · https://www.chessprogramming.org/Texel%27s_Tuning_Method
- https://chess.phd/learn/terms/space/
- https://www.chessworld.net/chessclubs/openingguide/chess-space-control.asp
- https://www.chess.com/article/view/space-and-the-attack
- https://github.com/lichess-bot-devs/lichess-bot · https://lichess.org/api#tag/Bot
- https://github.com/Disservin/fastchess · https://github.com/cutechess/cutechess
