# Chessy — PRD (Product Requirements Document)

> Motor de ajedrez "constrictor": gana espacio, controla casillas y asfixia al rival limitando sus movimientos y posibilidades.

| | |
|---|---|
| Versión | v1.01 (ver §8, Versiones) |
| Estado | Fases 0 a 7 completadas, v1.01 con libro, Syzygy, Chess960 y niveles de fuerza · siguiente: el BOT en línea |
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
- Otras variantes (Chess960 sí está soportado desde v1.01).
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
│       ├── tune/    Texel tuning de los pesos de la evaluación
│       └── Main.kt  CLI (uci por defecto, perft, divide, eval, bench…)
└── tools/           Herramientas Python alrededor del motor
    ├── board.py, game.py, app/, utils/   Visor de FEN/PGN (PyQt5 + python-chess)
    ├── match.py, sprt.sh, snapshot.sh    Partidas entre motores y SPRT (fastchess)
    ├── epd.py, sts.py, tactics.py, suites/   Suites de test (táctica, WAC, STS)
    ├── analysis.py, style.py             Análisis de partidas y métricas de estilo
    ├── texel.py     Posiciones de entrenamiento para el Texel tuning
    └── lichess/     Configuración de lichess-bot
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
- **Extensión por jaque** (solo jaques seguros: la pieza que da jaque no puede ser capturada).
- **Incentivo de progreso**: la evaluación estática se reduce a medida que avanza el contador de 50 movimientos, para que el bando mejor avance en lugar de maniobrar indefinidamente.
- **SEE** (Static Exchange Evaluation): atacantes y defensores sobre una casilla, para ordenar y podar capturas.
- **Lazy SMP** (multihilo, §5.5) y *pondering* (pensar durante el turno del rival).
- Precaución con el **zugzwang**: el estilo constrictor busca precisamente posiciones en las que el rival se queda sin jugadas útiles, que es donde el *null move pruning* falla. Se usará *verification search* o se desactivará con poco material no-peón. Implementado en la Fase 5: sin piezas no hay jugada nula, y con 2 piezas o menos (o a profundidad ≥ 10) el corte se verifica buscando la misma posición sin jugada nula; tests con posiciones de zugzwang.

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

- La búsqueda solo conoce `Evaluator` (patrón *strategy*). La implementación se elige con la opción UCI `Style`, que define cuatro estilos (y niveles de dificultad): **Space** (por defecto, el evaluador "Constrictor" con pesos que conservan el estilo), **Full** (la misma evaluación con todos los pesos ajustados para la máxima fuerza), **Material** (material y tablas de casillas) y **Greedy** (búsqueda de profundidad fija que solo cuenta material, opción `Greedy Depth`). Cada evaluador tiene su propio *contempt*.
- Los **pesos** viven en una estructura de datos (`SpaceWeights`) que se lee y se escribe en ficheros de texto (`nombre = valor`). Los pesos por defecto son el recurso `chessy/eval/space.weights`, ajustado con *Texel tuning*. La opción UCI `Weights` (y `--weights`) permite probar otros ficheros sin recompilar. Las bonificaciones no pueden volverse penalizaciones al ajustarlas, ni al revés.
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
Opciones: `Style` (Space, Full, Material, Greedy), `Hash`, `Threads`, `Move Overhead`, `Ponder`, `Greedy Depth` y `Weights` (fichero de pesos de Space o Full). El *contempt* lo define cada evaluador.
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
  - búsqueda ≥ 500 k nps con evaluación de material (medido: ~2 M con la búsqueda de la Fase 4) y ≥ 150 k nps con la evaluación Constrictor completa.
- Determinismo: misma posición + mismos límites de nodos → misma jugada (para `bench` y regresiones).
- Tests automáticos (`./gradlew test`) rápidos (< 1 min); tests lentos marcados aparte.

## 7. Herramientas (tools/)

- **Visor** (ya existe): FEN y PGN con PyQt5 + `python-chess`.
- **Análisis de partidas**: recorrer un PGN preguntando al motor (UCI) la evaluación y su desglose, y graficar espacio, movilidad propia/rival y material a lo largo de la partida.
- **Validación** (hecho): `tools/perft_check.py` compara `divide` y la SAN de las jugadas del motor con `python-chess` en posiciones aleatorias.
- **Partidas**: el motor exporta las partidas en PGN (comando `play --pgn`, y las partidas de las fases siguientes) para verlas en el visor (`tools/game.py`) o analizarlas en Lichess o Chess.com.
- **Matches**: scripts para `fastchess`/`cutechess-cli` (SPRT entre versiones, partidas contra Stockfish limitado).
- **Tuning** (hecho): `tools/texel.py` extrae las posiciones tranquilas de partidas con su resultado y `chessy tune` ajusta los pesos (Texel tuning).
- **Lichess**: configuración de `lichess-bot` apuntando al ejecutable UCI del motor.
- **CLI**: lanzadores para jugar contra el motor en consola o en una GUI.

## 8. Despliegue

- Empaquetado: `./gradlew installDist` → script `engine/build/install/chessy/bin/chessy` (UCI), y `./gradlew distZip` → `engine/build/distributions/chessy-<versión>.zip` con los lanzadores `bin/chessy` (macOS, Linux) y `bin/chessy.bat` (Windows) y las librerías (necesita Java 17+). Opcional: GraalVM `native-image` para arrancar al instante y no depender de Java.
- **Lichess**: cuenta nueva convertida a BOT (irreversible; la cuenta no puede volver a jugar como humano) + `lichess-bot`, arrancado con `tools/lichess/run.sh` (token en la variable de entorno `LICHESS_BOT_TOKEN`). Los bots solo juegan retos (no torneos ni emparejamiento por pools). El bot se ejecuta en la máquina que lo arranca (Lichess solo transmite las jugadas): para tenerlo siempre en línea, imagen Docker con el motor y lichess-bot (`tools/lichess/Dockerfile`, `compose.yaml`; x86-64 y ARM64, 436 MB) para un servidor, con las tablas Syzygy como volumen opcional y los comandos `play`, `upgrade` y `test` (partida sin conexión).
- Libro de aperturas propio (Polyglot, `OwnBook`) y tablas de finales Syzygy (`SyzygyPath`), desde v1.01.

### Versiones

| Versión | Qué es | Dónde |
|---|---|---|
| **v0** | Primera implementación (2018–2019), objetos y tablero como mapa | Rama `v0` (PGN: `Site "Chessy (v0)"`) |
| **v1.0-dev** | Motor nuevo mientras se implementan las fases del PRD | `master` |
| **v1.0** | Motor nuevo al completar el PRD con la primera evaluación Constrictor completa | Tag `v1.0` |
| **v1.01, v1.02, …, v1.15, v1.26…** | Mejoras del motor y de las evaluaciones, una versión por cambio aceptado (SPRT) | Tags `v1.01`, `v1.02`… |
| **v1.07.6** | Correcciones (*hotfix*) sobre una versión publicada | Tag `v1.07.6` |

