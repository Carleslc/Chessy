# Chessy — PRD (Product Requirements Document)

> Motor de ajedrez "constrictor": gana espacio, controla casillas y asfixia al rival limitando sus movimientos y posibilidades.

| | |
|---|---|
| Versión | v1.0-dev (ver §8, Versiones) |
| Estado | Fases 0, 1 y 2 completadas · siguiente: Fase 3 (búsqueda y evaluación base) |
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

La rama `v0` conserva la primera implementación (2018–2019), arreglada y con las reglas completas para ser jugable en consola (ver §12). La rama `python-old` conserva el visor de 2022 tal como estaba.

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
- **Perft** y **divide** para validar la generación (también en paralelo, ver §5.5).
- **Notaciones**: SAN (`e4`, `Nf3`, `Ngf6`, `N4f6`, `Qh4e1`, `Rxf3`, `Nf3+`, `Qh1#`, `O-O`, `O-O-O`, `exd6 e.p.`, `a8=Q`, `bxa8=N+`) para escribir y leer jugadas (lectura tolerante: `0-0`, `Qh1++`, `!?`, captura o promoción a dama implícitas) y UCI (`e2e4`, `e7e8q`).
- **Partidas** (`Game`) con estado y resultado, y **PGN**: exportación (Seven Tag Roster, `SetUp`/`FEN` si no empieza en la posición inicial, líneas de 80 caracteres) e importación (varias partidas por fichero, comentarios, variantes y NAGs ignorados).
- **Reglas completas**: enroques, captura al paso, todas las promociones, jaque mate, ahogado, material insuficiente (solo reyes, una pieza menor, o alfiles del mismo color), regla de los 50 movimientos y triple repetición (comparando claves Zobrist desde el último movimiento irreversible). Como hacen los motores y las plataformas online, las tablas reclamables (50 movimientos y triple repetición) se aplican directamente.
- **Sin librerías de ajedrez** (como chesslib en la JVM): el núcleo propio está validado con perft y contra `python-chess` (`tools/perft_check.py --san`), es necesario de todos modos para que la evaluación acceda a los bitboards y ataques, y evita dependencias. `python-chess` se usa solo como oráculo de pruebas y en las herramientas.

### 5.2 Búsqueda (search)

Base:
- Negamax con poda alfa-beta, **iterative deepening**, puntuaciones de mate con distancia (`MATE - ply`).
- **Quiescence search** (capturas y promociones; jaques opcional) con *stand pat* y *delta pruning*.
- **Ordenación de jugadas**: jugada de la TT → capturas buenas (SEE ≥ 0, MVV-LVA) → promociones → *killers* → *countermove* → *history* → capturas malas.
- **Tabla de transposición** (Zobrist): entradas con profundidad, tipo de cota (EXACT/LOWER/UPPER), mejor jugada y ajuste de puntuaciones de mate por ply; reemplazo por profundidad/edad.
- Detección de **repetición** y regla de los 50 movimientos dentro de la búsqueda (en la búsqueda basta una repetición para puntuar tablas).

Avanzado (cada técnica entra solo si gana en SPRT):
- **PVS** (Principal Variation Search) y **aspiration windows**.
- **Null move pruning** (desactivado en finales con riesgo de zugzwang).
- **LMR** (Late Move Reductions), **futility** / **reverse futility pruning**, **late move pruning**.
- **Extensión por jaque**.
- **SEE** (Static Exchange Evaluation): atacantes y defensores sobre una casilla, para ordenar y podar capturas.
- **Lazy SMP** (multihilo, §5.5) y *pondering* (pensar durante el turno del rival).
- Precaución con el **zugzwang**: el estilo constrictor busca precisamente posiciones en las que el rival se queda sin jugadas útiles, que es donde el *null move pruning* falla. Se usará *verification search* o se desactivará con poco material no-peón.

#### Profundidad variable y gestión del tiempo

