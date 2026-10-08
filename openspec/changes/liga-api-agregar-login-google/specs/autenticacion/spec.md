# Spec Delta: autenticacion

## Purpose

Permite entrar a la API con una cuenta de Google y protege todos los endpoints
con un token propio. Identifica al admin por configuración y define el manejo
único de errores y el acceso transversal: endpoints públicos, CORS y Swagger.

## ADDED Requirements

### Requirement: Login con token de Google
El sistema SHALL ofrecer `POST /api/v1/sesiones` con cuerpo JSON `{"tokenGoogle": "<ID token>"}`.
Si el token de Google es válido, responde 200 con un JWT propio, su tipo y su vencimiento.
Si el usuario ya existe (se busca por el `sub` de Google), el tipo es `USUARIO`.
El endpoint MUST ignorar el header `Authorization`.

#### Scenario: Login de un usuario existente
- **WHEN** un usuario existente envía `POST /api/v1/sesiones` con un token de Google válido
- **THEN** la respuesta es 200 con `token`, `tipo` = `USUARIO`, `expiraEn` y los datos del usuario

#### Scenario: Login con un JWT propio vencido en el header
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google válido y `Authorization: Bearer <JWT propio vencido>`
- **THEN** la respuesta es 200 con un JWT propio nuevo

#### Scenario: Login con un JWT propio válido no reemplaza al token de Google
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google inválido y `Authorization: Bearer <JWT propio válido>`
- **THEN** la respuesta es 401 con `type` `/errores/token-google-invalido`

#### Scenario: Cuerpo sin token de Google
- **WHEN** se envía `POST /api/v1/sesiones` con `tokenGoogle` ausente o vacío
- **THEN** la respuesta es 400 con `type` `/errores/solicitud-invalida`

### Requirement: Validación del token de Google
El sistema MUST aceptar un token de Google solo si cumple todo lo siguiente:
- Está firmado con RS256 por una clave publicada por Google.
- `iss` es `accounts.google.com` o `https://accounts.google.com`.
- `aud` es el client ID configurado.
- No venció y `iat` no es futuro, con hasta 60 segundos de tolerancia.
- Trae `sub`.

Cualquier falla responde 401 con un único `type`, sin revelar qué chequeo falló.

#### Scenario: Token de Google con firma inválida
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google cuya firma no corresponde a una clave de Google
- **THEN** la respuesta es 401 con `type` `/errores/token-google-invalido`

#### Scenario: Token de Google con otro algoritmo
- **WHEN** se envía `POST /api/v1/sesiones` con un token firmado con HS256 o sin firma (`alg: none`), con claims por lo demás válidos
- **THEN** la respuesta es 401 con `type` `/errores/token-google-invalido`

#### Scenario: Token de Google de otro emisor
- **WHEN** se envía `POST /api/v1/sesiones` con un token cuyo `iss` no es de Google
- **THEN** la respuesta es 401 con `type` `/errores/token-google-invalido`

#### Scenario: Token de Google para otra aplicación
- **WHEN** se envía `POST /api/v1/sesiones` con un token cuyo `aud` no es el client ID configurado
- **THEN** la respuesta es 401 con `type` `/errores/token-google-invalido`

#### Scenario: Token de Google vencido
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google cuyo `exp` pasó hace más de 60 segundos
- **THEN** la respuesta es 401 con `type` `/errores/token-google-invalido`

#### Scenario: Token de Google mal formado
- **WHEN** se envía `POST /api/v1/sesiones` con un `tokenGoogle` que no es un JWT
- **THEN** la respuesta es 401 con `type` `/errores/token-google-invalido`

#### Scenario: El token no aparece en el log
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google rechazado
- **THEN** la respuesta es 401 y el log de la aplicación no contiene el token

### Requirement: Correo verificado
El sistema MUST rechazar el login si el token de Google no trae `email_verified` en `true`, aunque el token sea válido en todo lo demás.

#### Scenario: Login con correo verificado
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google válido con `email_verified` = `true`
- **THEN** la respuesta es 200

