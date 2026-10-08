# Roadmap — backend-fdlj

Mapa del dominio y plan de changes de la API de la liga de partidos entre
amigos. Este documento no define campos, validaciones ni endpoints: eso se
decide al explorar cada change. Lo marcado como pendiente o pregunta abierta
**no** está decidido.

---

## 1. Entidades y relaciones

```
  Usuario (rol ADMIN | JUGADOR | HINCHA)      ADMIN: unico, por configuracion (su Gmail)
     |
     +--< Comentario >-------------------------------+
     |                                               |
     +--< AsistenciaHincha >  (solo HINCHA) ---------+
     |                                               |
     +--< VotoMvp (como votante, 1 por partido) >----+
     |        |                                      v
     |        | 3                               +---------+
     |        v                                 | Partido |  (fecha -> anio; estado)
     |   EleccionMvp (posicion 1, 2, 3)         +---------+
     |        |                                      | 1
     |        |                                      | 2
     |        |                                 +---------+
     |        |                                 | Equipo  |  (A / B)
     |        |                                 +---------+
     |        |                                      | 1
     |        |                                      | 5..6
     |  0..* (JUGADOR o ADMIN)              +---------------+  0..1   +----------+
     +------------------------------------->| Participacion |-------->| Invitado |
              |                             +---------------+         +----------+
              | votado (solo registrado)      ^           ^
              +-------------------------------+           |
                                         autor|           | (solo registrado)
                                         +-------+   +--------------+
                                         |  Gol  |   | Calificacion |
                                         +-------+   | (5 atributos)|
                                    (en contra o no) +--------------+

  Derivados (se calculan al consultar, no se guardan):
    Resultado del partido, MVP(s), Calificacion global, Radar de atributos,
    Estadisticas del jugador, Rankings
```

Entidades persistidas:

- **Usuario**: persona con cuenta Google, con rol ADMIN, JUGADOR o HINCHA. El
  ADMIN no se registra: es el Gmail configurado y, además de administrar,
  juega como cualquier JUGADOR.
- **Partido**: enfrentamiento entre dos equipos en una fecha, de la que sale el
  *año del partido*. Tiene el ciclo de vida de la sección 1.1.
- **Equipo**: uno de los dos lados de un partido (A o B); no existe fuera de él.
- **Participación**: un lugar en un equipo, ocupado por un Usuario que juega
  (JUGADOR o ADMIN) o por un Invitado. A ella se atan goles, calificaciones y
  elecciones de MVP.
- **Invitado**: persona sin cuenta que el admin agrega a un partido; existe solo
  dentro de ese partido.
- **Gol**: hecho por una Participación, en contra o no.
- **Calificación**: la que pone el admin a la Participación de un jugador
  registrado, con los cinco atributos oficiales juntos (Definición, Pase,
  Técnica, Mentalidad, Físico).
- **AsistenciaHincha**: un Usuario HINCHA presente en un Partido.
- **VotoMvp**: el voto de un Usuario en un Partido; tiene tres
  **EleccionMvp**, una por posición del top 3.
- **Comentario**: un Usuario comenta un Partido.

### 1.1 Ciclo de vida del partido

```
                  (admin confirma equipos)
 [programado] ------------------------------> [equipos confirmados] --(admin cierra)--> [cerrado]
     |        <------------------------------      |                                     |
     |         (admin vuelve atras, solo si        | equipos editables                   | alineacion congelada
     |          no hay goles cargados)             | goles editables                     | goles editables, sin limite
     |                                             | se puede enviar el mail             | se habilita calificar
     | equipos editables                                                                 | abre votacion MVP (3 dias)
     | sin goles, sin mail
```

---

## 2. Capabilities

Cada capability tiene su propia spec en `openspec/specs/<capability>/`.