La profundidad **no es fija**: la búsqueda es por **tiempo**. La profundización iterativa (*iterative deepening*) busca a profundidad 1, 2, 3… hasta agotar el presupuesto de la jugada, y juega la mejor jugada de la última iteración completada (o la mejor encontrada en la iteración en curso si ya se ha buscado la jugada principal). Así se aprovecha el tiempo según la posición:

- En **finales** hay menos piezas y jugadas (factor de ramificación pequeño) y la tabla de transposición acierta mucho, así que en el mismo tiempo se llega bastante más profundo, lo que permite ver **mates forzados lejanos** y promociones.
- En posiciones tácticas, las **extensiones** (jaque, única jugada legal) y la quiescencia profundizan solo en las líneas forzadas; las **reducciones** (LMR) y podas recortan las líneas poco prometedoras. Por eso se distingue entre la profundidad nominal (`depth`) y la máxima alcanzada (`seldepth`).
- **Presupuesto por jugada** a partir de `wtime/btime/winc/binc/movestogo` con margen de seguridad (`Move Overhead`), con dos límites:
  - *suave*: no empezar una nueva iteración si probablemente no terminará;
  - *duro*: abortar la búsqueda en curso.
- **Ajustes dinámicos**: menos tiempo si la mejor jugada es estable entre iteraciones o es la única jugada, más tiempo si cambia o la puntuación cae (*fail low*).
- Si se encuentra un mate forzado se deja de profundizar.
- También se puede limitar por `depth`, `nodes` o `movetime`, o buscar hasta `stop` (`infinite`), para análisis y tests.

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

#### El espacio en detalle