#### Scenario: Login con correo no verificado
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google válido con `email_verified` = `false` o sin ese claim
- **THEN** la respuesta es 403 con `type` `/errores/correo-no-verificado`

### Requirement: Admin único por configuración
El admin es el usuario cuyo correo coincide con el correo de admin configurado. Se compara en minúsculas, sin quitar puntos ni sufijos.
En el primer login con ese correo, el sistema SHALL crear al admin y fijar su `sub` de Google.
Después, ese correo MUST aceptarse solo con el `sub` fijado.
Si el `sub` fijado se borró a mano, el siguiente login con el correo configurado lo vuelve a fijar.

#### Scenario: Primer login del admin
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google válido cuyo correo, en minúsculas, es el configurado y no existe usuario con ese correo
- **THEN** la respuesta es 200 con `tipo` = `USUARIO` y rol `ADMIN`
- **AND** el usuario queda guardado con ese `sub`

#### Scenario: Correo del admin con otro sub
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google válido con el correo del admin y un `sub` distinto del fijado
- **THEN** la respuesta es 409 con `type` `/errores/cuenta-en-conflicto` y no se crea ni modifica ningún usuario

#### Scenario: Recuperación manual del admin
- **WHEN** el `sub` del admin se borró a mano y se envía `POST /api/v1/sesiones` con un token de Google válido con el correo configurado y un `sub` nuevo
- **THEN** la respuesta es 200 con rol `ADMIN` y el `sub` nuevo queda fijado

### Requirement: Reconciliación del admin al arrancar
Al arrancar, el sistema SHALL pasar a `JUGADOR` a todo usuario con rol `ADMIN` cuyo correo no coincida con el configurado, conservando su historial.
Por cada degradación MUST escribir un aviso en el log.
El nuevo admin se crea o se promueve en su primer login.

#### Scenario: Cambio del correo de admin configurado
- **WHEN** la aplicación arranca con un correo de admin distinto del correo del admin guardado, y el admin anterior envía `GET /api/v1/sesiones/actual` con un token de usuario emitido antes del arranque
- **THEN** la respuesta es 200 con rol `JUGADOR`
- **AND** el log contiene un aviso de la degradación

#### Scenario: El admin anterior no recupera el rol
- **WHEN** el admin anterior, ya degradado, envía `POST /api/v1/sesiones` con un token de Google válido
- **THEN** la respuesta es 200 con rol `JUGADOR`, no `ADMIN`

### Requirement: Datos del usuario sincronizados con Google
En cada login de un usuario existente, el sistema SHALL actualizar su correo (en minúsculas) y su nombre con los del token de Google.
El rol `ADMIN` MUST recalcularse con el correo actualizado.
Si el correo nuevo pertenece a otro usuario, el login se rechaza.

#### Scenario: Cambio de nombre en Google
- **WHEN** un usuario existente envía `POST /api/v1/sesiones` con un token de Google válido con un nombre distinto del guardado
- **THEN** la respuesta es 200 con el nombre nuevo y el nombre queda actualizado

#### Scenario: El admin cambia su correo en Google
- **WHEN** el admin envía `POST /api/v1/sesiones` con un token de Google válido con su `sub` y un correo distinto del configurado
- **THEN** la respuesta es 200 con rol `JUGADOR` y el correo queda actualizado

#### Scenario: Correo nuevo de otro usuario
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google válido cuyo correo ya pertenece a otro usuario con otro `sub`
- **THEN** la respuesta es 409 con `type` `/errores/cuenta-en-conflicto` y no se modifica ningún usuario

### Requirement: Login sin registro
Si el `sub` no corresponde a un usuario y el correo no es el del admin ni pertenece a otro usuario, el sistema SHALL responder 200 con un JWT propio de tipo `REGISTRO_PENDIENTE` y no MUST guardar ningún usuario.
Ese token solo es válido en `GET /api/v1/sesiones/actual`.

#### Scenario: Login de una cuenta sin registrar
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google válido de una cuenta que no es usuario ni admin
- **THEN** la respuesta es 200 con `tipo` = `REGISTRO_PENDIENTE`, `expiraEn` y sin datos de usuario
- **AND** no se crea ningún usuario