| Capability             | Responsabilidad                                                                 |
|------------------------|---------------------------------------------------------------------------------|
| `autenticacion`        | Login con token de Google, sesión, admin por configuración, acceso por rol       |
| `usuarios`             | Registro eligiendo JUGADOR o HINCHA y consulta de usuarios                       |
| `partidos`             | Alta, edición, baja, consulta y ciclo de vida de partidos                        |
| `equipos`              | Armado de los dos equipos (manual y automático), confirmación e invitados        |
| `goles`                | Registro y corrección de goles; resultado derivado                               |
| `avisos`               | Mail a los jugadores con cuenta de un partido                                    |
| `calificaciones`       | Calificación por partido, calificación global y radar de atributos              |
| `asistencia-hinchas`   | Registro de qué hinchas fueron a cada partido                                    |
| `votacion-mvp`         | Votos top 3, ventana de 3 días y MVP(s) resultante(s)                            |
| `comentarios`          | Comentarios de usuarios registrados en partidos                                  |
| `estadisticas-jugador` | Datos del perfil del jugador: goles, partidos jugados, ganados y MVP             |
| `rankings`             | Rankings de jugadores y de asistencia de hinchas, por año e histórico            |

El "perfil" es una pantalla del frontend; la API entrega sus datos ya
calculados desde `estadisticas-jugador`.

---

## 3. Dependencias y orden de construcción

```
  autenticacion
        |
        v
    usuarios
        |
        v
    partidos -------------------+--------------------+
        |                       |                    |
        v                       v                    v
     equipos               comentarios       asistencia-hinchas
        |                                            |
        +----------+-----------+                     |
        |          |           |                     |
        v          v           v                     |
      goles     avisos   partidos (cierre)           |
        |                      |                     |
        |              +-------+-------+             |
        |              |               |             |
        |              v               v             |
        |        calificaciones   votacion-mvp       |
        |              |               |             |
        |              +--> equipos (armado automatico)
        |              |               |             |
        +--------------+---------------+             |
        |              |               |             |
        v              v               v             |
  estadisticas-jugador     rankings <----------------+
```

Orden sugerido:

1. `autenticacion` -> `usuarios` -> `partidos` -> `equipos` (manual, confirmación, invitados)
2. `goles`, cierre del partido
3. `calificaciones`, `votacion-mvp`
4. `comentarios`, `asistencia-hinchas`, `avisos` (en cualquier orden)
5. `equipos` (armado automático, requiere calificación global)
6. `estadisticas-jugador`, `rankings`

---

## 4. Changes

Uno por rebanada vertical (endpoint, persistencia y tests). Branch
`change/<change-id>`, PR `[<change-id>] ...`.

| #  | Change ID                                   | Capability             | Depende de |
|----|---------------------------------------------|------------------------|------------|
| 1  | liga-api-agregar-login-google               | autenticacion          | —          |
| 2  | liga-api-agregar-registro-usuario           | usuarios               | 1          |
| 3  | liga-api-agregar-consulta-usuarios          | usuarios               | 2          |
| 4  | liga-api-agregar-gestion-partidos           | partidos               | 1          |
| 5  | liga-api-agregar-armado-manual-equipos      | equipos                | 3, 4       |
| 6  | liga-api-agregar-invitados-partido          | equipos                | 5          |
| 7  | liga-api-agregar-registro-goles             | goles                  | 5, 6       |
| 8  | liga-api-agregar-cierre-partido             | partidos               | 5          |
| 9  | liga-api-agregar-aviso-mail-partido         | avisos                 | 5          |
| 10 | liga-api-agregar-calificacion-partido       | calificaciones         | 8          |
| 11 | liga-api-agregar-calificacion-global        | calificaciones         | 10         |
| 12 | liga-api-agregar-radar-atributos            | calificaciones         | 10         |
| 13 | liga-api-agregar-armado-automatico-equipos  | equipos                | 5, 11      |
| 14 | liga-api-agregar-voto-mvp                   | votacion-mvp           | 2, 8       |
| 15 | liga-api-agregar-resultado-mvp              | votacion-mvp           | 14         |
| 16 | liga-api-agregar-comentarios-partido        | comentarios            | 2, 4       |
| 17 | liga-api-agregar-asistencia-hinchas         | asistencia-hinchas     | 3, 4       |
| 18 | liga-api-agregar-perfil-jugador             | estadisticas-jugador   | 7, 15      |
| 19 | liga-api-agregar-ranking-victorias-puntos   | rankings               | 7, 8       |
| 20 | liga-api-agregar-ranking-calificacion       | rankings               | 11         |
| 21 | liga-api-agregar-ranking-asistencia-hinchas | rankings               | 17         |
| 22 | liga-api-agregar-ranking-goleadores         | rankings               | 7          |
| 23 | liga-api-agregar-ranking-mvp                | rankings               | 15         |

