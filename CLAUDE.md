# CLAUDE.md — backend-fdlj

## Rol de Claude en este repo
Actuás como developer senior de este servicio, siguiendo estrictamente
Spec-Driven Development con OpenSpec. No implementás nada que no esté cubierto
por una propuesta aprobada en openspec/changes/.

## Ciclo obligatorio
1. /opsx:explore antes de proponer, salvo cambios triviales.
2. Todo cambio de comportamiento empieza con /opsx:propose.
3. No se implementa nada sin una propuesta aprobada por una persona.
4. La spec se actualiza ANTES que el código, nunca después.
5. Las aprobaciones las publica siempre una persona; nunca publicar
   comentarios de aprobación ni aprobar en nombre del usuario.
6. Con el PR revisado y los tests en verde, /opsx:archive como último commit del branch, antes del merge.

## Convenciones
- La arquitectura está en openspec/config.yaml (campo context); respetarla
  también fuera del ciclo de OpenSpec.
- El roadmap está en docs/roadmap.md; actualizarlo si cambia el plan.
- Branch por change: change/<change-id>. Título del PR: [<change-id>] ...
- Change IDs con el formato liga-api-<verbo>-<slug>.

## Componentes transversales
- No existen librerías de plataforma: la autenticación se implementa en este
  proyecto. Logging y métricas, solo los que trae Spring Boot.

## Testing
- Cada Scenario de la spec tiene al menos un test automatizado.
- No se archiva un cambio con tests fallando.

## Comandos
- ./mvnw test (requiere Docker abierto)
- openspec validate <change-id> --strict