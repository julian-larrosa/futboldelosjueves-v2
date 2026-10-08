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
  - Excepción: el rechazo de CORS sale con la respuesta por defecto de Spring
    (403, sin ProblemDetail).
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
12. **Reconciliación al arrancar:** si cambia `LIGA_ADMIN_EMAIL`, al arrancar se
    degrada a JUGADOR al admin cuyo correo ya no coincide.
13. **Login sin registro:** quien no está registrado recibe el token de registro
    pendiente, que en este change solo habilita `GET /api/v1/sesiones/actual`.
14. **Modelo mínimo:** el modelo de este change cubre solo lo que necesita el
    admin, sin estados de aprobación de registro.
15. **Forma de los endpoints:**
    - Login en `POST /api/v1/sesiones` con respuesta 200.
    - "Quién soy" en `GET /api/v1/sesiones/actual`.
    - Cuerpos según `design.md`.
    - La respuesta del login indica si la cuenta está registrada (`tipo` =
      `USUARIO`) o pendiente de registro (`tipo` = `REGISTRO_PENDIENTE`).
16. **Rol en cada login:**
    - El rol ADMIN se recalcula en cada login: quien tiene el `sub` fijado y un
      correo distinto del configurado deja de ser admin.
    - Si el admin cambia su correo en Google y se actualiza `LIGA_ADMIN_EMAIL`,
      con el mismo `sub` conserva su fila y vuelve a ser admin.
17. **Correo ya usado:** si un correo que ya pertenece a un usuario llega con
    otro `sub` y no es el del admin, responde 409 `/errores/cuenta-en-conflicto`.
18. **Login con token viejo:** el login ignora el header `Authorization`.
19. **Catálogo de errores:**
    - Incluye `/errores/tipo-de-contenido-no-soportado` (415).
    - El rechazo de CORS no tiene `type`: sale con la respuesta por defecto de
      Spring, como excepción documentada al formato de errores.
    - `/errores/acceso-denegado` se cataloga con el primer endpoint restringido
      por rol.
20. **CORS vacío:** si `LIGA_CORS_ORIGINS` está vacía, no se acepta ningún origen
    cruzado y la aplicación arranca igual.
21. **Medida del secreto:** los 32 bytes de `LIGA_JWT_SECRET` se miden sobre el
    valor en UTF-8, sin decodificarlo.
22. **Claims del JWT propio:**
    - `iss` = `backend-fdlj` y claim `tipo` (`USUARIO` o `REGISTRO_PENDIENTE`).
    - El token de usuario lleva `sub` = UUID propio y no lleva el rol.
    - El token de registro pendiente lleva el `sub` de Google, el correo y el
      nombre.
23. **Perfil y entorno:**
    - El perfil de desarrollo se llama `dev`.
    - Front y API pueden estar en dominios distintos (hosting sin definir).
    - Tolerancia de reloj de 60 segundos.
24. **Recuperación del admin:** `google_sub` es nulable solo para el admin, con
    `CHECK (google_sub IS NOT NULL OR rol = 'ADMIN')`.
25. **`nonce`:** no se usa en el login con Google.
26. **Columnas:** en español y snake_case (`correo`, `creado_en`,
    `modificado_en`), igual que las tablas.
27. **Reconciliación con un admin sin `sub`:** si al arrancar hay un ADMIN con
    `google_sub` nulo cuyo correo no coincide con `LIGA_ADMIN_EMAIL`, la
    aplicación no arranca. El error nombra el id del usuario y
    `LIGA_ADMIN_EMAIL`, y no se modifica ningún usuario.

## Limitaciones aceptadas

- **Logout solo del cliente.** No hay endpoint de logout ni revocación: un JWT
  sigue valiendo hasta que vence (60 minutos como máximo).
- **Recuperación manual del acceso del admin.** Si el `sub` de la cuenta del
  admin cambia (por ejemplo, porque la cuenta de Google se recreó), el login
  responde 409. Se recupera a mano borrando el `sub` fijado en la base; el
  siguiente login con el correo configurado lo vuelve a fijar.
- **ID token de Google robado.** Sin `nonce`, quien robe un ID token de Google
  válido puede loguearse con él hasta que venza (alrededor de 1 hora) y recibir
  un JWT propio. El backend no puede distinguirlo de un login legítimo.

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
- Arranque fallido y restricciones de la base:
  - El arranque fallido se prueba con contextos que deben fallar al levantarse
    (`ApplicationContextRunner`, o `SpringApplication` con propiedades
    inválidas).
  - Las restricciones de la base se prueban con tests de repositorio.
  - Estos Scenarios no tienen método ni ruta HTTP: la aplicación no llega a
    atender requests, o la regla vive en la base. Es una excepción explícita a
    la regla de que cada escenario indica método, ruta y código de estado.
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
