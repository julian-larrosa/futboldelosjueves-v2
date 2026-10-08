# Design

## Context

Ver `proposal.md` (Why) y `specs/autenticacion/spec.md` para el
comportamiento esperado.

Estado actual del repo:
- **Base:** Spring Boot 4.1.1 con Java 21, webmvc, JPA, Flyway y validation.
- **springdoc:** 3.1.0, hoy activo en todos los perfiles.
- **Persistencia:** PostgreSQL 18 vía `compose.yaml` y Testcontainers
  (`TestcontainersConfiguration`).
- **Lo que no hay:** Spring Security, migraciones y paquetes de dominio.
  `application.properties` solo define el nombre y `open-in-view=false`.

Restricciones que dan forma al diseño:
- Un único `@RestControllerAdvice`.
- `ddl-auto=validate`.
- Services que devuelven records.
- Paquetes por funcionalidad y después por capa.
- Autenticación implementada en este proyecto.

## Goals / Non-Goals

**Goals:**
- Un único punto de verdad para validar tokens, con los mismos validadores en
  producción y en tests.
- Que todo error, incluidos los de los filtros de seguridad, salga por el mismo
  advice. La única excepción documentada es el rechazo de CORS (ver D8).
- Que la regla de Swagger sin sesión no exista en producción, ni siquiera
  deshabilitada.

**Non-Goals:**
- Abstraer la seguridad para otros proveedores de identidad.
- Reglas por rol para endpoints de negocio. Llegan con el primer endpoint
  restringido.

## Endpoints

### `POST /api/v1/sesiones` (público)

Request (`Content-Type: application/json`):
```json
{ "tokenGoogle": "eyJhbGciOiJSUzI1NiIs..." }
```

