# Tasks

## 1. Dependencias y configuración base

- [ ] 1.1 Agregar `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server` y `spring-boot-starter-security-test` (test) al `pom.xml`; verificar con `./mvnw -q dependency:tree` que aparece `spring-security-oauth2-jose`
- [ ] 1.2 Agregar a `application.properties` las propiedades `liga.*` mapeadas a variables de entorno, `spring.jpa.hibernate.ddl-auto=validate` y springdoc apagado; crear `application-dev.properties` que lo prende. Verificar con los tests del grupo 7
- [ ] 1.3 Implementar `LigaPropiedades` con validación propia (nombra la variable, nunca el valor; secreto de 32 bytes UTF-8 o más; correo de admin en minúsculas) y el bean `Clock` UTC; verificar con los tests 1.4 a 1.7
- [ ] 1.4 Test de arranque "Falta el client ID de Google" (`ApplicationContextRunner`) y verificar que pasa
- [ ] 1.5 Test de arranque "Falta el correo de admin" y verificar que pasa
- [ ] 1.6 Test de arranque "Falta el secreto del JWT" y verificar que pasa
- [ ] 1.7 Test de arranque "Secreto del JWT corto", verificando además que el mensaje no contiene el valor, y verificar que pasa
- [ ] 1.8 Darle a `BackendFdljApplicationTests` las propiedades obligatorias de prueba; verificar `./mvnw test` en verde

## 2. Persistencia de usuarios

- [ ] 2.1 Crear `db/migration/V1__crear_usuarios.sql` según design D10; verificar que el contexto levanta con Flyway contra Testcontainers y `ddl-auto=validate`
- [ ] 2.2 Crear `usuarios.model.Usuario`, `Rol` y `usuarios.repository.UsuarioRepository`, con la auditoría de Spring Data JPA basada en el `Clock`; verificar con un test de repositorio que guarda y relee un usuario con `creado_en` y `modificado_en`
- [ ] 2.3 Test de repositorio que verifica que la base rechaza un segundo usuario `ADMIN` (índice `usuarios_un_solo_admin`); verificar que pasa
- [ ] 2.4 Test de repositorio "Usuario no admin sin sub" (CHECK `usuarios_sub_solo_admin_vacio`, con JUGADOR y con HINCHA); verificar que pasa

## 3. JWT propio, cadena de seguridad, errores y sesión actual

- [ ] 3.1 Crear `comun.error.TipoError` (catálogo idéntico al de la spec, con `type`, status y `title`), `ApiException` y `ManejadorErrores` (único `@RestControllerAdvice`, sin `about:blank`, 4xx del framework según D7, 500 sin detalles); verificar con los tests 3.8 a 3.10
- [ ] 3.2 Implementar el emisor y el decoder del JWT propio (HS256, `iss=backend-fdlj`, claim `tipo`, validador de `exp` con código `token_vencido` y 60 s de tolerancia); verificar con un test unitario que emite y decodifica los dos tipos de token
- [ ] 3.3 Implementar el converter `Jwt` → autenticación (rol leído de la base, `RegistroPendiente` sin base) y la cadena de seguridad de D6: stateless, sin CSRF, reglas en orden, resolver bearer que ignora `POST /api/v1/sesiones`, y entry point y access-denied handler delegando en el `HandlerExceptionResolver`; verificar con los tests 3.5 a 3.10
- [ ] 3.4 Crear `SesionController` con `GET /api/v1/sesiones/actual` y los DTOs (records) de D1; verificar con los tests 3.5
- [ ] 3.5 Tests "Sesión actual de un usuario", "Sesión actual con registro pendiente" y "Token de usuario de un usuario inexistente"; verificar que pasan
- [ ] 3.6 Tests "Token de usuario vencido" y "Token de registro pendiente vencido" con el `Clock` de prueba; verificar que pasan
- [ ] 3.7 Tests "Request sin token", "JWT propio con firma inválida" y "JWT propio con otro algoritmo"; verificar que pasan
- [ ] 3.8 Tests "Token de registro pendiente en un endpoint normal", "Ruta inexistente con token válido" y "Ruta de error interna"; verificar que pasan
- [ ] 3.9 Tests "Otro método sobre la ruta de login" y "Método no permitido"; verificar que pasan
- [ ] 3.10 Tests "El type y el status coinciden con el catálogo" y "Ningún error usa about:blank"; verificar que pasan

## 4. Login con Google