#### Scenario: Token de registro pendiente en un endpoint normal
- **WHEN** se envía `GET /api/v1/partidos` con `Authorization: Bearer <token de registro pendiente válido>`
- **THEN** la respuesta es 403 con `type` `/errores/registro-pendiente`

### Requirement: Duración del JWT propio
El sistema SHALL emitir el token de usuario con vencimiento a los 60 minutos y el de registro pendiente a los 15, sin renovación.
Un JWT propio vencido MUST rechazarse con un `type` propio, distinto del de un token inválido.

#### Scenario: Vencimiento del token de usuario
- **WHEN** un usuario existente envía `POST /api/v1/sesiones` con un token de Google válido
- **THEN** la respuesta es 200 con `expiraEn` igual al momento de emisión más 60 minutos

#### Scenario: Vencimiento del token de registro pendiente
- **WHEN** una cuenta sin registrar envía `POST /api/v1/sesiones` con un token de Google válido
- **THEN** la respuesta es 200 con `expiraEn` igual al momento de emisión más 15 minutos

#### Scenario: Token de usuario vencido
- **WHEN** se envía `GET /api/v1/sesiones/actual` con un token de usuario emitido hace más de 60 minutos (más la tolerancia de 60 segundos)
- **THEN** la respuesta es 401 con `type` `/errores/token-vencido`

#### Scenario: Token de registro pendiente vencido
- **WHEN** se envía `GET /api/v1/sesiones/actual` con un token de registro pendiente emitido hace más de 15 minutos (más la tolerancia de 60 segundos)
- **THEN** la respuesta es 401 con `type` `/errores/token-vencido`

### Requirement: Consulta de la sesión actual
El sistema SHALL ofrecer `GET /api/v1/sesiones/actual`.
Con un token de usuario devuelve `tipo` = `USUARIO` y los datos del usuario (id, correo, nombre y rol). El rol se lee de la base en cada request.
Con un token de registro pendiente devuelve `tipo` = `REGISTRO_PENDIENTE`, el correo y el nombre de Google.

#### Scenario: Sesión actual de un usuario
- **WHEN** se envía `GET /api/v1/sesiones/actual` con un token de usuario válido
- **THEN** la respuesta es 200 con `tipo` = `USUARIO` y el id, el correo, el nombre y el rol actual del usuario

#### Scenario: Sesión actual con registro pendiente
- **WHEN** se envía `GET /api/v1/sesiones/actual` con un token de registro pendiente válido
- **THEN** la respuesta es 200 con `tipo` = `REGISTRO_PENDIENTE`, el correo y el nombre

#### Scenario: Token de usuario de un usuario inexistente
- **WHEN** se envía `GET /api/v1/sesiones/actual` con un token de usuario bien firmado cuyo usuario no existe en la base
- **THEN** la respuesta es 401 con `type` `/errores/no-autenticado`

### Requirement: Todo endpoint exige un JWT propio válido
Toda ruta que no esté en la lista de endpoints públicos MUST exigir un JWT propio válido en el header `Authorization: Bearer`.
Un JWT propio es válido si:
- Está firmado con HS256 con el secreto configurado.
- Su emisor es esta API.
- Trae el claim `tipo`.
- No venció.

Un ID token de Google no es un JWT propio.

#### Scenario: Request sin token
- **WHEN** se envía `GET /api/v1/partidos` sin header `Authorization`
- **THEN** la respuesta es 401 con `type` `/errores/no-autenticado`

#### Scenario: JWT propio con firma inválida
- **WHEN** se envía `GET /api/v1/sesiones/actual` con un JWT firmado con otro secreto
- **THEN** la respuesta es 401 con `type` `/errores/no-autenticado`

#### Scenario: JWT propio con otro algoritmo
- **WHEN** se envía `GET /api/v1/sesiones/actual` con un JWT con claims válidos firmado con RS256 o sin firma (`alg: none`)
- **THEN** la respuesta es 401 con `type` `/errores/no-autenticado`