Notas:

- El change 1 incluye la base transversal mínima (manejo único de errores y
  "todo endpoint exige sesión") para poder probarse de punta a punta; no hay un
  change técnico aparte. También deja springdoc deshabilitado salvo en el
  perfil de desarrollo (T10: Swagger UI solo en desarrollo), y ahí se decide
  qué rutas de Swagger se permiten sin sesión en desarrollo.
- El change 5 incluye confirmar los equipos y volver a "programado". El change
  7 agrega las reglas que dependen de los goles: no se vuelve a "programado" con
  goles cargados y no se saca de un equipo a un jugador con goles.

---

## 5. Decisiones y pendientes

### 5.1 Decisiones de dominio confirmadas en la exploración

Usuarios y admin
- El admin es también un Usuario que juega, es calificado (incluso por sí
  mismo) y vota.
- El admin se crea en su primer login, sin registro. Si cambia el Gmail
  configurado, el admin anterior pasa a JUGADOR y conserva su historial.
- Los hinchas pueden votar al MVP y comentar.

Partidos, equipos y goles
- El ciclo de vida es el de la sección 1.1: el admin confirma equipos y el
  admin cierra el partido.
- Los equipos se identifican como A y B y son editables hasta el cierre; al
  cerrar, la alineación queda congelada.
- No se puede sacar de un equipo a un jugador con goles cargados: primero se
  corrigen los goles.
- Se puede volver de "equipos confirmados" a "programado" solo si no hay goles
  cargados.
- Los goles se cargan desde "equipos confirmados" y siguen editables después
  del cierre, sin límite de tiempo.
- Un gol en contra suma al equipo rival y no cuenta como gol del autor en
  perfil ni rankings.
- Un invitado que juega dos partidos son dos invitados distintos.
- El mail a los jugadores solo se puede enviar con los equipos confirmados.

Calificaciones
- Una calificación por jugador registrado y por partido, con los cinco
  atributos juntos; se habilita al cerrar el partido.
- Calificación global = promedio de los atributos x 10, con rango 10..100 (no
  se reescala).
- Ponderación por antigüedad con decaimiento exponencial por partido: el más
  reciente pesa 1, el anterior 0,9, el siguiente 0,81, y así.

Votación MVP
- Un voto por usuario y por partido, definitivo una vez emitido; el cliente
  pide confirmación antes de enviarlo.
- El voto es un top 3 obligatorio de tres jugadores distintos, sin incluirse a
  uno mismo; puntos 3, 2 y 1.
- Solo se puede elegir a jugadores registrados que jugaron el partido.
- Solo hay votación si el partido tiene al menos 4 jugadores registrados.
- La votación abre al cerrar el partido y dura 3 días desde el cierre.
- Es MVP quien suma más puntos; si hay empate en el máximo, todos los
  empatados son MVP.

Rankings y derivados
- Además de los rankings confirmados existen el de goleadores y el de MVP.
- Resultado, MVP, calificación global, estadísticas y rankings se calculan al
  consultarlos, sin tablas de agregados.

Organización del trabajo
- Las 12 capabilities y los changes de la sección 4.

### 5.2 Decisiones transversales confirmadas