Response 200, token de usuario:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tipo": "USUARIO",
  "expiraEn": "2026-10-08T16:00:00Z",
  "usuario": {
    "id": "0b6f3c1e-...",
    "correo": "admin@gmail.com",
    "nombre": "Nombre en Google",
    "rol": "ADMIN"
  }
}
```

Response 200, registro pendiente (sin el campo `usuario`):
```json
{ "token": "eyJ...", "tipo": "REGISTRO_PENDIENTE", "expiraEn": "2026-10-08T15:15:00Z" }
```

Errores:

| status | type | motivo |
|---|---|---|
| 400 | `/errores/solicitud-invalida` | Falta el cuerpo o `tokenGoogle` está vacío, o el JSON está mal formado |
| 401 | `/errores/token-google-invalido` | El token de Google no pasa la validación |
| 403 | `/errores/correo-no-verificado` | `email_verified` no es `true` |
| 409 | `/errores/cuenta-en-conflicto` | El correo ya está atado a otro `sub` |
| 415 | `/errores/tipo-de-contenido-no-soportado` | `Content-Type` no es JSON |
| 500 | `/errores/error-interno` | Falla la base o no se pueden obtener las claves de Google |

El rechazo de CORS no figura en la tabla: sale con la respuesta por defecto de
Spring (403, sin ProblemDetail).

Se usa 200 y no 201: el JWT no es un recurso consultable, así que no hay
`Location` que devolver.

### `GET /api/v1/sesiones/actual` (token de usuario o de registro pendiente)

Response 200, token de usuario:
```json
{ "tipo": "USUARIO", "usuario": { "id": "...", "correo": "...", "nombre": "...", "rol": "JUGADOR" } }
```

Response 200, registro pendiente:
```json
{ "tipo": "REGISTRO_PENDIENTE", "correo": "alguien@gmail.com", "nombre": "Alguien" }
```

Errores:

| status | type | motivo |
|---|---|---|
| 401 | `/errores/no-autenticado` | Falta el token, o el token es inválido, o el usuario no existe |
| 401 | `/errores/token-vencido` | El JWT propio venció |
| 405 | `/errores/metodo-no-permitido` | Otro método HTTP |

### Formato de las fechas

`expiraEn` es un `Instant` en ISO-8601 UTC.

## Decisions

### D1. Paquetes

- **`com.fdlj.backend_fdlj.autenticacion`**, con:
  - `controller`: `SesionController`.
  - `service`: login, emisión de JWT, verificador de Google y reconciliación
    del admin.
  - `dto`: request y responses como records.
  - `config`: cadena de seguridad, decoders, CORS, propiedades y OpenAPI de dev.
- **`com.fdlj.backend_fdlj.usuarios`**, con `model` (`Usuario`, `Rol`) y
  `repository` (`UsuarioRepository`). El usuario es de la capability `usuarios`,
  que el change 2 amplía.
  - *Alternativa:* dejarlo en `autenticacion` y moverlo en el change 2. Se
    descarta para no mover clases enseguida.
- **`com.fdlj.backend_fdlj.comun.error`**, con:
  - `TipoError`: enum con `type`, status y título; refleja el catálogo de la
    spec.
  - `ApiException`.
  - `ManejadorErrores`: el único `@RestControllerAdvice`.

### D2. JWT propio: emisión y validación

- **Emisión:** `NimbusJwtEncoder` con `ImmutableSecret` sobre los bytes UTF-8 de
  `LIGA_JWT_SECRET`, header `alg=HS256`.
- **Claims:**
  - Todos llevan `iss=backend-fdlj`, `iat`, `exp` y `tipo`.
  - Token de usuario: `sub` = UUID del usuario. No lleva el rol.
  - Token de registro pendiente: `sub` = `sub` de Google, más `correo` y
    `nombre`.
- **Duración:** 60 minutos el de usuario y 15 el de registro pendiente. Las dos
  son constantes en el código, no configurables, porque están confirmadas y la
  spec las fija.
- **Validación:** `NimbusJwtDecoder.withSecretKey(...).macAlgorithm(HS256)`. El
  selector de claves acepta solo HS256, así que se rechazan RS256, `alg: none`
  y un ID token de Google.
- **Validadores**, armados a mano sin `JwtValidators.createDefault()`:
  - Emisor `backend-fdlj`.
  - Claim `tipo` con valor `USUARIO` o `REGISTRO_PENDIENTE`.
  - Un validador propio de `exp` con 60 segundos de tolerancia, que devuelve un
    `OAuth2Error` con código `token_vencido`. Así el entry point distingue
    `/errores/token-vencido` de `/errores/no-autenticado`.
- **Alternativa descartada:** inspeccionar el texto del error del
  `JwtTimestampValidator` por defecto. Es frágil ante cambios de versión.

### D3. Token de Google

- **Verificador:** `VerificadorTokenGoogle` recibe un bean `JwtDecoder` con
  qualifier `decoderGoogle`.
- **Decoder en producción:** `NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").jwsAlgorithm(RS256)`.
  La URI es una constante, no una propiedad.
- **Validadores:** salen de una única fábrica `ValidadoresTokenGoogle.crear(clientId, clock)`:
  - `iss` en el conjunto de los dos emisores de Google.
  - `aud` que contiene `GOOGLE_CLIENT_ID`.
  - `JwtTimestampValidator` con 60 s de tolerancia y el `Clock` de la app
    (cubre `exp` e `iat`).
  - `sub` presente.
- **`email_verified`:** lo chequea el service después de decodificar, porque
  tiene otro `type` (403).
- **Mapeo de excepciones:**
  - `BadJwtException` o `JwtValidationException` → `/errores/token-google-invalido`.
  - Cualquier otra `JwtException` (por ejemplo, no se pudo bajar el JWKS) →
    `/errores/error-interno`. Ese caso no es culpa del cliente.
- **Tests:** el bean `decoderGoogle` se reemplaza por
  `NimbusJwtDecoder.withPublicKey(claveDePrueba).signatureAlgorithm(RS256)`,
  con los **mismos** validadores de la fábrica.
- **Decoder explícito en la cadena:** hay dos beans `JwtDecoder` (propio y
  Google). El resource server recibe el decoder propio de forma explícita
  (`.jwt(j -> j.decoder(decoderJwtPropio))`) para que el autoconfigure de Boot
  no elija mal.
- **Alternativas descartadas:**
  - `GoogleIdTokenVerifier` (google-api-client): es otra dependencia para lo
    mismo.
  - El endpoint `tokeninfo`: Google lo indica solo para depurar.

### D4. Algoritmo del login (`LoginService`, transaccional)

1. Se valida el token de Google (D3) y se exige `email_verified=true`. Si no,
   responde 403.