#### Scenario: ID token de Google usado como JWT propio
- **WHEN** se envía `GET /api/v1/sesiones/actual` con `Authorization: Bearer <token de Google válido>`
- **THEN** la respuesta es 401 con `type` `/errores/no-autenticado`

#### Scenario: Ruta inexistente con token válido
- **WHEN** se envía `GET /api/v1/partidos` con un token de usuario válido
- **THEN** la respuesta es 404 con `type` `/errores/recurso-no-encontrado`

### Requirement: Lista de endpoints públicos
Los únicos endpoints que el sistema MUST atender sin JWT propio son:
- `POST /api/v1/sesiones`.
- Solo en el perfil de desarrollo, las rutas de Swagger: `/v3/api-docs/**`, `/swagger-ui.html` y `/swagger-ui/**`.
- Los preflight CORS.

#### Scenario: Login sin token
- **WHEN** se envía `POST /api/v1/sesiones` sin header `Authorization` y con un token de Google válido
- **THEN** la respuesta es 200

#### Scenario: Otro método sobre la ruta de login
- **WHEN** se envía `GET /api/v1/sesiones` sin header `Authorization`
- **THEN** la respuesta es 401 con `type` `/errores/no-autenticado`

#### Scenario: Ruta de error interna
- **WHEN** se envía `GET /error` sin header `Authorization`
- **THEN** la respuesta es 401 con `type` `/errores/no-autenticado`

### Requirement: Swagger solo en desarrollo
Con el perfil de desarrollo activo, el sistema SHALL servir la documentación OpenAPI y Swagger UI sin JWT propio.
Fuera de ese perfil MUST no servirlas: exigen JWT propio como cualquier otra ruta y no existen.

#### Scenario: Swagger en desarrollo
- **WHEN** con el perfil `dev` activo se envía `GET /v3/api-docs` y `GET /swagger-ui/index.html` sin header `Authorization`
- **THEN** ambas respuestas son 200

#### Scenario: Swagger fuera de desarrollo sin token
- **WHEN** sin el perfil `dev` se envía `GET /v3/api-docs` sin header `Authorization`
- **THEN** la respuesta es 401 con `type` `/errores/no-autenticado`

#### Scenario: Swagger fuera de desarrollo con token
- **WHEN** sin el perfil `dev` se envía `GET /v3/api-docs` con un token de usuario válido
- **THEN** la respuesta es 404 con `type` `/errores/recurso-no-encontrado`

### Requirement: CORS por configuración
El sistema SHALL aceptar requests cruzadas solo de los orígenes configurados, comparados exactamente y sin comodines.
No MUST habilitar credenciales del navegador (cookies).
Los preflight de un origen permitido no exigen JWT propio.
Sin orígenes configurados, no se acepta ningún origen cruzado.

#### Scenario: Preflight de un origen permitido
- **WHEN** se envía `OPTIONS /api/v1/sesiones/actual` sin `Authorization`, con `Origin` permitido, `Access-Control-Request-Method: GET` y `Access-Control-Request-Headers: authorization`
- **THEN** la respuesta es 200 con `Access-Control-Allow-Origin` igual a ese origen y sin `Access-Control-Allow-Credentials: true`

#### Scenario: Preflight de un origen no permitido
- **WHEN** se envía `OPTIONS /api/v1/sesiones` con un `Origin` que no está en la configuración y `Access-Control-Request-Method: POST`
- **THEN** la respuesta es 403 con `type` `/errores/origen-no-permitido` y sin `Access-Control-Allow-Origin`

#### Scenario: Request de un origen no permitido
- **WHEN** se envía `POST /api/v1/sesiones` con un `Origin` que no está en la configuración
- **THEN** la respuesta es 403 con `type` `/errores/origen-no-permitido`

#### Scenario: Sin orígenes configurados
- **WHEN** no hay orígenes configurados y se envía `OPTIONS /api/v1/sesiones` con cualquier `Origin` y `Access-Control-Request-Method: POST`
- **THEN** la respuesta es 403 con `type` `/errores/origen-no-permitido`

### Requirement: Configuración obligatoria al arrancar
La aplicación MUST no arrancar si:
- Falta el client ID de Google.
- Falta el correo de admin.
- Falta el secreto del JWT propio.
- El secreto del JWT propio mide menos de 32 bytes en UTF-8.