Ideas de [chessprogramming.org/Space](https://www.chessprogramming.org/Space), [chess.phd](https://chess.phd/learn/terms/space/), [chessworld.net](https://www.chessworld.net/chessclubs/openingguide/chess-space-control.asp) y [Space and the Attack](https://www.chess.com/article/view/space-and-the-attack) (Bryan Smith), convertidas en términos de evaluación:

1. **Espacio base** (como el término clásico de Stockfish): casillas *seguras* para piezas menores en las columnas c–f y filas 2–4 relativas (no atacadas por peones rivales ni ocupadas por peones propios), contando doble las que están detrás de un peón propio (hasta 3 filas). Peso que crece con el número de piezas propias y de peones bloqueados (cadenas cerradas), y que se desactiva con poco material: `espacio × peso² / 16`. Es el punto de partida, no el término completo.
2. **Territorio en el campo rival**: casillas de la mitad rival que controlamos de forma segura (controladas por más atacantes o por atacantes de menor valor). Toga II premia de forma no lineal las piezas situadas en la mitad rival, más cuanto más avanzadas.
3. **El espacio solo vale si las piezas lo ocupan** ("el espacio es una señal, no una puntuación"): el bonus de territorio se multiplica por la capacidad de nuestras piezas de usarlo (movilidad segura hacia esas casillas). Peones avanzados sin piezas detrás no cuentan como espacio, sino como debilidad.
4. **Sobreextensión y agujeros**: cada avance de peón deja atrás casillas que ningún peón podrá volver a defender. Penalización por peones avanzados sin apoyo y por **agujeros** en nuestro campo alcanzables por caballos o alfiles rivales (puestos avanzados para el rival).
5. **Rupturas de peón** (*pawn breaks*): el bando apretado se libera con una ruptura (p. ej. …c5 o …f6 contra la cadena d4–e5). Evaluar las rupturas disponibles y seguras de cada bando; el constrictor gana puntos **impidiendo las rupturas liberadoras** del rival (controlando la casilla de ruptura o fijando el peón).
6. **Política de cambios según el espacio**: con ventaja de espacio conviene **mantener piezas** (al rival le falta sitio para todas); el bando apretado quiere cambiar. Bonus de espacio escalado por el material en el tablero, de forma que cambiar piezas reduzca la ventaja del constrictor y lo desincentive, salvo cambios que eliminen la **pieza más activa del rival** (visión moderna: el espacio vale por sí mismo).
7. **La peor pieza**: "mejora tu peor pieza" y "la cadena es tan fuerte como su eslabón más débil". Penalización según la movilidad segura de la pieza menos activa de cada bando, además de la suma total; para el rival, cuanto más atrapada esté su peor pieza, mejor.
8. **Dirección de la cadena de peones**: se ataca en el flanco hacia el que apuntan los peones (en la Francesa Avance, e5 apunta al flanco de rey). Bonus de ataque al rey cuando nuestra cadena apunta hacia su enroque y tenemos más espacio en ese flanco.
9. **Espacio cerca del rey rival**: controlar las casillas alrededor del rey es lo que acaba dando mate. El control de casillas se pondera más en la zona del rey rival y alimenta la seguridad del rey.
10. **Restricción** (término `restricted` de Stockfish): casillas que el rival ataca pero que no tiene bien protegidas y que nosotros también atacamos; se traduce en menos movilidad segura para él.
11. **Espacio en el final**: ventaja de espacio = mejores rutas para el rey y más opciones de peón pasado; el término de espacio se transforma en actividad del rey y control de casillas de avance.
12. **Paciencia**: no premiar los avances de peón por sí mismos; el bonus está en las casillas que controlan y en las piezas que las usan. Esto evita que el motor fuerce rupturas prematuras.

#### Ideas del README de la rama `python-old` (2022)

| Idea del README | Cómo se incorpora |
|---|---|
| Material en centipeones P=100, N=320, B=330, R=500, Q=900 | Valores base de material (mg/eg), el punto de partida antes del *tuning* |
| Rey = 400 o 11000; jaque mate ∞ opcional | El mate se puntúa en la búsqueda (`MATE - ply`), así que el rey no necesita valor de material. El "400" se recoge como valor de **actividad del rey en el final** (PST de final y control de casillas del rey) |
| Valor de pieza × factor de control (casillas controladas / máximas: P=2, N=8, B=13, R=14, Q=27, K=8) | Sustituido por **valor dinámico aditivo**: base + `Mobility[tipo][n]` no lineal + puestos − atrapada − alfil malo. El producto es inestable (una dama con poca movilidad perdería casi todo su valor, y una pequeña variación de casillas cambia cientos de centipeones) y difícil de ajustar. Se mantendrá como evaluador experimental para compararlo con SPRT gracias a la interfaz `Evaluator` |
| Movilidad (jugadas legales × 10) | Movilidad **segura** con tablas no lineales por tipo de pieza (contar casillas atacadas por peones rivales premia jugadas que pierden material) |
| Control de casillas (casillas atacadas × 20) [espacio] | Términos de espacio y control de casillas con dominio por casilla (§ anterior) |
| Mapas de ataque (∑ piezas atacadas × valor) | Término de **amenazas** (piezas colgadas, atacadas por piezas de menor valor) y **SEE** en la búsqueda |
| Peones pasados por proximidad a la promoción (∑ fila relativa × P) | Peones pasados por fila relativa, camino libre y distancia de los reyes en el final |
| Mapas de defensa (∑ piezas defendidas × valor × 0.5), opcional | Parte de amenazas: piezas no defendidas (colgadas) penalizan; la defensa en sí apenas suma para no premiar posiciones pasivas |
| Clavadas, IQP (peón aislado de dama), opcionales | Clavadas: amenaza y menos movilidad de la pieza clavada. IQP: dentro de la estructura de peones (débil en el final, activo en el medio juego) |

### 5.4 UCI

Comandos: `uci`, `isready`, `ucinewgame`, `position [startpos|fen …] [moves …]`, `go [wtime btime winc binc movestogo depth nodes movetime infinite]`, `stop`, `quit`, `setoption`.
Salida `info depth seldepth score cp|mate nodes nps time pv hashfull`.
Opciones: `Hash`, `Threads` (futuro), `Evaluator`, `Contempt`, `Move Overhead`, `UCI_ShowWDL` (opcional).
La búsqueda corre en un hilo aparte para atender `stop`/`isready`.

Comandos extra (no UCI) para depurar: `d` (mostrar tablero + FEN + clave), `perft N`, `eval` (desglose de evaluación), `bench` (posiciones fijas → nodos y nps, útil para detectar cambios de comportamiento).

### 5.5 Paralelismo

**CPU, no GPU.** La búsqueda alfa-beta es secuencial por naturaleza (cada poda depende de los resultados anteriores), con mucha ramificación condicional y acceso aleatorio a memoria (tabla de transposición): justo lo que peor se le da a una GPU, que necesita miles de hilos haciendo la misma operación sobre datos contiguos. Las GPU se usan en motores con redes neuronales grandes y búsqueda MCTS (como Leela Chess Zero), que evalúan lotes de posiciones a la vez; no encaja con una evaluación clásica y alfa-beta. La evaluación de Chessy (bitboards, `popcount`) es rapidísima en CPU y no compensaría el coste de transferir posiciones a la GPU.

En CPU:
- **Perft paralelo** (hecho): el árbol se reparte a dos plies de la raíz en tareas independientes, cada hilo con su copia de la posición. Medido en la máquina de desarrollo: 38 M nodos/s con 1 hilo, ~207 M con 8 (×5,4).
- **Lazy SMP** para la búsqueda (Fase 4): varios hilos buscan la misma posición en paralelo compartiendo solo la tabla de transposición (sin bloqueos, con la técnica XOR de Hyatt para detectar entradas corruptas). Los hilos se desincronizan con profundidades u ordenaciones ligeramente distintas y se aprovechan de lo que los otros guardan en la tabla. Es el método de Stockfish y la mayoría de motores modernos porque es simple y escala razonablemente (con 4–8 hilos, típicamente +50–100 Elo respecto a 1 hilo). Alternativas más complejas como YBWC o ABDADA (citado en el README de la rama `python-old`) dan resultados similares con mucho más código.
- **Herramientas**: partidas de test (SPRT) en paralelo con fastchess/cutechess (varias partidas a la vez) y *tuning* paralelo (evaluar muchas posiciones a la vez es trivialmente paralelizable).
- Opción UCI `Threads`.

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
- **Validación** (hecho): `tools/perft_check.py` compara `divide` y la SAN de las jugadas del motor con `python-chess` en posiciones aleatorias.
- **Partidas**: el motor exporta las partidas en PGN (comando `play --pgn`, y las partidas de las fases siguientes) para verlas en el visor (`tools/game.py`) o analizarlas en Lichess o Chess.com.
- **Matches**: scripts para `fastchess`/`cutechess-cli` (SPRT entre versiones, partidas contra Stockfish limitado).
- **Tuning**: Texel tuning de los pesos sobre posiciones etiquetadas.
- **Lichess**: configuración de `lichess-bot` apuntando al ejecutable UCI del motor.
- **CLI**: lanzadores para jugar contra el motor en consola o en una GUI.

## 8. Despliegue

- Empaquetado: `./gradlew installDist` → script `engine/build/install/chessy/bin/chessy` (UCI). Opcional: GraalVM `native-image` para arrancar al instante.
- **Lichess**: cuenta nueva convertida a BOT (irreversible; la cuenta no puede volver a jugar como humano) + `lichess-bot`. Los bots solo juegan retos (no torneos ni emparejamiento por pools).
- Opcional: libro de aperturas (Polyglot) y tablas de finales Syzygy en fases avanzadas.

### Versiones

| Versión | Qué es | Dónde |
|---|---|---|
| **v0** | Primera implementación (2018–2019), objetos y tablero como mapa | Rama `v0` (PGN: `Site "Chessy (v0)"`) |
| **v1.0-dev** | Motor nuevo mientras se implementan las fases del PRD | `master` |
| **v1.0** | Motor nuevo al completar el PRD con la primera evaluación Constrictor completa | Tag `v1.0` |
| **v1.01, v1.02, …, v1.15, v1.26…** | Mejoras del motor y de las evaluaciones, una versión por cambio aceptado (SPRT) | Tags `v1.01`, `v1.02`… |
| **v1.07.6** | Correcciones (*hotfix*) sobre una versión publicada | Tag `v1.07.6` |

- La versión se define en un solo sitio, `engine/gradle.properties`, y el motor la usa en `id name` de UCI, en la CLI y en el `Site` de los PGN que exporta (`Chessy (v1.0-dev)`).
- Cada versión publicada lleva un **tag anotado** de git (`git tag -a v1.01 -m "..."`) con un resumen de los cambios y, cuando aplique, el resultado del SPRT frente a la versión anterior.
- `v0` es una rama y no un tag (un tag con el mismo nombre que una rama sería ambiguo para git).

## 9. Testing y medición

1. **Corrección**:
   - reglas (hecho): perft de las posiciones de referencia de chessprogramming.org (inicial, Kiwipete, posiciones 3–6, también en paralelo), consistencia de Zobrist (incremental = recalculada), ida y vuelta de FEN, enroques, captura al paso, promociones, jaque y mate, tablas (ahogado, material insuficiente, 50 movimientos, triple repetición), SAN (escritura y lectura) y PGN (exportación, importación y las partidas de `tools/examples`); validación cruzada con `python-chess` en posiciones aleatorias (`tools/perft_check.py --san`);
   - búsqueda (Fase 3, ver sus tests);
   - simetría de la evaluación (Fase 5).
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
- Rama `kotlin` → `v0` (antes `kotlin-old`); rama `python` → `python-old`, y su contenido → `master` en `tools/`.
- PRD.
- Arreglos en `v0` para que sea jugable en consola (§12).

### Fase 1 — Núcleo de reglas (core) ✅
- Bitboards, casillas, piezas, tablas de ataques (incluidas deslizantes con rayos).
- Codificación de movimientos, listas preasignadas.
- Posición con make/unmake, enroque, al paso, promociones, contador de 50 movimientos.
- Zobrist incremental.
- FEN (lectura/escritura), notación UCI de movimientos (`e2e4`, `e7e8q`).
- Perft/divide + CLI (`perft`, `divide`, `d`).
- **Aceptación**: perft correcto en las 6 posiciones de referencia (tests), Zobrist consistente, FEN ida y vuelta, rendimiento de perft medido.
- **Resultado**: perft correcto en las 6 posiciones hasta profundidad 5–6 (`./gradlew test -Pslow`) y en 300 posiciones aleatorias contra python-chess (`tools/perft_check.py --san`). Unos 22–38 M nodos/s en un hilo (objetivo: ≥ 5 M) y ~207 M con 8 hilos (perft paralelo).
- Añadido en esta fase (adelantado de la Fase 2): SAN y UCI, `Game` con todas las tablas (ahogado, material insuficiente, 50 movimientos, triple repetición), PGN (exportar e importar, probado con las partidas de `tools/examples`), comando `play` (partida entre humanos en consola, exportable a PGN) y `moves`.

### Fase 2 — Protocolo y juego básico ✅
- Bucle UCI completo (§5.4) con búsqueda en hilo aparte y `stop`.
- ~~Detección de tablas~~ (hecho en la Fase 1).
- Jugador provisional (aleatorio o material a 1 ply) para validar el protocolo.
- Tools: script para jugar contra el motor y enganche con el visor; prueba local de `lichess-bot`.
- **Aceptación**: 1000 partidas en fastchess sin jugadas ilegales, *timeouts* ni errores de protocolo.
- **Resultado**:
  - UCI completo (`uci`, `isready`, `ucinewgame`, `position`, `go` con todos sus parámetros incluidos `searchmoves`, `infinite` y `ponder`, `stop`, `ponderhit`, `setoption`, `quit`) con la búsqueda en otro hilo; opción `Move Overhead`; comandos de depuración `d`, `moves`, `perft` y `go perft`. Es el modo por defecto del ejecutable.
  - Interfaz `Search` (límites, `info`, puntuaciones de mate) y jugador provisional `GreedySearch` (1 ply, material, mate en 1, evita el ahogado si gana).
  - `play --chessy <color>`: partida contra Chessy en consola, exportable a PGN.
  - `tools/match.py` en lugar de fastchess (no instalado): partidas entre motores UCI con `python-chess`, que valida cada jugada y el protocolo, controla el reloj y guarda los PGN. 1000 partidas Chessy contra Chessy a 10+0,1 sin errores (102.383 jugadas, 2,3 ms de media) y 10 partidas contra Stockfish (`UCI_Elo` 1320) sin errores.
  - `tools/lichess`: configuración y guía de lichess-bot. Pendiente de probar en Lichess con una cuenta BOT (requiere crear la cuenta y un token).

### Fase 3 — Búsqueda base + evaluación base
- Interfaz `Evaluator` + `MaterialEvaluator` (material + PST cónicas).
- Negamax alfa-beta, iterative deepening, quiescence, MVV-LVA, mates con distancia, gestión de tiempo.
- `bench`.
- Tests de búsqueda (unitarios, con límite de profundidad o nodos para que sean deterministas):
  - mate en 1 encontrado a **cualquier** profundidad (1, 2, 3, 4…: detecta errores de signo como el de la primera implementación) y con puntuación `MATE - 1`;
  - mates en 2 y 3, y preferencia por el mate más corto;
  - capturar una pieza colgada y no dejar piezas colgadas (dama atacada por un peón);
  - no caer en el horizonte (captura con recaptura que pierde material, gracias a la quiescencia);
  - evitar el ahogado cuando se gana, y buscar tablas (ahogado o repetición) cuando se pierde;
  - repetición y regla de los 50 movimientos dentro de la búsqueda;
  - la tabla de transposición no cambia el resultado (misma jugada y puntuación con y sin TT a profundidad fija);
  - la búsqueda deja la posición intacta (misma FEN y clave al terminar o al abortar por tiempo);
  - respeta el tiempo (`movetime`) y el límite de nodos.
- **Aceptación**: los tests anteriores pasan; resuelve un subconjunto de WAC; gana claramente al jugador de la Fase 2. Estimación ~1500–1800.

### Fase 4 — Búsqueda avanzada
- TT, PVS, aspiration windows, killers/history/countermove, null move, LMR, futility, extensión por jaque, SEE.
- Gestión del tiempo avanzada (§5.2) y **Lazy SMP** (§5.5).
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
- Opcionales: libro de aperturas, Syzygy, pondering, evaluadores alternativos (p. ej. NNUE, o el "valor × factor de control" del README de 2022) gracias a la interfaz.

## 11. Riesgos

| Riesgo | Mitigación |
|---|---|
| Una evaluación pesada (mapas de control) reduce la profundidad y cuesta más Elo del que aporta | Medir nps; calcular ataques una vez por evaluación y reutilizarlos en todos los términos; *lazy eval* (salir pronto si el material está muy fuera de la ventana); SPRT de cada término |
| Sobrevalorar el espacio → regalar material o ignorar táctica | Quiescence + SEE + amenazas; Texel tuning; suites tácticas como regresión |
| Errores sutiles de reglas | Perft exhaustivo + validación cruzada con python-chess |
| Pérdida de legibilidad por optimizar | Optimizar solo lo que muestre el profiler; API estable para cambiar implementaciones (p. ej. magic bitboards) |
| JVM: calentamiento y GC | Sin asignaciones en búsqueda; `bench` al iniciar opcional; GraalVM native-image |
| La cuenta BOT de Lichess es irreversible | Usar una cuenta nueva dedicada |

## 12. Legado: rama `v0`

Primera implementación (2018–2019): piezas como objetos, tablero como mapa/matriz, minimax alfa-beta. Se corrigieron los errores graves para dejarla jugable en consola:

1. Signo de la evaluación invertido según la paridad de la profundidad (a profundidad 2 evitaba el mate en 1) → negamax.
2. Profundidad cambiando en mitad de la búsqueda raíz → iterative deepening con profundidad uniforme por iteración.
3. Caché que guardaba cotas alfa/beta como valores exactos y copiaba la puntuación al rival → TT con tipo de cota.
4. `setCheck` modificaba la tabla del caballo (estado global) → tabla del rey según la fase de la partida.
5. Tablas puntuadas como −10000 → 0.
6. Ahogado detectado con movimientos pseudo-legales → estado calculado con movimientos legales y detección de ataques al rey.
7. Ordenación que generaba y evaluaba el tablero siguiente para cada jugada (y fugas de memoria por cachés que retenían el árbol) → MVV-LVA barato.

Después se completaron las reglas (enroque, captura al paso, subpromoción, material insuficiente con alfiles del mismo color), FEN, SAN, exportación a PGN (`--pgn`), búsqueda por tiempo sin límite de profundidad y tests (`mvn test`: perft de las posiciones de referencia, reglas, SAN, PGN y búsqueda). No tiene UCI: queda como referencia histórica, jugable en consola.

## 13. Glosario

- **Ply** (medio movimiento): una jugada de un solo bando. Una "jugada" en notación ajedrecística (1. e4 e5) son 2 plies. En la búsqueda, el *ply* de un nodo es su **distancia a la raíz**: cuántas jugadas se han hecho desde la posición actual para llegar a él. Sirve para indexar estructuras por nivel (listas de jugadas, *killers*) y para puntuar mates (`MATE - ply`: un mate más cercano vale más).
- **Profundidad** (*depth*): cuántos plies **quedan por buscar** desde un nodo antes de evaluarlo (o pasar a la quiescencia). En una búsqueda uniforme `depth + ply = profundidad de la iteración`, pero con **extensiones** (se suma profundidad en jaques) y **reducciones** (LMR, *null move*) dejan de coincidir: una línea forzada puede llegar a un ply mucho mayor que la profundidad nominal (eso es el `seldepth` de UCI) y una línea poco prometedora se busca con menos profundidad. Resumen: el *ply* mira hacia atrás (cuánto llevamos), la profundidad hacia delante (cuánto queda).
- **Nodo**: una posición visitada por la búsqueda. **nps**: nodos por segundo.
- **Perft**: número de posiciones alcanzables a una profundidad dada; prueba la generación de jugadas.
- **SAN**: notación algebraica estándar (`Nf3`). **UCI**: protocolo entre GUI y motor, y su notación de jugadas (`g1f3`).
- **SPRT**: test estadístico para decidir si un cambio gana Elo jugando el mínimo número de partidas necesario.

## 14. Referencias

- https://www.chessprogramming.org/Getting_Started
- https://www.chessprogramming.org/UCI
- https://www.chessprogramming.org/Bitboards · https://www.chessprogramming.org/Classical_Approach
- https://www.chessprogramming.org/Encoding_Moves · https://www.chessprogramming.org/Perft_Results
- https://www.chessprogramming.org/Space · https://www.chessprogramming.org/Mobility · https://www.chessprogramming.org/Square_Control
- https://www.chessprogramming.org/Static_Exchange_Evaluation · https://www.chessprogramming.org/MVV-LVA
- https://www.chessprogramming.org/Tapered_Eval · https://www.chessprogramming.org/Texel%27s_Tuning_Method
- https://www.chessprogramming.org/Lazy_SMP · https://www.chessprogramming.org/Time_Management · https://www.chessprogramming.org/Depth · https://www.chessprogramming.org/Ply
- https://hxim.github.io/Stockfish-Evaluation-Guide/ (términos clásicos de Stockfish: space, mobility, threats, restricted)
- https://chess.phd/learn/terms/space/
- https://www.chessworld.net/chessclubs/openingguide/chess-space-control.asp
- https://www.chess.com/article/view/space-and-the-attack
- https://github.com/lichess-bot-devs/lichess-bot · https://lichess.org/api#tag/Bot
- https://github.com/Disservin/fastchess · https://github.com/cutechess/cutechess