2. Se normaliza el correo con `toLowerCase(Locale.ROOT)`, sin quitar puntos ni
   `+sufijo`.
3. Se busca un usuario por `google_sub`. Si existe:
   - Se actualizan el correo y el nombre.
   - Si el correo nuevo pertenece a otro usuario, responde 409.
   - Su rol pasa a `ADMIN` si el correo es igual a `LIGA_ADMIN_EMAIL`. Si era
     `ADMIN` y el correo ya no coincide, pasa a `JUGADOR`.
   - Así funciona el caso del admin que cambia su correo en Google y se
     actualiza `LIGA_ADMIN_EMAIL`:
     - Al arrancar, la reconciliación lo degrada, porque su fila todavía tiene
       el correo viejo.
     - En su login, con el mismo `sub`, se actualiza el correo y vuelve a
       `ADMIN` en la misma fila.
   - Se emite un token de usuario.
4. Si no existe y el correo es el del admin:
   - Hay un usuario con ese correo y `google_sub` nulo (recuperación manual): se
     fija el `sub` y el rol pasa a `ADMIN`.
   - Hay un usuario con ese correo y otro `sub`: responde 409.
   - No hay ninguno: se crea con rol `ADMIN`.
5. Si no existe y el correo pertenece a otro usuario, responde 409.
6. En cualquier otro caso se emite un token de registro pendiente, sin escribir
   en la base.

Concurrencia:
- Dos primeros logins simultáneos del admin chocan contra la restricción única.
- La `DataIntegrityViolationException` del login se mapea a 409. El cliente
  reintenta y el segundo intento entra por el paso 3.

### D5. Autenticación por request (rol desde la base)

Un `Converter<Jwt, AbstractAuthenticationToken>` propio:
- **`tipo=USUARIO`:** busca el usuario por id (lectura por PK).
  - Si no existe, lanza una `AuthenticationException` → 401
    `/errores/no-autenticado`.
  - Si existe, el principal es un record `UsuarioAutenticado` (id, correo,
    nombre, rol) con la autoridad `ROLE_<rol>`.
- **`tipo=REGISTRO_PENDIENTE`:** el principal es un record `RegistroPendiente`
  (sub de Google, correo, nombre) con la autoridad `REGISTRO_PENDIENTE`. No toca
  la base.

### D6. Cadena de seguridad

**Cadena principal:**
- `csrf` deshabilitado: no hay cookies ni sesión.
- `sessionManagement` en `STATELESS`.
- `formLogin`, `httpBasic` y `logout` deshabilitados.

**Reglas de autorización, en orden:**
1. `dispatcherTypeMatchers(ERROR, FORWARD).permitAll()`. Es para el despacho
   interno de errores. Un `GET /error` directo es un despacho `REQUEST` y exige
   token.
2. `POST /api/v1/sesiones` → `permitAll`.
3. `GET /api/v1/sesiones/actual` → cualquier rol o `REGISTRO_PENDIENTE`.
4. `anyRequest()` → `hasAnyRole(ADMIN, JUGADOR, HINCHA)`. Con un token de
   registro pendiente lanza `AccessDeniedException` → 403
   `/errores/registro-pendiente`, incluso en rutas que no existen.

**Bearer en el login:**
- Un `BearerTokenResolver` que envuelve al default y devuelve `null` para
  `POST /api/v1/sesiones`.
- Sin esto, un JWT vencido en el header haría fallar el login con 401 antes de
  llegar al controller.

**Cadena de dev:** `@Profile("dev")`, `@Order(1)` y `securityMatcher` sobre
`/v3/api-docs/**`, `/swagger-ui.html` y `/swagger-ui/**`, con `permitAll`.
- Fuera de dev la cadena no existe y springdoc está apagado, así que esas rutas
  caen en la regla 4: 401 sin token, 404 con token.
- *Alternativa descartada:* un `if (perfil dev)` dentro de la cadena principal.
  Deja la regla en el código de producción.

**OpenAPI en dev:** un bean `OpenAPI` con esquema `bearerAuth` (HTTP bearer,
JWT), para pegar un token en "Authorize". Va bajo `@Profile("dev")`.

### D7. Errores: un único advice