- La versión se define en un solo sitio, `engine/gradle.properties`, y el motor la usa en `id name` de UCI, en la CLI y en el `Site` de los PGN que exporta (`Chessy (v1.0)`).
- Cada versión publicada lleva un **tag anotado** de git (`git tag -a v1.01 -m "..."`) con un resumen de los cambios y, cuando aplique, el resultado del SPRT frente a la versión anterior.
- `v0` es una rama y no un tag (un tag con el mismo nombre que una rama sería ambiguo para git).

## 9. Testing y medición

1. **Corrección**:
   - reglas (hecho): perft de las posiciones de referencia de chessprogramming.org (inicial, Kiwipete, posiciones 3–6, también en paralelo), consistencia de Zobrist (incremental = recalculada), ida y vuelta de FEN, enroques, captura al paso, promociones, jaque y mate, tablas (ahogado, material insuficiente, 50 movimientos, triple repetición), SAN (escritura y lectura) y PGN (exportación, importación y las partidas de `tools/examples`); validación cruzada con `python-chess` en posiciones aleatorias (`tools/perft_check.py --san`);
   - búsqueda (Fase 3, ver sus tests);
   - simetría de la evaluación (Fase 5).
2. **Táctica**: suites EPD con tiempo fijo (`tools/epd.py`): la suite propia `tools/suites/tactics.epd`, extraída con `tools/tactics.py` de partidas reales usando Stockfish como oráculo (posiciones con una sola jugada buena, al menos 200 cp mejor que la segunda), y suites clásicas como Win At Chess (WAC) cuando se añadan al repositorio; mates en N.
3. **Posicional**: Strategic Test Suite (STS, `tools/suites/sts.epd` y `tools/sts.py`), con especial atención a las categorías de control del centro, espacio y juego de peones.
4. **Estilo**: métricas por partida: movilidad media del rival, diferencial de espacio, número de piezas rivales atrapadas, % de partidas ganadas con material inferior.
5. **Fuerza**:
   - SPRT con fastchess para aceptar cada cambio: `elo0=0, elo1=5` (o similar) a control de tiempo corto, con el libro de aperturas equilibradas `tools/openings/balanced.epd` (generado con `tools/openings.py`) y cada apertura jugada con ambos colores (`-repeat`).
   - Calibración contra Stockfish con `UCI_LimitStrength`/`UCI_Elo` y contra motores de la lista CCRL de nivel conocido.
   - Rating del BOT en Lichess.

### CCRL

La escala `UCI_Elo` de Stockfish está ajustada con partidas contra versiones del motor Stash con rating CCRL Blitz, a 120+1 (`search.h`: de 1320 a 3190; las anclas de Stash están a ±100 Elo de CCRL y la más alta es 2744). Por eso la calibración contra Stockfish limitado da una estimación aproximada del rating en **CCRL Blitz**. A 2+1 Chessy rinde unos 100 Elo más que a 10+0,1 contra Stockfish 2800 (Space 61 %, Full 55 %, 40 partidas cada uno), así que la estimación actual es de **~2800–2850 en CCRL Blitz** (puesto ~295–310 de ~890) y ~2770–2820 en CCRL 40/2 FRC si jugara Chess960 (puesto ~105–110 de 151). Detalles en el README (*Versions and styles*).

Listas y condiciones (máquina de referencia Intel i7-4770K, con el tiempo escalado según el `bench` de Stockfish 10 en la máquina del probador; *pondering* desactivado; sin aprendizaje; tablas de finales de hasta 6 piezas):

