# Flujo de trabajo de un change

Cada change del roadmap sigue este ciclo. La columna "Quién" indica quién
actúa en cada paso.

| # | Paso | Quién | Detalle |
| --- | --- | --- | --- |
| 1 | Partir de main actualizado | Persona | git switch main · git pull · git status limpio |
| 2 | Explorar | Persona + agente | /opsx:explore en plan mode, sin branch. Resultado: resumen de decisiones y pendientes |
| 3 | Cerrar lo que quedó al aire | Persona | Cada pendiente pasa a decisión o se descarta; lo demás queda como supuesto |
| 4 | Crear el branch | Persona o agente | change/<change-id>, después del explore y antes del propose |
| 5 | Proponer | Agente | /opsx:propose <change-id> y openspec validate <change-id> --strict |
| 6 | Leer los artefactos | Persona | Con las cinco preguntas de revisión (abajo) |
| 7 | Commit, push y PR en borrador | Agente, con confirmación | Mostrar el diff; título [<change-id>] ...; push solo tras confirmación explícita. El PR sigue en borrador hasta el paso 14: un borrador no se puede mergear |
| 8 | Correcciones | Agente | Un commit por ronda; mostrar el diff antes de cada push |
| 9 | Aprobar la propuesta | Persona | Comentario en el PR, desde la web, con el hash completo y sin supuestos abiertos. El agente nunca aprueba |
| 10 | Implementar | Agente | /opsx:apply <change-id>; detenerse al final de cada grupo; ./mvnw test en verde; un commit por grupo |
| 11 | Revisar cada grupo | Persona | Con la lista de abajo; push solo tras confirmación |
| 12 | Cierre | Agente | ./mvnw test completo y openspec validate <change-id> --strict |
| 13 | Archivar | Agente | /opsx:archive como último commit del branch |
| 14 | Revisión del código | Persona | PR a Ready for review con la plantilla completa; es el único momento en que se saca de borrador |
| 15 | Merge | Persona | Desde la web, con Create a merge commit |
| 16 | Limpieza | Persona | git switch main · git pull · git branch -d change/<change-id> · git fetch --prune |

## Reglas

- Si durante el apply el código y la spec no coinciden: primero se corrigen
  spec, design y tasks, y después el código. Se reabren las tareas afectadas.
- Lo que importa vive en un artefacto, no solo en el chat.
- El roadmap se actualiza dentro del PR del change que cambia el plan.
- Un solo PR por change: lleva la propuesta, la implementación y el archive,
  y se mergea una sola vez, al final.
- Los cambios sin comportamiento (docs, chores) no llevan change: branch
  docs/ o chore/ y PR con título [docs] o [chore].

## Cinco preguntas para revisar una propuesta

1. ¿Qué pasa con nulo, vacío, el límite y uno más, tipo incorrecto,
   duplicado, concurrencia y reintento?
2. ¿Quién decidió esto? Cada decisión confirmada debe rastrearse hasta algo
   que dijo una persona; si no, es un supuesto.
3. ¿Dicen lo mismo la spec y el design? Si se contradicen, gana la spec.
4. ¿Qué se vuelve difícil de cambiar? Contratos, type de error, formatos.
5. ¿Dónde va a vivir esto dentro de seis meses? Si importa, en un artefacto.

## Al cerrar cada grupo del apply

- Tests en verde, y los anteriores más los nuevos dan el total informado.
- Cada tarea marcada verifica sus escenarios, no solo compila.
- El código no hace más ni menos de lo que dice la spec.
- Las notas para tareas futuras están en tasks.md o design.md.
- Si la spec cambió, se reabrieron las tareas que verificaban la versión anterior.

## Si algo sale mal

- Antes de cualquier reset o borrado: git status y git log.
- Para descartar commits locales: git reset --hard origin/<branch>, nunca
  HEAD~1 a ciegas.