El error de arranque nombra la variable que falla, sin mostrar su valor.
Estos escenarios no tienen método ni ruta HTTP porque la aplicación no llega a atender requests.

#### Scenario: Arranque con la configuración completa
- **WHEN** la aplicación arranca con el client ID, el correo de admin y un secreto de al menos 32 bytes
- **THEN** arranca y `POST /api/v1/sesiones` con un token de Google válido responde 200

#### Scenario: Falta el client ID de Google
- **WHEN** la aplicación arranca sin `GOOGLE_CLIENT_ID`
- **THEN** el arranque falla con un error que nombra `GOOGLE_CLIENT_ID`

#### Scenario: Falta el correo de admin
- **WHEN** la aplicación arranca sin `LIGA_ADMIN_EMAIL`
- **THEN** el arranque falla con un error que nombra `LIGA_ADMIN_EMAIL`

#### Scenario: Falta el secreto del JWT
- **WHEN** la aplicación arranca sin `LIGA_JWT_SECRET`
- **THEN** el arranque falla con un error que nombra `LIGA_JWT_SECRET`

#### Scenario: Secreto del JWT corto
- **WHEN** la aplicación arranca con un `LIGA_JWT_SECRET` de 31 bytes
- **THEN** el arranque falla con un error que nombra `LIGA_JWT_SECRET` y no muestra su valor

### Requirement: Errores como ProblemDetail con type estable
Toda respuesta de error SHALL ser un ProblemDetail (RFC 9457) con `type`, `title`, `status` y `detail`.
El `type` es una URI relativa estable del catálogo de la spec `autenticacion`, nunca `about:blank`.
Los errores 5xx MUST no exponer detalles internos.

#### Scenario: Método no permitido
- **WHEN** se envía `DELETE /api/v1/sesiones/actual` con un token de usuario válido
- **THEN** la respuesta es 405 con `type` `/errores/metodo-no-permitido`

#### Scenario: Tipo de contenido no soportado
- **WHEN** se envía `POST /api/v1/sesiones` con `Content-Type: text/plain`
- **THEN** la respuesta es 415 con `type` `/errores/tipo-de-contenido-no-soportado`

#### Scenario: JSON mal formado
- **WHEN** se envía `POST /api/v1/sesiones` con un cuerpo que no es JSON válido
- **THEN** la respuesta es 400 con `type` `/errores/solicitud-invalida`

#### Scenario: Error inesperado
- **WHEN** se envía `POST /api/v1/sesiones` con un token de Google válido y falla el acceso a la base
- **THEN** la respuesta es 500 con `type` `/errores/error-interno` y el `detail` no contiene trazas, SQL ni nombres de clases

### Requirement: Catálogo de errores transversales
El sistema SHALL usar estos `type`:

| type | status |
|---|---|
| `/errores/no-autenticado` | 401 |
| `/errores/token-vencido` | 401 |
| `/errores/token-google-invalido` | 401 |
| `/errores/correo-no-verificado` | 403 |
| `/errores/registro-pendiente` | 403 |
| `/errores/origen-no-permitido` | 403 |
| `/errores/cuenta-en-conflicto` | 409 |
| `/errores/solicitud-invalida` | 400, u otro 4xx del framework sin `type` propio, que conserva su status |
| `/errores/recurso-no-encontrado` | 404 |
| `/errores/metodo-no-permitido` | 405 |
| `/errores/tipo-de-contenido-no-soportado` | 415 |
| `/errores/error-interno` | 500 |

#### Scenario: El type y el status coinciden con el catálogo
- **WHEN** se envía `GET /api/v1/partidos` sin header `Authorization`
- **THEN** la respuesta es 401, `Content-Type` es `application/problem+json` y `type` es `/errores/no-autenticado`

#### Scenario: Ningún error usa about:blank
- **WHEN** se envía `GET /api/v1/no-existe` con un token de usuario válido
- **THEN** la respuesta es 404 con `type` `/errores/recurso-no-encontrado` y no `about:blank`