| Lista | Control de tiempo | Hash | Aperturas | Motores |
|---|---|---|---|---|
| [CCRL Blitz](https://computerchess.org.uk/404/) | 2+1 | 128 o 256 MB | Libro genérico, como mucho 12 jugadas por bando | ~890 |
| [CCRL 40/2 FRC](https://computerchess.org.uk/404FRC/) | 40 jugadas en 2 min | 256 MB | Posiciones de Chess960 | 151 |
| [CCRL 40/15](https://computerchess.org.uk/4040/) | 40 jugadas en 15 min | 256 o 512 MB | Libro genérico, como mucho 12 jugadas por bando | |

Para entrar en las listas en el futuro:

1. Publicar una versión etiquetada (p. ej. `v1.0`) con un ejecutable fácil de usar en Windows (los probadores usan sobre todo Windows): un `.jar` con su lanzador `.bat`, o mejor un binario nativo con GraalVM `native-image`, sin depender de una JVM instalada.
2. UCI completo y robusto en partidas largas (sin pérdidas por tiempo con `movestogo` y controles repetidos como 40/2), opción `Hash` hasta 256–512 MB y opción `SyzygyPath` para las tablas de finales (no obligatoria, pero los demás motores las usan).
3. Para CCRL 40/2 FRC, soporte de **Chess960**: enroques desde cualquier columna, FEN con columnas de enroque (X-FEN/Shredder-FEN) y la opción `UCI_Chess960`.
4. Comprobación propia antes de enviarlo: torneo *gauntlet* de unas 1000 partidas a 2+1 (escalado a la máquina con el `bench` de Stockfish 10) contra 4–6 motores con rating CCRL Blitz conocido cerca de la estimación (por ejemplo Frenzee 3.5.19, Bison 9.11, Octochess, Tornado 8.0), con el libro de CCRL y BayesElo para calcular el rating.
5. Anunciar el motor en el foro de CCRL ([talkchess](https://talkchess.com)): los probadores eligen qué motores prueban y publican sus resultados.

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

### Fase 3 — Búsqueda base + evaluación base ✅
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
  - (Fase 4) la tabla de transposición no cambia el resultado (misma jugada y puntuación con y sin TT a profundidad fija);
  - la búsqueda deja la posición intacta (misma FEN y clave al terminar o al abortar por tiempo);
  - respeta el tiempo (`movetime`) y el límite de nodos.
- **Aceptación**: los tests anteriores pasan; resuelve un subconjunto de WAC; gana claramente al jugador de la Fase 2. Estimación ~1500–1800.
- **Resultado**:
  - `Evaluator` (con `explain` y el comando `eval`) y `MaterialEvaluator`: material, tablas de casillas de la *Simplified Evaluation Function* (con tablas de final para rey y peones) y pareja de alfiles, cónica por fase. Sin asignaciones en `evaluate`, simétrica (test con posiciones espejo).
  - `AlphaBetaSearch`: negamax alfa-beta, profundización iterativa por tiempo, quiescencia (capturas y promociones, todas las jugadas en jaque, *delta pruning*), ordenación (variante principal anterior, MVV-LVA, promociones), mates por distancia y *mate distance pruning*, tablas dentro de la búsqueda (repetición, 50 movimientos, material insuficiente), variante principal completa (tabla triangular), límites `depth`, `nodes`, `movetime`, reloj, `mate`, `infinite` y `searchmoves`.
  - `TimeManager`: límite suave (no empezar otra iteración tras el 60 %) y duro (abortar), con `Move Overhead`; una sola jugada legal se responde tras la primera iteración, y un mate demostrado detiene la profundización.
  - `GreedySearch` se mantiene como jugador de referencia, con profundidad configurable (por defecto 2: no deja piezas colgadas a una captura directa). Opciones UCI `Search`, `Greedy Depth` y `Evaluator`.
  - `bench`: 12 posiciones a profundidad 6, 11.132.361 nodos, ~5,3 M nodos/s en un hilo (objetivo: ≥ 500 k).
  - Tests de búsqueda con posiciones verificadas con Stockfish 19 (mate en 1 a profundidades 1–6, mates en 2 y 3, el mate más corto, horizonte, piezas colgadas, ahogado, repetición y 50 movimientos, límites de tiempo, nodos y `stop`, determinismo, variante principal legal, `go mate`).
  - Partidas con fastchess (10+0,1, 500 aperturas equilibradas, cada apertura con ambos colores), sin pérdidas por tiempo ni jugadas ilegales:

    | Rival | Resultado (+ / − / =) | Puntos | Diferencia de Elo |
    |---|---|---|---|
    | `GreedySearch` profundidad 2 | 100 / 0 / 0 | 100 % | — |
    | Stockfish 19, `UCI_Elo` 1500 | 92 / 6 / 2 | 93 % | +449 ± 186 |
    | Stockfish 19, `UCI_Elo` 1800 | 63 / 36 / 1 | 63,5 % | +96 ± 74 |

    Estimación: **~1900** en la escala `UCI_Elo` de Stockfish (calibrada con controles de tiempo más largos, así que es orientativa), por encima de lo previsto para esta fase.
  - Suite táctica propia (`tools/suites/tactics.epd`, 100 posiciones con una sola jugada buena según Stockfish, ver §9): **97/100** con 1 s por posición (Stockfish 19: 100/100 con 0,1 s). Son posiciones extraídas de partidas de motores todavía débiles, así que es una suite fácil; WAC o STS servirán para medir con más precisión.

### Fase 4 — Búsqueda avanzada ✅
- TT, PVS, aspiration windows, killers/history/countermove, null move, LMR, futility, extensión por jaque, SEE.
- Gestión del tiempo avanzada (§5.2) y **Lazy SMP** (§5.5).
- **Aceptación**: cada técnica aceptada por SPRT; estimación ≥ 2000 con la evaluación base.
- **Resultado**: cada técnica se probó con un SPRT (fastchess, 8+0,08, `elo0=0 elo1=10`, α = 0,05, β = 0,1, máximo 1500 partidas, libro `balanced.epd`) contra la versión anterior (`tools/sprt.sh`):

  | Técnica | Resultado | Elo | Partidas |
  |---|---|---|---|
  | Tabla de transposición (opción `Hash`, `hashfull`, XOR de Hyatt) | ✅ aceptada | +235 ± 52 | 190 |
  | *Killers* + historial (con gravedad y penalización de las jugadas que no cortan) | ✅ aceptada | +112 ± 34 | 318 |
  | PVS + ventanas de aspiración (y cortes de la TT solo fuera de la variante principal) | ✅ aceptada | +28 ± 16 | 1192 |
  | *Null move pruning* (R = 3 + depth/4, no sin piezas ni dos seguidos) | ✅ aceptada | +173 ± 44 | 226 |
  | LMR (tabla logarítmica, menos reducción en la variante principal) | ✅ aceptada | +147 ± 40 | 258 |
  | Extensión por jaque | ❌ rechazada (sin conclusión) | −4 ± 13 | 1500 |
  | Quitar la extensión por jaque (confirmación) | neutral | +7 ± 13 | 1500 |
  | SEE: ordenación (capturas perdedoras al final) y poda en quiescencia | ✅ aceptada (sin conclusión, LOS 96,9 %) | +12 ± 13 | 1500 |
  | *Reverse futility*, *futility* y *late move pruning* | ✅ aceptada | +191 ± 43 | 212 |
  | Gestión del tiempo por estabilidad de la jugada y caída de la puntuación | ❌ rechazada (H0) | −12 ± 14 | 1290 |
  | Lazy SMP: 4 hilos frente a 1 (opción `Threads`) | ✅ aceptada | +223 ± 42 | 196 |

  - Las pruebas de SEE detectaron dos errores en la implementación (una poda que solo vale para saber el signo del resultado, no su valor), corregidos y cubiertos por un test que compara SEE con una referencia que ejecuta las capturas. El primer SPRT de SEE (−4 ± 14) medía la versión con el error; se repitió con la versión corregida.
  - La extensión por jaque y la gestión del tiempo por estabilidad no se usan. Se pueden volver a probar con otros parámetros en la Fase 6 (ajuste).
  - Sin *countermove*: queda para la Fase 6.
  - Medidas finales de la versión resultante (1 hilo salvo indicación):
    - `bench` (12 posiciones a profundidad 12): 2.230.298 nodos, ~2 M nodos/s (la evaluación estática en cada nodo y la TT cuestan velocidad, pero reducen el árbol muchísimo: la profundidad 12 visita menos nodos que la profundidad 6 de la Fase 3).
    - **WAC** (`tools/suites/wac.epd`, 1 s por posición): **285/300** con 1 hilo y **293/300** con 4 hilos (Fase 3: 230/300; Stockfish 19 con 0,1 s: 251/300). Suite táctica propia: 99/100.
    - Partidas contra Stockfish 19 con fuerza limitada (fastchess, 10+0,1, 100 partidas por nivel, sin pérdidas por tiempo ni jugadas ilegales):

      | Rival | + / − / = | Puntos | Diferencia de Elo |
      |---|---|---|---|
      | `UCI_Elo` 2000 | 86 / 12 / 2 | 87 % | +330 ± 109 |
      | `UCI_Elo` 2200 | 77 / 21 / 2 | 78 % | +220 ± 82 |
      | `UCI_Elo` 2400 | 61 / 35 / 4 | 63 % | +92 ± 69 |

      Estimación: **~2450–2500** en la escala `UCI_Elo` de Stockfish con 1 hilo (orientativa: esa escala está calibrada con controles de tiempo más largos). El objetivo de ≥ 2000 se supera ya con la evaluación de material; la Fase 5 debe mantener esta fuerza mientras cambia el estilo de juego.

### Fase 5 — Evaluación Constrictor ✅
- Términos de §5.3 (prioridad alta primero), `explain`/`eval`, pesos en fichero, opción UCI `Evaluator`.
- Tools: análisis de partidas con gráficas de espacio/movilidad.
- **Aceptación**: SPRT positivo frente al `MaterialEvaluator` con la misma búsqueda; mejora en las categorías de STS relacionadas; métricas de estilo (§9.4) claramente distintas de la evaluación base.
- **Resultado**:
  - `SpaceEvaluator` ("Constrictor"), el estilo por defecto. Términos: material, tablas de casillas (al 50 % para las piezas: la movilidad hace el trabajo), movilidad segura (tablas con raíz cuadrada por tipo de pieza), piezas atrapadas, peor pieza, espacio (fórmula de Stockfish clásico, con peso que crece con las piezas y los peones bloqueados), territorio (casillas controladas en el campo rival y en la zona del rey), restricción (casillas que el rival no sujeta firmemente), puestos avanzados, torres en columnas abiertas y en 7ª, estructura de peones, peones pasados (con la distancia de los reyes en el final), sobreextensión y agujeros, amenazas (piezas atacadas por peones o piezas menores, piezas colgadas), seguridad del rey (unidades de ataque, jaques seguros, escudo de peones, columnas abiertas), pareja de alfiles y tempo. *Contempt* de 15 cp.
  - Mapas de ataques calculados una vez por evaluación y reutilizados; sin asignaciones en la búsqueda; ~930 k nodos/s (`bench`), por encima del objetivo de 150 k.
  - `eval` muestra cada término y el **valor dinámico de cada pieza** (material + casilla + movilidad + puesto avanzado + columna − atrapada).
  - Estilos con la opción UCI `Style` (y `--style`): **Space**, **Material** y **Greedy** (con `Greedy Depth`). Sustituye a las opciones `Search` y `Evaluator`.
  - *Null move pruning* con verificación contra el zugzwang (§5.2), tests de zugzwang y SPRT de no regresión: +3 ± 14 Elo (1400 partidas).
  - SPRT **Space frente a Material** (misma búsqueda, 8+0,08): ✅ **+183 ± 47 Elo** (230 partidas).
  - Calibración con 1 hilo a 10+0,1 contra Stockfish 19 limitado (100 partidas por nivel):

    | Rival | + / − / = | Puntos | Diferencia de Elo |
    |---|---|---|---|
    | `UCI_Elo` 2400 | 69 / 19 / 12 | 75 % | +191 ± 70 (5 victorias por tiempo de Stockfish) |
    | `UCI_Elo` 2600 | 52 / 35 / 13 | 58,5 % | +60 ± 63 |
    | `UCI_Elo` 2800 | 23 / 65 / 12 | 29 % | −156 ± 80 |

    Estimación: **~2650–2700** en la escala `UCI_Elo` de Stockfish (Material: ~2450–2500).
  - Táctica sin pérdidas: WAC 285/300 con 1 s (igual que Material), suite propia 100/100.
  - Métricas de estilo (`tools/style.py`) contra el mismo rival (Stockfish `UCI_Elo` 2400, 100 partidas cada estilo):

    | Métrica | Material | Space |
    |---|---|---|
    | Movilidad media del rival | 27,6 | **25,2** |
    | Espacio (diferencia) | 0,67 | **1,57** |
    | Territorio (diferencia) | −0,96 | **+2,44** |
    | Piezas rivales atrapadas (≤ 1 casilla segura) | 0,20 | **0,32** |
    | Piezas en la jugada 30 | 6,59 | **6,90** |

    Entre ellos (Space contra Material), la movilidad del rival de Space es 21,4 frente a 29,7, y el 22 % de sus victorias llegan tras ir por detrás en material (Material: 0 %).
  - `tools/analysis.py`: gráfica de los términos de la evaluación a lo largo de una partida.
  - STS (añadida en la Fase 6, 0,2 s por posición): Space **10.775/15.000** frente a 9.806 de Material, mejor en los 15 temas (socavar +151, columnas abiertas +161, casillas vacías +96, actividad del rey +85, control del centro +28…). Stockfish 19 con 0,1 s: 13.876.
  - Los pesos en fichero pasaron a la Fase 6, junto con el *Texel tuning*. Observación para la Fase 6: con el *contempt*, en posiciones cerradas a veces maniobra sin progresar durante muchas jugadas; conviene un incentivo de progreso (por ejemplo, reducir la evaluación cuando avanza el contador de 50 movimientos).

### Fase 6 — Ajuste y medición ✅
- Texel tuning de pesos, ajuste de parámetros de búsqueda, calibración contra Stockfish limitado / CCRL.
- **Aceptación**: ≥ 2000 Elo medido.
- **Resultado**:
  - **Pesos en fichero**: `SpaceWeights` se lee y se escribe como texto (`nombre = valor`, 92 pesos ajustables). Los pesos por defecto son el recurso `chessy/eval/space.weights`. Opción UCI `Weights` y `--weights` para probar otros ficheros, y comando `chessy weights` para ver los actuales.
  - **Texel tuning** (`chessy tune`, búsqueda local con pasos adaptativos y error cuadrático medio contra el resultado de la partida, `k` ajustado al principio). Datos: posiciones tranquilas (sin jaque, y la jugada de la partida no es captura, promoción ni jaque) de partidas propias a 2+0,02 desde 5000 aperturas equilibradas (`tools/texel.py`). Los pesos del estilo (espacio, territorio, restricción, porcentaje de movilidad), el *contempt* y el peón de medio juego (la unidad) no se ajustan. Las bonificaciones no pueden volverse penalizaciones ni al revés.
  - Cada cambio con SPRT (8+0,08, libro `balanced.epd`):

    | Cambio | Resultado |
    |---|---|
    | Incentivo de progreso (evaluación × (200 − contador de 50 movimientos) / 200) | −1 ± 16 (1122 partidas): neutro, se mantiene por el comportamiento |
    | *Countermove* | +6 ± 15 (1260): `bench` con un 17 % menos de nodos, se mantiene |
    | Texel 1: 817 k posiciones de 10.000 partidas | ✅ **+142 ± 43** (284) |
    | Texel 1 con el material fijo y cambios limitados | +76 ± 29 (458); la versión libre le gana por +48 ± 22 (732) |
    | Texel 2: 1,72 M posiciones (10.000 partidas más del motor ajustado) | ✅ **+20 ± 13** (1698) |
    | Extensión por jaque seguro (la pieza que da jaque no puede ser capturada) | +7,8 ± 9,2 (3000, LOS 95 %), se mantiene |
    | Signos restringidos y reajuste (un alfil atrapado había quedado como bonificación) | +0,8 ± 8,8 (3580): sin regresión |

  - El ajuste revaloriza las piezas frente a los peones en el medio juego (caballo 626, alfil 667, torre 871 y dama 2248 con el peón en 100; en el final 278, 313, 577 y 781), reduce la movilidad y aumenta amenazas, puestos avanzados y peones pasados. Con ello los términos del estilo pesan menos en proporción y **el estilo se diluyó**: contra Stockfish `UCI_Elo` 2400 la movilidad del rival subió de 25,2 a 26,3, el espacio bajó de 1,57 a 0,80, el territorio de 2,44 a 1,34, y dejó de ganar partidas yendo por detrás en material.
  - **Recuperación del estilo** (SPRT de 1000 partidas como máximo, métricas de estilo con 100 partidas contra Stockfish 2400 a 10+0,1, reutilizando las posiciones de los ajustes anteriores):
    1. Texel con todos los pesos, también los del estilo (`chessy tune --all true`, salvo el peón de medio juego y el *contempt*): +19,5 ± 17 frente a la versión anterior, 90 % contra Stockfish 2400. El ajuste baja el espacio (`spacePercent` 120 → 42) y sube el territorio (4 → 11) y sobre todo el control de la zona del rey (6 → 38): más territorio (2,92), pero menos espacio (0,54) y ninguna victoria con material de menos.
    2. Partiendo de esos pesos, subir a mano los del estilo. Variantes probadas:

       | Variante | Contra SF 2400 | Movilidad del rival | Espacio | Territorio | Piezas atrapadas | Victorias con material de menos |
       |---|---|---|---|---|---|---|
       | Space (Fase 5) | 75 % | **25,2** | **1,57** | 2,44 | 0,32 | 4 % |
       | Fase 6 (primer ajuste) | 78,5 % | 26,3 | 0,80 | 1,34 | 0,26 | 0 % |
       | Todos los pesos ajustados | 90 % | 25,7 | 0,54 | **2,92** | 0,29 | 0 % |
       | Espacio 120, movilidad 150 %, restricción (10, 5) | 80,5 % | 26,6 | 1,28 | 2,17 | **0,39** | 4 % |
       | Espacio 200, movilidad 180 %, restricción (15, 8), territorio 14 | 81 % | 26,9 | 1,60 | 1,68 | 0,33 | 2 % |
       | Solo espacio 120 | 83,5 % | 25,9 | 1,09 | 1,76 | 0,29 | 1 % |
       | ✅ **Espacio 90, movilidad 135 %, restricción (8, 3)** | **86 %** | 25,5 | 1,05 | 2,43 | 0,32 | **5 %** |

       La elegida iguala a la Fase 5 en movilidad del rival, territorio, piezas atrapadas y victorias con material de menos, aunque gana menos espacio detrás de los peones. Fuerza: −6 ± 16 frente al primer ajuste (sin diferencia apreciable), −58 ± 31 frente a la versión con todos los pesos ajustados (el estilo cuesta fuerza en el enfrentamiento directo). La primera variante (espacio 120, movilidad 150 %), algo más débil, ganó a la Fase 5 por +164 ± 46.
  - Calibración final con 1 hilo a 10+0,1 contra Stockfish 19 limitado (100 partidas por nivel; Chessy no perdió ninguna por tiempo):

    | Rival | + / − / = | Puntos | Diferencia de Elo | Fase 6 (primer ajuste) | Fase 5 |
    |---|---|---|---|---|---|
    | `UCI_Elo` 2400 | 84 / 12 / 4 | 86 % | +315 | +225 | +191 |
    | `UCI_Elo` 2600 | 70 / 23 / 7 | 73,5 % | +177 ± 70 | +111 | +60 |
    | `UCI_Elo` 2800 | 34 / 49 / 17 | 42,5 % | −53 ± 60 | −108 | −156 |
    | `UCI_Elo` 3000 | 11 / 77 / 12 | 17 % | −275 ± 89 | −301 | — |

    Estimación: **~2750–2800** en la escala `UCI_Elo` de Stockfish (Fase 5: ~2650–2700). Contra SF 2400 hubo 4 victorias por tiempo de Stockfish.
  - Táctica: WAC **285/300** con 1 s (igual que la Fase 5).
  - STS por temas, con `tools/sts.py` (0,2 s por posición, puntos sobre 1000 por tema) y con `sts_rating.py` de [STS-Rating](https://github.com/fsmosca/STS-Rating) (`--getrating`, 69 ms por posición en esta máquina; % de puntos y estimación de Elo CCRL 40/4):

    | Tema | Material | Space (Fase 5) | Fase 6 (primer ajuste) | **Final** | Stockfish 19 |
    |---|---|---|---|---|---|
    | Socavar | 547 | **698** | 683 | 679 | 929 |
    | Columnas y diagonales abiertas | 551 | **712** | 665 | 681 | 912 |
    | Puestos avanzados de caballo | 613 | 645 | **725** | 700 | 923 |
    | Casillas vacías | 615 | **711** | 637 | 650 | 941 |
    | Alfil contra caballo | 709 | 782 | 816 | **843** | 962 |
    | Recaptura | 870 | **897** | **897** | 892 | 954 |
    | Ofrecer simplificación | 604 | 648 | 653 | **665** | 902 |
    | Avance de peones f, g, h | 575 | **651** | 634 | 645 | 902 |
    | Avance de peones a, b, c | 564 | **603** | 583 | 590 | 862 |
    | Simplificación | 778 | **827** | 821 | 826 | 967 |
    | Actividad del rey | 609 | **694** | 646 | 624 | 909 |
    | Control del centro | 663 | 691 | **790** | 774 | 923 |
    | Juego de peones en el centro | 702 | **755** | 735 | 752 | 907 |
    | 7ª fila | 747 | 778 | **840** | 827 | 941 |
    | Evitar cambios inútiles | 659 | 683 | 729 | **727** | 942 |
    | **Total (`sts.py`)** | 9.806 | 10.775 | 10.854 | **10.875** | 13.876 |
    | **% de puntos (`sts_rating.py`)** | 51,8 % | 56,7 % | 56,5 % | **57,0 %** | 91,5 % |
    | **Elo STS (`sts_rating.py`)** | 2065 | 2283 | 2274 | **2296** | 3832 |

    Fortalezas frente a la Fase 5: alfil contra caballo, control del centro, 7ª fila, puestos avanzados y evitar cambios inútiles (temas de piezas activas, que el ajuste valora más). Debilidades: actividad del rey, casillas vacías y columnas abiertas, y en general los temas de final y de maniobra lenta, donde Stockfish saca más ventaja. El Elo STS mide sobre todo la comprensión posicional en jugadas sueltas: Chessy juega mucho mejor de lo que indica (2296 frente a ~2750 en partidas).
  - `bench`: 1.617.877 nodos, ~780 k nodos/s.

### Estilo Full (después de la Fase 6)

- **Full**: la misma evaluación de Space con todos los pesos ajustados para la máxima fuerza (el ajuste completo, `full.weights`), como estilo para quien busque la fuerza y no el estilo. Opción `Style` (y `--style full`). La opción `Weights` vale para Space y Full.
- Medidas (10+0,1, 100 partidas por nivel): contra Stockfish 2600 +200, contra 2800 −96 y contra 3000 −308 (Space: +177, −53 y −275), así que contra Stockfish queda igual que Space dentro del margen. En el enfrentamiento directo gana a Space por **+37 ± 16** (1000 partidas a 8+0,08). WAC 283/300, STS 10.868 (`sts.py`) y 2258 (`sts_rating.py`).
- Estilo contra Stockfish 2600 y 2800: movilidad del rival 25,3 (Space 25,5), espacio 0,61 (Space 1,00), territorio 0,16 (Space 0,74), piezas atrapadas 0,19 (Space 0,21).
- A 2+1 (el control de CCRL Blitz) contra Stockfish 2800: Space 61 % (+80 ± 96) y Full 55 % (+35 ± 105), 40 partidas cada uno.
- Comparativa de todas las versiones y estilos (v0, Greedy, Material, Space, Full) en el README.
- Los *snapshots* de `tools/engines/` se reducen a uno por fase (`phase3` a `phase6`) más el *worktree* de `v0`.

### Fase 7 — Despliegue y extras ✅
- BOT en Lichess con `lichess-bot`, empaquetado.
- Opcionales: libro de aperturas, Syzygy, pondering, evaluadores alternativos (p. ej. NNUE, o el "valor × factor de control" del README de 2022) gracias a la interfaz.
- **Resultado**:
  - ***Pondering*** ([Pondering](https://chessprogramming.org/Pondering)): `go ponder` piensa durante el turno del rival y `ponderhit` ya no detiene la búsqueda: la convierte en una búsqueda normal cuyo reloj empieza en el `ponderhit` (`Ponderhit` viaja en `SearchLimits`, y `TimeManager` no aplica límites mientras se piensa en el turno del rival), así que todo lo pensado se aprovecha. Opción UCI `Ponder`. Comprobado con python-chess en partidas a 10+0,1: el 65 % de las jugadas fueron *ponderhit* y ninguna partida se perdió por tiempo.
  - **Empaquetado**: `./gradlew distZip` genera `chessy-1.0.zip` (1,9 MB) con los lanzadores para macOS, Linux y Windows. Sin `native-image` por ahora (requiere instalar GraalVM).
  - **BOT en Lichess**: `tools/lichess/config.yml` al día con el formato actual de lichess-bot (*pondering*, abandono con −10 peones durante 5 jugadas, tablas en finales igualados, sin libro ni jugadas en línea, retos de bullet a clásicas, casuales y puntuables, de humanos y bots) y `tools/lichess/run.sh`, que compila el motor, copia la configuración con la ruta del motor y arranca lichess-bot (o convierte la cuenta en BOT con `--upgrade`). Comprobado sin conexión con el simulador de lichess.org de los tests de lichess-bot (`run.sh --test`, lichess-bot 2026.10.11.1): la configuración es válida y Chessy juega partidas completas a 60+2 contra Stockfish 2000 con *pondering* (`go ponder`, `ponderhit` y `stop`) sin problemas de tiempo. Falta crear la cuenta del BOT y su token, que son del usuario.
  - **Tablero electrónico**: el ChessUp 2 (directamente o con chessconnect) juega contra el BOT desde la cuenta de Lichess del usuario; chessconnect no conecta con motores locales.
  - Versión **v1.0** (tag `v1.0`).
  - Quedaban como mejoras futuras el libro de aperturas, las tablas Syzygy y Chess960 (hechos en v1.01, abajo), `native-image` y evaluadores alternativos.

### Mejoras antes del BOT en línea (v1.01)

- **Referencias**: §14 con todas las páginas de chessprogramming.org que cita el código, por temas.
- **`chessy play`**: muestra el estilo (con la profundidad de Greedy, o el Elo elegido) y su Elo estimado al empezar, y los guarda en el PGN (`White`/`Black` con el estilo, `WhiteElo`/`BlackElo` con el Elo estimado y `-` para la persona). La evaluación se muestra en peones desde el punto de vista de las blancas, como en los tableros de análisis (`+2.40 (White is better)`, `mate in 3 for Black`): antes era la puntuación de la búsqueda desde el punto de vista de Chessy (`cp -240`) y se confundía. Comando `eval` durante la partida.
  - Ejemplo de la partida del usuario contra Greedy (profundidad 2): 12...Nxe4?? 13.Bxe4. Greedy no tiene búsqueda de quiescencia: tras cualquier jugada tranquila veía 13.Qxf6 (gana el caballo) sin ver la recaptura ...Qxf6, así que prefirió "salvar" el caballo con Nxe4, que pierde pieza por peón (−2,40 para Chessy) pero menos que el caballo que creía perder (efecto horizonte).
- **Niveles de fuerza** ([Playing Strength](https://chessprogramming.org/Playing_Strength); `UCI_LimitStrength`, `UCI_Elo` de 900 a 2800 en pasos de 100, `--elo`; clase `Strength`): Greedy a profundidad 2, 3 y 4 para los niveles bajos (~900, ~1100 y ~1120: sin quiescencia la profundidad 4 apenas mejora a la 3), el estilo elegido (Space por defecto) limitado a un número de nodos por jugada para los intermedios, conservando toda la búsqueda (quiescencia, extensiones, reducciones), y Full sin límite para 2800. Calibración con 50 partidas por punto contra Stockfish limitado: la fuerza crece **~135 Elo por cada duplicación de nodos** (64 nodos ~1310, 1024 ~1590, 4096 ~1990, 16384 ~2310, 65536 ~2510, 262144 ~2800).
- **Libro de aperturas** ([Opening Book](https://chessprogramming.org/Opening_Book), [PolyGlot](https://chessprogramming.org/PolyGlot); `OwnBook`, desactivado por defecto como piden las listas de rating; activado en `play` y en el BOT): libros Polyglot construidos con `tools/book.py` desde un repertorio de 63 líneas de aperturas de espacio (India de Rey, Benoni, Francesa, Caro-Kann y siciliana Alapin con e5, Gambito de Dama cerrado, Española cerrada, Pirc y Moderna; 12 jugadas por bando como máximo, como los libros de CCRL), comprobadas con Stockfish (se cortaron las líneas con jugadas que perdían más de 60 cp a profundidad 16). Un libro con todo y uno por apertura: la opción `Opening` fuerza una apertura (y el libro general cuando se agota), y `Book File` usa otro libro Polyglot. Las claves Polyglot se comprueban con los valores de la especificación.
- **Chess960** ([Chess960](https://chessprogramming.org/Chess960); `UCI_Chess960`, `--chess960`, PGN con `Variant`): el enroque se codifica como el rey tomando su torre (la representación de Stockfish), que permite enroques en los que el rey o la torre no se mueven o se intercambian; derechos de enroque con la casilla de cada torre; FEN con KQkq, X-FEN y Shredder-FEN; las 960 posiciones iniciales por número de Scharnagl. Validado contra python-chess con perft y SAN en 150 posiciones aleatorias de Chess960 (`perft_check.py --chess960`); el perft estándar no cambia.
- **Tablas Syzygy** ([Syzygy Bases](https://chessprogramming.org/Syzygy_Bases), [Endgame Tablebases](https://chessprogramming.org/Endgame_Tablebases); `SyzygyPath`, `--syzygy`; de 3 a 5 piezas, 939 MiB en `tools/syzygy`, que se usan por defecto en `play` y en UCI si están descargadas; el libro, en cambio, está activado por defecto en `play` pero no en UCI, donde `OwnBook` empieza desactivado como piden las listas de rating y lo activa la configuración del BOT): implementación propia del formato (Huffman canónico, codificación de los índices, mapas de DTZ) y del algoritmo de sondeo de Ronald de Man (redistribuible sin restricciones), validada contra python-chess en **20.000 posiciones aleatorias sin ninguna diferencia** (`tools/syzygy_check.py`: victorias, tablas, derrotas, *cursed wins*, *blessed losses*, peones y captura al paso). En la búsqueda se usa el WDL tras capturas y jugadas de peón (como Stockfish), y en la raíz se buscan solo las jugadas que mantienen el mejor resultado ordenadas por DTZ, de modo que los finales ganados se convierten antes de la regla de 50 movimientos (por ejemplo, rey, alfil y caballo contra rey contra Stockfish: mate en 24 jugadas); la puntuación de la raíz es la de las tablas (0 en finales tablas como dos caballos contra rey). SPRT a 8+0,08: −3 ± 30 (300 partidas), neutro: a ese ritmo pocas partidas llegan a finales de 5 piezas, su valor está en jugarlos perfectamente.
- **Fuerza de v1.01 con libro y tablas** contra Stockfish limitado (100 partidas por nivel a 10+0,1, desde la posición inicial para que juegue el libro): Space 86 / 79 / 42,5 / 20,5% contra 2400 / 2600 / 2800 / 3000 (v1.0: 86 / 73,5 / 42,5 / 17%), Full 84 / 71 / 42,5 / 25,5% (v1.0: 90 / 76 / 36,5 / 14,5%). Ajustando un Elo a los cuatro niveles: Space 2768 ± 40 (v1.0: 2746), Full 2756 ± 40 (v1.0: 2741), +22 y +15 dentro del margen de error: sin cambio medible. El libro solo cubre ~2,5 jugadas por partida porque Stockfish limitado varía sus respuestas, y las tablas se alcanzan en el 20–45% de las partidas; su valor está en la variedad y el estilo de las aperturas y en jugar perfectamente los finales de 5 piezas, más que en el Elo.
- **Evaluación**:
  - El término de espacio es el *Space* / *Space area* clásico de Stockfish ([guía](https://hxim.github.io/Stockfish-Evaluation-Guide/)): casillas seguras de las columnas c–f en las filas 2–4 propias (sin peones propios ni atacadas por peones rivales), contando dos veces las que están detrás de los propios peones (hasta tres casillas) si no las ataca ninguna pieza rival; peso = piezas − 3 + mín(peones bloqueados, 9); bonus × peso² / 16, solo en el medio juego y con suficiente material. Diferencias: el porcentaje del estilo (`spacePercent`), el umbral de material ajustado por el *tuning* y un conteo más simple de los peones bloqueados. Territorio y Restricción son términos propios de Chessy, más allá del espacio de Stockfish.
  - La evaluación es cónica (*tapered*): cada término tiene valor de medio juego y de final, interpolados con una fase continua de 0 a 24 según el material sin peones, así que no hay saltos entre fases. La única discontinuidad apreciable es el umbral de material del espacio (igual que en Stockfish): por debajo el término desaparece de golpe, aunque es pequeño (unos 10 cp, porque solo cuenta en el medio juego y la fase ya es media en el umbral; [Evaluation Discontinuity](https://chessprogramming.org/Evaluation_Discontinuity)). Se probó a desvanecerlo linealmente en el último cuarto del umbral: −20 ± 28 (300 partidas, sin demostrar que no empeore), probablemente porque los pesos se ajustaron con el umbral brusco; se mantiene el umbral, y queda para un próximo ajuste de pesos con la rampa.

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

Después se completaron las reglas (enroque, captura al paso, subpromoción, material insuficiente con alfiles del mismo color), FEN, SAN, exportación a PGN (`--pgn`), búsqueda por tiempo sin límite de profundidad y tests (`mvn test`: perft de las posiciones de referencia, reglas, SAN, PGN y búsqueda). No tiene UCI: queda como referencia histórica, jugable en consola. Con el adaptador `tools/v0_uci.py` (que la arranca desde la posición en cada jugada) se pudo medir: ~1500 en la escala de Stockfish (87,5 % contra 1320 y 25 % contra 1600, a 60+0,6), WAC 122/300 y STS 6.735/15.000. Gana el 97 % de las partidas a v1 Greedy.

## 13. Glosario

- **Ply** (medio movimiento): una jugada de un solo bando. Una "jugada" en notación ajedrecística (1. e4 e5) son 2 plies. En la búsqueda, el *ply* de un nodo es su **distancia a la raíz**: cuántas jugadas se han hecho desde la posición actual para llegar a él. Sirve para indexar estructuras por nivel (listas de jugadas, *killers*) y para puntuar mates (`MATE - ply`: un mate más cercano vale más).
- **Profundidad** (*depth*): cuántos plies **quedan por buscar** desde un nodo antes de evaluarlo (o pasar a la quiescencia). En una búsqueda uniforme `depth + ply = profundidad de la iteración`, pero con **extensiones** (se suma profundidad en jaques) y **reducciones** (LMR, *null move*) dejan de coincidir: una línea forzada puede llegar a un ply mucho mayor que la profundidad nominal (eso es el `seldepth` de UCI) y una línea poco prometedora se busca con menos profundidad. Resumen: el *ply* mira hacia atrás (cuánto llevamos), la profundidad hacia delante (cuánto queda).
- **Nodo**: una posición visitada por la búsqueda. **nps**: nodos por segundo.
- **Perft**: número de posiciones alcanzables a una profundidad dada; prueba la generación de jugadas.
- **SAN**: notación algebraica estándar (`Nf3`). **UCI**: protocolo entre GUI y motor, y su notación de jugadas (`g1f3`).
- **SPRT**: test estadístico para decidir si un cambio gana Elo jugando el mínimo número de partidas necesario.

## 14. Referencias

Páginas de [chessprogramming.org](https://chessprogramming.org/Main_Page) citadas en el código del motor y las herramientas, por temas:

- **Introducción**: [Getting Started](https://chessprogramming.org/Getting_Started) · [Depth](https://chessprogramming.org/Depth) · [Ply](https://chessprogramming.org/Ply)
- **Tablero y reglas**: [Bitboards](https://chessprogramming.org/Bitboards) · [Square Mapping Considerations](https://chessprogramming.org/Square_Mapping_Considerations) · [Classical Approach](https://chessprogramming.org/Classical_Approach) · [Encoding Moves](https://chessprogramming.org/Encoding_Moves) · [Move Generation](https://chessprogramming.org/Move_Generation) · [Pseudo-Legal Move](https://chessprogramming.org/Pseudo-Legal_Move) · [Make Move](https://chessprogramming.org/Make_Move) · [Unmake Move](https://chessprogramming.org/Unmake_Move) · [Zobrist Hashing](https://chessprogramming.org/Zobrist_Hashing) · [Perft](https://chessprogramming.org/Perft) · [Perft Results](https://chessprogramming.org/Perft_Results) · [Repetitions](https://chessprogramming.org/Repetitions) · [Fifty-move Rule](https://chessprogramming.org/Fifty-move_Rule) · [Chess960](https://chessprogramming.org/Chess960)
- **Notación**: [Forsyth-Edwards Notation](https://chessprogramming.org/Forsyth-Edwards_Notation) · [Algebraic Chess Notation](https://chessprogramming.org/Algebraic_Chess_Notation) · [Portable Game Notation](https://chessprogramming.org/Portable_Game_Notation) · [Extended Position Description](https://chessprogramming.org/Extended_Position_Description) · [UCI](https://chessprogramming.org/UCI)
- **Búsqueda**: [Alpha-Beta](https://chessprogramming.org/Alpha-Beta) · [Negamax](https://chessprogramming.org/Negamax) · [Iterative Deepening](https://chessprogramming.org/Iterative_Deepening) · [Principal Variation Search](https://chessprogramming.org/Principal_Variation_Search) · [Aspiration Windows](https://chessprogramming.org/Aspiration_Windows) · [Quiescence Search](https://chessprogramming.org/Quiescence_Search) · [Delta Pruning](https://chessprogramming.org/Delta_Pruning) · [Mate Distance Pruning](https://chessprogramming.org/Mate_Distance_Pruning) · [Null Move](https://chessprogramming.org/Null_Move) · [Null Move Pruning](https://chessprogramming.org/Null_Move_Pruning) · [Zugzwang](https://chessprogramming.org/Zugzwang) · [Late Move Reductions](https://chessprogramming.org/Late_Move_Reductions) · [Futility Pruning](https://chessprogramming.org/Futility_Pruning) · [Reverse Futility Pruning](https://chessprogramming.org/Reverse_Futility_Pruning) · [Check Extensions](https://chessprogramming.org/Check_Extensions) · [Static Exchange Evaluation](https://chessprogramming.org/Static_Exchange_Evaluation) · [Contempt Factor](https://chessprogramming.org/Contempt_Factor) · [Draw Evaluation](https://chessprogramming.org/Draw_Evaluation)
- **Ordenación de jugadas**: [Move Ordering](https://chessprogramming.org/Move_Ordering) · [MVV-LVA](https://chessprogramming.org/MVV-LVA) · [Killer Heuristic](https://chessprogramming.org/Killer_Heuristic) · [History Heuristic](https://chessprogramming.org/History_Heuristic) · [Countermove Heuristic](https://chessprogramming.org/Countermove_Heuristic)
- **Tabla de transposición y paralelismo**: [Transposition Table](https://chessprogramming.org/Transposition_Table) · [Shared Hash Table](https://chessprogramming.org/Shared_Hash_Table) · [Lazy SMP](https://chessprogramming.org/Lazy_SMP)
- **Tiempo y juego**: [Time Management](https://chessprogramming.org/Time_Management) · [Pondering](https://chessprogramming.org/Pondering) · [Playing Strength](https://chessprogramming.org/Playing_Strength) · [Opening Book](https://chessprogramming.org/Opening_Book) · [PolyGlot](https://chessprogramming.org/PolyGlot) · [Endgame Tablebases](https://chessprogramming.org/Endgame_Tablebases) · [Syzygy Bases](https://chessprogramming.org/Syzygy_Bases)
- **Evaluación**: [Evaluation](https://chessprogramming.org/Evaluation) · [Tapered Eval](https://chessprogramming.org/Tapered_Eval) · [Evaluation Discontinuity](https://chessprogramming.org/Evaluation_Discontinuity) · [Simplified Evaluation Function](https://chessprogramming.org/Simplified_Evaluation_Function) · [Piece-Square Tables](https://chessprogramming.org/Piece-Square_Tables) · [Space](https://chessprogramming.org/Space) · [Square Control](https://chessprogramming.org/Square_Control) · [Mobility](https://chessprogramming.org/Mobility) · [Trapped Pieces](https://chessprogramming.org/Trapped_Pieces) · [Outposts](https://chessprogramming.org/Outposts) · [Rook on Open File](https://chessprogramming.org/Rook_on_Open_File) · [Attack and Defend Maps](https://chessprogramming.org/Attack_and_Defend_Maps) · [King Safety](https://chessprogramming.org/King_Safety) · [Pawn Structure](https://chessprogramming.org/Pawn_Structure) · [Passed Pawn](https://chessprogramming.org/Passed_Pawn) · [Doubled Pawn](https://chessprogramming.org/Doubled_Pawn) · [Isolated Pawn](https://chessprogramming.org/Isolated_Pawn) · [Bishop Pair](https://chessprogramming.org/Bishop_Pair) · [Tempo](https://chessprogramming.org/Tempo)
- **Ajuste y pruebas**: [Texel's Tuning Method](https://chessprogramming.org/Texel%27s_Tuning_Method) · [Automated Tuning](https://chessprogramming.org/Automated_Tuning) · [Engine Testing](https://chessprogramming.org/Engine_Testing) · [Sequential Probability Ratio Test](https://chessprogramming.org/Sequential_Probability_Ratio_Test) · [Match Statistics](https://chessprogramming.org/Match_Statistics) · [Win at Chess](https://chessprogramming.org/Win_at_Chess) · [Strategic Test Suite](https://chessprogramming.org/Strategic_Test_Suite)

Otras referencias:

- https://hxim.github.io/Stockfish-Evaluation-Guide/ (términos clásicos de Stockfish: space, mobility, threats, restricted)
- https://chess.phd/learn/terms/space/
- https://www.chessworld.net/chessclubs/openingguide/chess-space-control.asp
- https://www.chess.com/article/view/space-and-the-attack
- https://github.com/lichess-bot-devs/lichess-bot · https://lichess.org/api#tag/Bot
- https://github.com/Disservin/fastchess · https://github.com/cutechess/cutechess
- https://github.com/fsmosca/STS-Rating · https://computerchess.org.uk/404/ (CCRL)
- https://syzygy-tables.info/