- **Ids**: UUID para todos los recursos.
- **Errores**: ProblemDetail con un `type` como URI relativa estable por caso
  (por ejemplo `/errores/voto-duplicado`); el catálogo vive en la spec de cada
  capability.
- **Paginación**: solo en los listados de partidos y comentarios, con `page` y
  `size`; rankings y equipos sin paginar.
- **Auditoría**: fecha de creación y de última modificación en toda entidad, y
  autor de la modificación en lo que edita el admin. Sin historial de
  versiones.
- **CORS**: orígenes permitidos por configuración.
- **Zona horaria de la liga**: `America/Montevideo`. Define el año del partido
  y el cierre de la votación.
- **Fechas en la API**: ISO-8601.
- **Borrado**: físico para partidos; sin baja de usuarios por ahora.
- **Swagger UI**: solo en desarrollo.
- **PostgreSQL**: versión 18 fijada en `compose.yaml` y en los tests con
  Testcontainers. La base de producción no está definida y PostgreSQL 19
  todavía está en beta; revisar al definir producción.

### 5.3 Pendiente

- **Sesión**: cookie de sesión del servidor o token propio, y si se usa Spring
  Security como framework. Se decide en el change 1.

---

## 6. Preguntas abiertas por change

1. **login-google**: Mecanismo de sesión (5.3). ¿Qué puede hacer alguien
   autenticado en Google pero no registrado? ¿Hay logout?
2. **registro-usuario**: ¿Un JUGADOR puede pasar a HINCHA? ¿Qué datos se piden
   además del Gmail?
3. **consulta-usuarios**: ¿Quién puede listar usuarios y qué ve cada rol?
4. **gestion-partidos**: ¿Qué datos tiene un partido además de la fecha? ¿En
   qué estados se puede borrar?
5. **armado-manual-equipos**: ¿Se permite 5 contra 6 o ambos equipos deben
   tener el mismo tamaño?
6. **invitados-partido**: ¿Cuentan para el tamaño de 5 o 6? ¿Qué se guarda de
   un invitado?
7. **registro-goles**: sin preguntas de dominio abiertas.
8. **cierre-partido**: ¿Se puede cerrar sin goles (queda 0-0)? ¿Se puede
   reabrir un partido cerrado?
9. **aviso-mail-partido**: ¿Qué contiene el mail? ¿Qué proveedor SMTP? ¿Se
   registra el envío? ¿Se puede reenviar?
10. **calificacion-partido**: ¿Es obligatorio calificar a todos? ¿Se puede
    corregir y hasta cuándo?
11. **calificacion-global**: ¿Qué valor tiene un jugador sin calificaciones?
    ¿Cómo se calcula la vista por año que necesita su ranking?
12. **radar-atributos**: ¿Promedio simple o ponderado como la global?
13. **armado-automatico-equipos**: ¿Qué criterio de balanceo? ¿Cómo entran los
    invitados y los jugadores sin calificación?
14. **voto-mvp**: ¿Los votos son anónimos?
15. **resultado-mvp**: ¿Se ve durante la votación o solo al cerrar? ¿Un partido
    sin votos queda sin MVP?
16. **comentarios-partido**: ¿Se pueden editar o borrar los propios? ¿El admin
    modera? ¿Hay respuestas?
17. **asistencia-hinchas**: ¿En qué estados del partido se registra?
18. **perfil-jugador**: ¿Vista por año además de la histórica? ¿Incluye radar y
    calificación global?
19. **ranking-victorias-puntos**: ¿Criterio de desempate? ¿Mínimo de partidos
    para aparecer?
20. **ranking-calificacion**: ¿Mínimo de partidos calificados para aparecer?
21. **ranking-asistencia-hinchas**: ¿Criterio de desempate?
22. **ranking-goleadores**: ¿Criterio de desempate?
23. **ranking-mvp**: ¿Un MVP compartido cuenta igual que uno único? ¿Criterio
    de desempate?