- **`ManejadorErrores`** extiende `ResponseEntityExceptionHandler` y
  sobrescribe la creación del cuerpo, para que ninguna excepción del framework
  quede con `about:blank`:

  El `title` de cada respuesta es el del catálogo de la spec, guardado en
  `TipoError`. Los 404, 405, 415 y 500 nunca usan el `type` ni el `title` de
  `solicitud-invalida`.

  | excepción | type |
  |---|---|
  | `NoResourceFoundException` | `recurso-no-encontrado` |
  | `HttpRequestMethodNotSupportedException` | `metodo-no-permitido` |
  | `HttpMediaTypeNotSupportedException` | `tipo-de-contenido-no-soportado` |
  | `HttpMessageNotReadableException`, `MethodArgumentNotValidException` | `solicitud-invalida` |
  | resto de los 4xx del framework | `solicitud-invalida` con su status original |

- **`@ExceptionHandler` propios:**
  - `ApiException` → su `TipoError`.
  - `AuthenticationException` → `token-vencido` si la causa tiene el código
    `token_vencido`; si no, `no-autenticado`.
  - `AccessDeniedException` → `registro-pendiente` si el principal es
    `RegistroPendiente`. Si no, se relanza: no es alcanzable en este change.
  - `Exception` → `error-interno`, logueando la traza sin datos del request.
- **Filtros de seguridad:** el `AuthenticationEntryPoint` y el
  `AccessDeniedHandler` delegan en el `HandlerExceptionResolver` (qualifier
  `handlerExceptionResolver`) con `handler=null`. Así los 401/403 de los filtros
  llegan al mismo advice.
- **Logs:** la causa de un rechazo de token se loguea en `DEBUG`, con el motivo
  y nunca el token. Las respuestas 401 llevan `WWW-Authenticate: Bearer`.
- **CORS:** no pasa por el advice. Ver D8.

### D8. CORS

- **Configuración:**
  - Orígenes desde `liga.cors.origenes` (`LIGA_CORS_ORIGINS`, separados por
    coma, con espacios recortados); se comparan exactos.
  - Métodos: GET, POST, PUT, PATCH y DELETE.
  - Headers permitidos: `Authorization` y `Content-Type`; se expone `Location`.
  - `allowCredentials=false` y `maxAge` de 1 hora.
- **Lista vacía:** no se registran orígenes, así que todo request con `Origin`
  ajeno se rechaza.
- **Dónde vive:** en la cadena de Spring Security
  (`http.cors(c -> c.configurationSource(...))`), no solo en MVC. Así el
  preflight se resuelve antes de exigir token.
- **Rechazo:** lo resuelve el `DefaultCorsProcessor` de Spring: 403 con su
  cuerpo por defecto y sin `Access-Control-Allow-Origin`.
  - Es una excepción documentada al formato ProblemDetail (decisión 19 de la
    propuesta).
  - El navegador no expone ese cuerpo al front, así que no vale la pena el
    procesador propio que haría falta para mandarlo por el advice.

### D9. Configuración y arranque

**`application.properties`:**
```
liga.google.client-id=${GOOGLE_CLIENT_ID:}
liga.admin.email=${LIGA_ADMIN_EMAIL:}
liga.jwt.secret=${LIGA_JWT_SECRET:}
liga.cors.origenes=${LIGA_CORS_ORIGINS:}
spring.jpa.hibernate.ddl-auto=validate
springdoc.api-docs.enabled=false
springdoc.swagger-ui.enabled=false
```

**`application-dev.properties`:** vuelve a prender los dos `springdoc.*`. No
incluye secretos.

**`LigaPropiedades`:**
- Es un `@ConfigurationProperties("liga")`.
- Se valida con **código propio**, no con Bean Validation: el
  `FailureAnalyzer` de Boot imprime el valor rechazado y filtraría el secreto.
- Cada falla lanza una `IllegalStateException` que nombra la variable de entorno
  (`GOOGLE_CLIENT_ID`, `LIGA_ADMIN_EMAIL` o `LIGA_JWT_SECRET`) y nunca su valor.
- El secreto se mide con `getBytes(UTF_8).length >= 32`.
- El correo de admin se guarda normalizado en minúsculas.