- [ ] 4.1 Crear `TokensDePrueba` (par RSA de prueba y variantes de tokens de Google y de JWT propio) y la `@TestConfiguration` que reemplaza `decoderGoogle` y `Clock`; verificar que la usan los tests de este grupo
- [ ] 4.2 Implementar `ValidadoresTokenGoogle`, el bean `decoderGoogle` (JWKS fijo, RS256) y `VerificadorTokenGoogle` con el mapeo de excepciones de D3; verificar con los tests 4.5 y 4.6
- [ ] 4.3 Implementar `LoginService` según D4 (`email_verified`, normalización del correo, búsqueda por `sub`, admin con `sub` fijado, recuperación manual, sincronización de correo y nombre, recálculo del rol, 409, registro pendiente sin escritura, `DataIntegrityViolationException` → 409); verificar con los tests 4.7 a 4.10
- [ ] 4.4 Agregar `POST /api/v1/sesiones` a `SesionController` con `@Valid` sobre `tokenGoogle`; verificar con los tests 4.5
- [ ] 4.5 Tests "Login de un usuario existente", "Login con un JWT propio vencido en el header", "Login con un JWT propio válido no reemplaza al token de Google", "Cuerpo sin token de Google" y "Login sin token"; verificar que pasan
- [ ] 4.6 Tests "Token de Google con firma inválida", "Token de Google con otro algoritmo", "Token de Google de otro emisor", "Token de Google para otra aplicación", "Token de Google vencido", "Token de Google mal formado", "El token no aparece en el log" (con `OutputCaptureExtension`) y "Reuso de un token de Google vigente"; verificar que pasan
- [ ] 4.7 Tests "Login con correo verificado" y "Login con correo no verificado"; verificar que pasan
- [ ] 4.8 Tests "Primer login del admin", "Correo del admin con otro sub" y "Recuperación manual del admin"; verificar que pasan
- [ ] 4.9 Tests "Cambio de nombre en Google", "El admin cambia su correo en Google" y "Correo nuevo de otro usuario"; verificar que pasan
- [ ] 4.10 Tests "Login de una cuenta sin registrar", "Vencimiento del token de usuario" y "Vencimiento del token de registro pendiente"; verificar que pasan
- [ ] 4.11 Tests "ID token de Google usado como JWT propio", "Tipo de contenido no soportado", "JSON mal formado" y "Error inesperado" (con `@MockitoBean UsuarioRepository`); verificar que pasan
- [ ] 4.12 Test "Arranque con la configuración completa"; verificar que pasa
- [ ] 4.13 Tests "El login indica cuenta registrada", "El login indica registro pendiente" y "Un login rechazado no indica estado"; verificar que pasan

## 5. Reconciliación del admin al arrancar

- [ ] 5.1 Implementar `ReconciliadorAdmin` según D9 (`ApplicationRunner` transaccional e idempotente; falla sin modificar nada si hay un admin sin `sub` con otro correo; `WARN` con el id por cada admin degradado); verificar con los tests 5.2 y 5.3
- [ ] 5.2 Tests "Cambio del correo de admin configurado" (con `OutputCaptureExtension` para el aviso), "El admin anterior no recupera el rol" y "Reconciliación con un admin sin sub"; verificar que pasan
- [ ] 5.3 Test "El admin cambia su correo y se actualiza la configuración" (reconciliación + login con el mismo `sub`, mismo `usuario.id`); verificar que pasa

## 6. CORS

- [ ] 6.1 Implementar la `CorsConfigurationSource` de D8 en la cadena de Spring Security, con el rechazo por defecto de Spring; verificar con los tests 6.2
- [ ] 6.2 Tests "Preflight de un origen permitido", "Preflight de un origen no permitido", "Request de un origen no permitido" y "Sin orígenes configurados" (403 sin `Access-Control-Allow-Origin`); verificar que pasan

## 7. Swagger solo en desarrollo

- [ ] 7.1 Crear la cadena de seguridad `@Profile("dev")` `@Order(1)` para las rutas de Swagger y el bean `OpenAPI` de dev con esquema `bearerAuth`; verificar con los tests 7.2
- [ ] 7.2 Tests "Swagger en desarrollo" (`@ActiveProfiles("dev")`), "Swagger fuera de desarrollo sin token" y "Swagger fuera de desarrollo con token"; verificar que pasan

## 8. Cierre

- [ ] 8.1 Correr `./mvnw test` completo con Docker abierto; verificar que está en verde y que cada Scenario de la spec tiene un test con su nombre exacto
- [ ] 8.2 Correr `openspec validate liga-api-agregar-login-google --strict`; verificar que pasa
- [ ] 8.3 Mostrar el diff al usuario y, con su confirmación explícita, hacer push y abrir el PR `[liga-api-agregar-login-google] ...` con la plantilla completa; verificar que el PR existe
- [ ] 8.4 Esperar la aprobación humana del PR, publicada por una persona; verificar que figura en el PR
- [ ] 8.5 Con el PR revisado y los tests en verde, ejecutar `/opsx:archive` como último commit del branch, antes del merge; verificar que el change quedó en `openspec/changes/archive/` y que existe `openspec/specs/autenticacion/spec.md`
