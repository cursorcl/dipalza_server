# Login dipalza_app en deploy_desde_cero Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hacer que `00_crear_base_datos.sql` cree el login SQL `dipalza_app`
y sus usuarios en `[ventas]`/`[Mastersoft]`, replicando exactamente lo que
hoy existe (creado a mano) en el servidor real de producción.

**Architecture:** Un único bloque T-SQL agregado al final de
`00_crear_base_datos.sql`, después de `CREATE DATABASE ventas`. Sin cambios
de aplicación ni de otros scripts del paquete.

**Tech Stack:** T-SQL / SQL Server. Verificación con `sqlcmd` contra el
servidor real (`cursorcl.dynalias.com,1777`), no hay framework de tests
automatizados para este paquete de scripts (ver spec, sección "Cómo se
validó" del paquete original).

## Global Constraints

- El login NO lleva la clave real hardcodeada — placeholder
  `CAMBIAR_ESTA_CLAVE` con comentario indicando que debe coincidir con
  `DB_PASSWORD`/`FACTURACION_DB_PASSWORD` del servidor de la app.
- Estado objetivo exacto (verificado contra el servidor real):
  - LOGIN `dipalza_app`: SQL Login, `CHECK_POLICY = ON`,
    `CHECK_EXPIRATION = OFF`.
  - USER `dipalza_app` en `[ventas]`: miembro de `db_datareader` +
    `db_datawriter`, `CONNECT`.
  - USER `dipalza_app` en `[Mastersoft]`: miembro de `db_datareader` +
    `db_datawriter`, `CONNECT`.
  - Sin permisos a nivel de objeto (no hay llamadas a SPs desde la app).
- Idempotencia: si el login ya existe, `RAISERROR` y abortar — mismo
  patrón que el guard existente de `IF DB_ID(N'ventas') IS NOT NULL`.
- No se modifica el servidor real de producción (el login ya existe ahí).
  Cualquier verificación contra el servidor real debe usar un login de
  prueba desechable y limpiarlo al terminar — pedir confirmación al
  usuario antes de ejecutar ese paso porque toca la instancia compartida.

---

### Task 1: Agregar creación del login y usuarios a 00_crear_base_datos.sql

**Files:**
- Modify: `base_de_datos/deploy_desde_cero/00_crear_base_datos.sql`

**Interfaces:**
- No produce interfaces para otras tareas — es la única tarea de este plan.

- [ ] **Step 1: Agregar el bloque de creación al final del archivo**

Agregar al final de `base_de_datos/deploy_desde_cero/00_crear_base_datos.sql`
(después del `CREATE DATABASE ventas; GO` existente):

```sql
/* ---- Login dedicado de la app (en vez de sa) --------------------------
   Reemplaza CAMBIAR_ESTA_CLAVE por una clave real antes de ejecutar, y
   configura esa misma clave como DB_PASSWORD / FACTURACION_DB_PASSWORD
   en el servidor de la app (mismo patrón que JWT_SECRET: se setea directo
   en el servidor, no vía GitHub Actions). ------------------------------- */
USE master;
GO

IF EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'dipalza_app')
BEGIN
    RAISERROR (N'El login [dipalza_app] ya existe. Este script asume una instalación desde cero; abortando para no pisar un login existente.', 16, 1);
END
GO

CREATE LOGIN dipalza_app
    WITH PASSWORD = N'CAMBIAR_ESTA_CLAVE',
    CHECK_POLICY = ON,
    CHECK_EXPIRATION = OFF;
GO

USE ventas;
GO

CREATE USER dipalza_app FOR LOGIN dipalza_app;
GO

ALTER ROLE db_datareader ADD MEMBER dipalza_app;
ALTER ROLE db_datawriter ADD MEMBER dipalza_app;
GRANT CONNECT TO dipalza_app;
GO

USE Mastersoft;
GO

CREATE USER dipalza_app FOR LOGIN dipalza_app;
GO

ALTER ROLE db_datareader ADD MEMBER dipalza_app;
ALTER ROLE db_datawriter ADD MEMBER dipalza_app;
GRANT CONNECT TO dipalza_app;
GO
```

- [ ] **Step 2: Actualizar el comentario de cabecera del paquete**

En el mismo archivo, en el bloque de comentario superior (líneas ~26-37,
la lista "ORDEN DE EJECUCIÓN"), actualizar la línea de `00` para reflejar
que también crea el login:

```
     00_crear_base_datos.sql          -> crea [ventas] + login dipalza_app (con acceso a [ventas] y [Mastersoft])
```

- [ ] **Step 3: Verificación sintáctica local**

No hay framework de tests para este paquete (son scripts T-SQL ejecutados
manualmente contra SQL Server). Verificar que el archivo quedó bien
formado:

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
grep -c '^GO$' base_de_datos/deploy_desde_cero/00_crear_base_datos.sql
```

Expected: el conteo de `GO` aumenta en 7 respecto al archivo original (uno
por cada bloque `USE`/`CREATE`/`ALTER`+`GRANT` agregado), sin errores de
sintaxis obvios al leer el diff completo.

- [ ] **Step 4: Verificación funcional contra el servidor real (con confirmación previa del usuario)**

Este paso ejecuta SQL real contra la instancia compartida
(`cursorcl.dynalias.com,1777`) — **pedir confirmación explícita antes de
correrlo**, tal como se hizo para validar `07_poblado_inicial_ventas.sql`
en la sesión anterior. Usar un login desechable
(`dipalza_app_test_verify`) para no tocar el login real `dipalza_app`
que ya existe en producción; sustitución de texto en memoria, sin tocar
el archivo real durante la prueba.

```bash
sqlcmd -S cursorcl.dynalias.com,1777 -U sa -P '_l2j1rs2' -C -Q "
CREATE LOGIN dipalza_app_test_verify WITH PASSWORD = 'Temp_Verify_2026!', CHECK_POLICY = ON, CHECK_EXPIRATION = OFF;
USE ventas;
CREATE USER dipalza_app_test_verify FOR LOGIN dipalza_app_test_verify;
ALTER ROLE db_datareader ADD MEMBER dipalza_app_test_verify;
ALTER ROLE db_datawriter ADD MEMBER dipalza_app_test_verify;
GRANT CONNECT TO dipalza_app_test_verify;
USE Mastersoft;
CREATE USER dipalza_app_test_verify FOR LOGIN dipalza_app_test_verify;
ALTER ROLE db_datareader ADD MEMBER dipalza_app_test_verify;
ALTER ROLE db_datawriter ADD MEMBER dipalza_app_test_verify;
GRANT CONNECT TO dipalza_app_test_verify;
"
```

Verificar que los roles quedaron idénticos a los de `dipalza_app` (comparar
con las consultas ya corridas en esta sesión), luego limpiar:

```bash
sqlcmd -S cursorcl.dynalias.com,1777 -U sa -P '_l2j1rs2' -C -Q "
USE ventas; DROP USER dipalza_app_test_verify;
USE Mastersoft; DROP USER dipalza_app_test_verify;
USE master; DROP LOGIN dipalza_app_test_verify;
"
```

Expected: ambas ejecuciones sin errores; la limpieza deja la instancia
exactamente como estaba antes de este paso.

- [ ] **Step 5: Commit**

```bash
git add base_de_datos/deploy_desde_cero/00_crear_base_datos.sql
git commit -m "feat(db): agrega login dipalza_app a deploy_desde_cero

El login se creaba a mano en el servidor (PR #29); ahora queda
scriptado en 00_crear_base_datos.sql con la misma configuración
verificada en producción.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