**Reconciliación (`ReconciliadorAdmin`):**
- Es un `ApplicationRunner` transaccional.
- Primero busca un `ADMIN` con `google_sub` nulo cuyo correo es distinto del
  configurado. Si existe, lanza una `IllegalStateException` que nombra el id del
  usuario y `LIGA_ADMIN_EMAIL`, sin modificar nada. Así la aplicación no
  arranca: degradarlo violaría el CHECK de `google_sub`.
  - Es la decisión 27 de la propuesta.
- Si no, hace `UPDATE` a `JUGADOR` de los `ADMIN` cuyo correo es distinto del
  configurado.
- Por cada degradado escribe un `WARN` con su id; no loguea el correo.
- Es idempotente.

**`Clock`:** un bean `Clock` (UTC) que usan el emisor, los validadores y la
auditoría. En los tests se reemplaza por un reloj controlable.

### D10. Persistencia y migración Flyway

`src/main/resources/db/migration/V1__crear_usuarios.sql`:
```sql
CREATE TABLE usuarios (
    id            uuid         PRIMARY KEY,
    google_sub    varchar(255) UNIQUE,
    correo        varchar(320) NOT NULL UNIQUE,
    nombre        text,
    rol           varchar(10)  NOT NULL,
    creado_en     timestamptz  NOT NULL,
    modificado_en timestamptz  NOT NULL,
    CONSTRAINT usuarios_rol_valido CHECK (rol IN ('ADMIN', 'JUGADOR', 'HINCHA')),
    CONSTRAINT usuarios_correo_minusculas CHECK (correo = lower(correo)),
    CONSTRAINT usuarios_sub_solo_admin_vacio CHECK (google_sub IS NOT NULL OR rol = 'ADMIN')
);

CREATE UNIQUE INDEX usuarios_un_solo_admin ON usuarios (rol) WHERE rol = 'ADMIN';
```

- **`google_sub` nulable:**
  - Solo sirve para la recuperación manual del admin; el CHECK
    `usuarios_sub_solo_admin_vacio` lo garantiza.
  - El login siempre crea filas con `sub`, así que el nulo solo aparece cuando
    alguien lo borra a mano en la fila del admin.
  - PostgreSQL permite varios `NULL` en una columna `UNIQUE`.
- **Columnas en español y snake_case:** decisión 26 de la propuesta.
- **`id`:** UUID generado en la app (`@UuidGenerator` de Hibernate), sin depender
  de funciones de PostgreSQL 18.
- **Auditoría:** `creado_en` y `modificado_en` con la auditoría de Spring Data
  JPA (`@EnableJpaAuditing` + `@CreatedDate` y `@LastModifiedDate`), con un
  `DateTimeProvider` basado en el `Clock`. Las entidades futuras reutilizan este
  mecanismo. No se registra un autor: este change no tiene ediciones del admin.
- **Entidad:** `Usuario` con `rol` como `@Enumerated(STRING)`. El service
  devuelve records, nunca la entidad.

### D11. Dependencias (`pom.xml`)

- `spring-boot-starter-security`.
- `spring-boot-starter-security-oauth2-resource-server` (trae
  `spring-security-oauth2-jose` con Nimbus).
- Para tests: `spring-boot-starter-security-test`.

Las versiones las fija el parent de Boot.

## Testing

- **Infraestructura:**
  - `TokensDePrueba`: par RSA de prueba y constructores de tokens de Google
    (válido, otra clave, HS256, `alg: none`, `iss` y `aud` ajenos, vencido,
    `email_verified` falso o ausente) y de JWT propio (válido, otro secreto,
    RS256, `none`, vencido).
  - `@TestConfiguration` que reemplaza `decoderGoogle` y `Clock`.
  - Se usa el `TestcontainersConfiguration` existente.
- **Tests de integración:** `@SpringBootTest` + MockMvc, agrupados por
  requirement. Cada test se nombra con `@DisplayName` igual al nombre exacto del
  Scenario.
