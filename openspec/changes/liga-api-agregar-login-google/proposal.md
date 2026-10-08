# Proposal

## Change ID

`liga-api-agregar-login-google` — branch `change/liga-api-agregar-login-google`,
PR `[liga-api-agregar-login-google] ...`.

## Why

La API no tiene autenticación y todos los changes siguientes del roadmap la
necesitan: cada endpoint exige sesión y varias reglas dependen del rol. Este
change agrega el login con Google, la sesión con JWT propio, el admin por
configuración y la base transversal mínima (errores únicos, "todo endpoint exige
sesión", CORS y Swagger solo en desarrollo) para que el resto se pueda probar de
punta a punta.

## What Changes

- Login: `POST /api/v1/sesiones` recibe un ID token de Google, lo valida y
  devuelve un JWT propio.
- "Quién soy": `GET /api/v1/sesiones/actual` devuelve la identidad del token
  presentado.
- Todo endpoint exige un JWT propio válido salvo una lista corta y explícita de
  endpoints públicos: el login y, solo en el perfil de desarrollo, Swagger.
- El admin se identifica por el correo configurado (`LIGA_ADMIN_EMAIL`) y queda
  atado al `sub` de Google de su primer login. Al arrancar, un admin cuyo correo
  ya no coincide pasa a JUGADOR.
- Quien tiene una cuenta de Google válida pero no está registrado recibe un token
  de registro pendiente, que no sirve en los endpoints normales.
- Manejo único de errores como ProblemDetail, incluidos los 401/403 de seguridad,
  con el catálogo inicial de `type`.
- CORS con orígenes permitidos por configuración.
- springdoc apagado por defecto y prendido solo en el perfil de desarrollo (T10).
- Primera migración Flyway: tabla `usuarios`.
- Dependencias nuevas: Spring Security y su resource server OAuth2 (JOSE).
- `docs/roadmap.md`: se resuelven el pendiente 5.3 y la pregunta 1 de la
  sección 6.

## Capabilities

### New Capabilities
- `autenticacion`: login con token de Google, JWT propio, admin por
  configuración, acceso a los endpoints según el token, endpoints públicos,
  CORS, Swagger por perfil y catálogo de errores transversales (genéricos y de
  seguridad).

### Modified Capabilities
<!-- Ninguna: no hay specs existentes. -->

## Contratos afectados

Endpoints nuevos (el detalle de request y response está en `design.md`):

| Método | Ruta | Acceso |
|---|---|---|
| POST | `/api/v1/sesiones` | Público |
| GET | `/api/v1/sesiones/actual` | Token de usuario o de registro pendiente |
| GET | `/v3/api-docs/**`, `/swagger-ui.html`, `/swagger-ui/**` | Público solo en el perfil `dev`; fuera de dev exige token y no existe |

Contratos transversales nuevos:
- Header `Authorization: Bearer <jwt>` en todo endpoint no público.
- Cuerpo de error ProblemDetail con los `type` del catálogo de la spec
  `autenticacion`.
- Variables de entorno: `GOOGLE_CLIENT_ID`, `LIGA_ADMIN_EMAIL`, `LIGA_JWT_SECRET`
  y `LIGA_CORS_ORIGINS`.
- Esquema de base: tabla `usuarios` (V1).

## Decisiones confirmadas

Confirmadas explícitamente por una persona:

1. **Sesión:** JWT propio de acceso.
2. **Seguridad y tokens:**
   - Spring Security.
   - `NimbusJwtDecoder` para el ID token de Google y para el JWT propio, firmado
     con HS256 y `LIGA_JWT_SECRET`.
   - El rol se lee de la base en cada request.
   - Hay dos tipos de token, de usuario y de registro pendiente, y todo se
     deniega por defecto.
3. **Validación del token de Google:**
   - Firma RS256 contra el JWKS de Google.
   - `iss` de Google; `aud` igual a `GOOGLE_CLIENT_ID`.
   - `exp` e `iat` con tolerancia chica; `email_verified=true`.
   - Emisor y JWKS fijos, no configurables por entorno.
   - Un solo `type` para cualquier token de Google inválido.
4. **Identidad y admin:**
   - La identidad es el `sub` de Google.
   - Admin = `LIGA_ADMIN_EMAIL` + `sub` fijado en su primer login; si llega otro
     `sub` con ese correo, responde 409.
   - El correo se compara en minúsculas, sin quitar puntos.
   - Google es la fuente de verdad del correo y del nombre: se actualizan en
     cada login.
5. **Swagger (T10):**
   - springdoc apagado por defecto y prendido en `application-dev`.
   - Las rutas de Swagger son públicas solo bajo `@Profile("dev")`.
   - Un test verifica que fuera de dev no responden.
   - No hay login falso de desarrollo.
6. **Errores:**
   - Un único `@RestControllerAdvice`, también para los 401/403 de Spring
     Security.
   - Nunca se loguean tokens.
   - Los `type` genéricos se catalogan en la spec `autenticacion`.
7. **Duración de los tokens:** el JWT de usuario dura 60 minutos y el token de
   registro pendiente 15. No hay renovación: el frontend vuelve a autenticarse
   con Google.
8. **Token vencido:** `type` `/errores/token-vencido` para el JWT propio vencido.
9. **Tablas:**
   - El CHECK de rol de la V1 incluye los tres roles (ADMIN, JUGADOR, HINCHA).
   - Las tablas se nombran en plural, en español y en snake_case.
10. **Secreto del JWT:** la aplicación no arranca si `LIGA_JWT_SECRET` mide menos
    de 32 bytes.
11. **Aviso de degradación:** al degradar a un admin al arrancar, se escribe un
    aviso en el log.

## Supuestos pendientes de confirmar

De la exploración:
- **S1. Cuándo se reconcilia el admin.** Si cambia `LIGA_ADMIN_EMAIL`, al
  arrancar se degrada a JUGADOR al admin cuyo correo ya no coincide.
- **S2. Login sin registro.** Quien no está registrado recibe el token de
  registro pendiente, que en este change solo habilita
  `GET /api/v1/sesiones/actual`.
- **S3. Modelo mínimo.** El modelo de este change cubre solo lo que necesita el
  admin, sin estados de aprobación de registro.

Del diseño de este change:
- **S4. Forma de los endpoints.** Login en `POST /api/v1/sesiones` con
  respuesta 200, "quién soy" en `GET /api/v1/sesiones/actual`, y la forma de sus
  cuerpos (ver `design.md`).
- **S5. Rol en cada login.** El rol ADMIN también se recalcula en cada login:
  quien tiene el `sub` fijado y un correo distinto del configurado deja de ser
  admin. Esto incluye al admin que cambia su correo en Google.
- **S6. Correo ya usado.** Un correo que ya pertenece a un usuario llega con
  otro `sub` y no es el del admin: responde 409 `/errores/cuenta-en-conflicto`.
- **S7. Login con token viejo.** El login ignora el header `Authorization`, para
  que un token propio vencido no impida volver a loguearse.
- **S8. Catálogo de errores.** Además de los confirmados, el catálogo incluye
  `/errores/origen-no-permitido` (CORS) y `/errores/tipo-de-contenido-no-soportado`
  (415). `/errores/acceso-denegado` (403 por rol) se cataloga con el primer
  endpoint restringido por rol: en este change ningún usuario registrado tiene
  denegaciones.
- **S9. CORS vacío.** Si `LIGA_CORS_ORIGINS` está vacía, no se acepta ningún
  origen cruzado; la aplicación arranca igual.
- **S10. Medida del secreto.** Los 32 bytes de `LIGA_JWT_SECRET` se miden sobre
  el valor en UTF-8, sin decodificarlo.
- **S11. Claims del JWT propio.**
  - `iss` = `backend-fdlj`, claim `tipo` (`USUARIO` o `REGISTRO_PENDIENTE`).
  - El token de usuario lleva `sub` = UUID propio y no lleva el rol.
  - El token de registro pendiente lleva el `sub` de Google, el correo y el
    nombre.
- **S12. Perfil y entorno.**
  - El perfil de desarrollo se llama `dev`.
  - Front y API pueden estar en dominios distintos: hosting sin definir.
  - La tolerancia de reloj es de 60 segundos.
- **S13. Recuperación del admin.** `google_sub` es nulable solo para permitir la
  recuperación manual del admin; en cualquier otro caso se completa en el primer
  login.
- **S14. `nonce`.** No se usa `nonce` en el login con Google.

## Limitaciones aceptadas

- **Logout solo del cliente.** No hay endpoint de logout ni revocación: un JWT
  sigue valiendo hasta que vence (60 minutos como máximo).
- **Recuperación manual del acceso del admin.** Si el `sub` de la cuenta del
  admin cambia (por ejemplo, porque la cuenta de Google se recreó), el login
  responde 409. Se recupera a mano borrando el `sub` fijado en la base; el
  siguiente login con el correo configurado lo vuelve a fijar.

## Fuera de alcance

- Registro como JUGADOR o HINCHA, y la aprobación de registros (change 2). La
  aprobación es un supuesto pendiente del change 2: el roadmap no la lista como
  decisión confirmada.
- El caso de un correo de admin configurado que ya pertenecía a un HINCHA
  (change 2).
- Reglas de acceso por rol para usuarios registrados y el `type`
  `/errores/acceso-denegado` (primer endpoint restringido).
- Renovación de tokens (refresh), logout en el servidor, revocación y `nonce`.
- Límite de tasa del login, auditoría de accesos y métricas o logging más allá
  de lo que trae Spring Boot.
- Cómo guarda el token el frontend (memoria o localStorage). Es una decisión del
  frontend, con riesgo de XSS.
- La forma de obtener un ID token de Google para probar a mano en dev.

## Estrategia de testing

- Cada Scenario de la spec tiene al menos un test automatizado. El nombre del
  Scenario aparece en el test.
- Tests de integración con `@SpringBootTest` + MockMvc contra PostgreSQL real
  con Testcontainers, aplicando la migración V1 con Flyway y
  `ddl-auto=validate`.
- El decoder de Google se reemplaza en los tests por uno que valida con una
  clave RSA de prueba. Se le aplican los mismos validadores de producción
  (emisor, audiencia, tiempos, correo verificado y algoritmo), así los tests
  cubren la lógica real de validación. Los tokens de prueba se firman con esa
  clave.
- Para Swagger hay un test con el perfil `dev` y otro sin perfil.
- Arranque fallido por configuración:
  - Se prueba con contextos que deben fallar al levantarse
    (`ApplicationContextRunner`, o `SpringApplication` con propiedades
    inválidas).
  - Estos Scenarios no tienen método ni ruta HTTP porque la aplicación no llega
    a atender requests. Es una excepción explícita a la regla de que cada
    escenario indica método, ruta y código de estado.
- `./mvnw test` en verde antes de cada commit de tareas;
  `openspec validate liga-api-agregar-login-google --strict` antes del PR.

## Componentes transversales involucrados

- **Autenticación:** se implementa en este proyecto con Spring Security. No hay
  librería de plataforma.
- **Manejo de errores:** el `@RestControllerAdvice` único y el catálogo base de
  `type`.
- **CORS:** orígenes por configuración.
- **Documentación de la API:** springdoc solo en dev.
- **Persistencia:** la primera migración Flyway.
- **Logging:** el de Spring Boot. Se agregan un aviso al degradar al admin y los
  motivos de rechazo de tokens, sin incluir nunca el token.
- **Métricas:** ninguna nueva.

## Aprobadores requeridos

- Una persona responsable del repositorio (dueño: `julian-larrosa`) aprueba esta
  propuesta antes de implementar y aprueba el PR. Las aprobaciones las publica
  siempre una persona.

## Impact

- **`pom.xml`:**
  - Se agregan `spring-boot-starter-security`,
    `spring-boot-starter-security-oauth2-resource-server` y, para tests,
    `spring-boot-starter-security-test`.
- **Código nuevo:**
  - `src/main/java/com/fdlj/backend_fdlj/autenticacion/...`: controller,
    service, dto y config (seguridad, CORS, OpenAPI de dev).
  - `usuarios/...`: model y repository.
  - `comun/error/...`: manejo de errores transversal.
- **Configuración:**
  - `src/main/resources/application.properties`: springdoc apagado y
    propiedades de la liga.
  - `application-dev.properties` nuevo.
- **Migración:** `src/main/resources/db/migration/V1__crear_usuarios.sql`.
- **Tests existentes:** `BackendFdljApplicationTests` necesita las variables
  obligatorias para levantar el contexto.
- **Documentación:** `docs/roadmap.md` (5.3 y pregunta 1 de la sección 6).
