## Change ID
<!-- openspec/changes/<change-id> -->

## Resumen
<!-- Qué hace este PR, en 2-3 líneas -->

## Checklist
- [ ] La propuesta fue aprobada por una persona antes de implementar (comentario con el hash)
- [ ] `openspec validate <change-id> --strict` pasa
- [ ] La spec del change refleja lo implementado
- [ ] Cada Scenario de la spec tiene al menos un test automatizado
- [ ] Si el change toca el modelo, incluye su migración Flyway
- [ ] Los errores nuevos tienen su `type` registrado en la spec
- [ ] Si cambió la API, la spec y el OpenAPI lo reflejan
- [ ] `/opsx:archive` ejecutado como último commit del branch, con los tests en verde