- **Casos especiales:**
  - Reconciliación: se inserta un `ADMIN` con otro correo, se ejecuta el
    `ReconciliadorAdmin` del contexto (es el mismo bean que corre al arrancar) y
    se verifica con `GET /api/v1/sesiones/actual` y `OutputCaptureExtension`.
  - Arranque fallido: `ApplicationContextRunner` con la configuración de
    propiedades, que verifica el fallo, el nombre de la variable y que el
    mensaje no contiene el valor.
  - Error 500: `@MockitoBean UsuarioRepository` que lanza
    `DataAccessResourceFailureException`.
  - Swagger: `@ActiveProfiles("dev")` y, aparte, sin perfil.
  - Admin único y "Usuario no admin sin sub": tests de repositorio que verifican
    que la base rechaza la escritura.
  - "Reconciliación con un admin sin sub": se inserta el admin sin `sub` con otro
    correo, se ejecuta el `ReconciliadorAdmin` del contexto y se verifica la
    excepción, su mensaje y que no cambió ninguna fila.
  - "El admin cambia su correo y se actualiza la configuración": se inserta el
    admin con el `sub` X y un correo viejo, se ejecuta el `ReconciliadorAdmin`
    (lo degrada) y después se hace login con el `sub` X y el correo configurado
    en el contexto de test.
  - "Reuso de un token de Google vigente": el mismo token se envía dos veces.
- **Existente:** `BackendFdljApplicationTests` recibe las propiedades
  obligatorias de prueba.

## Risks / Trade-offs

- **Un token robado (XSS en el front) sirve hasta 60 minutos.** → TTL corto. Dónde
  se guarda el token lo decide el front y queda fuera de alcance.
- **Un ID token de Google robado permite loguearse dentro de su hora de vida
  (sin `nonce`).** → Limitación aceptada en la propuesta, con su Scenario. Un
  `nonce` se puede agregar sin cambiar el contrato del JWT propio.
- **Rotar `LIGA_JWT_SECRET` desloguea a todos.** → Aceptable para la liga; se
  documenta.
- **Logout solo del cliente.** → Limitación aceptada.
- **El admin pierde el acceso si su `sub` cambia.** → Recuperación manual
  aceptada: borrar `google_sub` de su fila.
- **El JWKS de Google no está disponible.** → Nimbus cachea las claves. Si no hay
  caché, el login responde 500, no un 401 que culpe al usuario.
- **Hay dos beans `JwtDecoder` y el autoconfigure de Boot podría tomar el
  equivocado.** → Decoder explícito en la cadena y qualifier en el de Google.
- **El filtro bearer se ejecuta en el login.** → Resolver que ignora
  `POST /api/v1/sesiones`, con su Scenario.
- **El rechazo de CORS no es ProblemDetail.** → Excepción documentada; el
  navegador no expone ese cuerpo al front.
- **Un admin sin `sub` y con otro correo bloquea el arranque.** → Es
  intencional: es un estado manual a medio hacer, y el error dice qué fila y qué
  variable revisar.
- **El secreto se filtra en el error de arranque.** → Validación propia, con un
  test que verifica que el valor no aparece.
- **Una consulta a la base por request para el rol.** → Lectura por PK; despreciable
  para la escala de la liga.
- **La reconciliación puede correr en paralelo si hay varias instancias.** → Es un
  `UPDATE` idempotente y se asume una sola instancia, con hosting sin definir.
- **Diferencias de API en Spring Security 7 / Boot 4 respecto de lo descrito.** →
  Se verifican durante la implementación. Si un nombre de clase cambia, el
  diseño sigue valiendo; se actualiza este documento antes del código.

## Migration Plan

- **Despliegue:**
  - La V1 crea `usuarios` en una base vacía; no hay datos que migrar.
  - Antes de desplegar hay que definir `GOOGLE_CLIENT_ID`, `LIGA_ADMIN_EMAIL`,
    `LIGA_JWT_SECRET` (32 bytes o más) y `LIGA_CORS_ORIGINS`. Sin las tres
    primeras, la app no arranca, a propósito.
- **Rollback:** revertir el commit. En una base de desarrollo, borrar
  `usuarios` y la fila de `flyway_schema_history`. No hay producción todavía.

## Open Questions

- Puerto o origen del front en desarrollo, para el valor de `LIGA_CORS_ORIGINS`
  en el entorno local. No cambia la spec: es un valor de entorno.
- Cómo conseguir a mano un ID token de Google para probar en dev (OAuth
  Playground o el front). No cambia la spec ni las tareas.
