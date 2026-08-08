-- Habilita cambio de clave autenticado y recuperacion de clave por
-- correo ("olvide mi clave"):
--
-- 1) Agrega dbo.app_user.email (nullable, unico) -- hoy no existe ningun
--    campo de correo en el modelo de usuario. Queda NULL para usuarios
--    existentes hasta que se les cargue un correo.
-- 2) Agrega dbo.app_password_reset_token, mismo patron que
--    dbo.app_refresh_token: token opaco hasheado con SHA-256, con
--    expiracion corta y marca de uso unico.
--
-- Ver AuthController (endpoints /auth/forgot-password, /auth/reset-password)
-- y el nuevo UsuarioController (PUT /api/usuario/cambiar-clave).

BEGIN TRAN;

ALTER TABLE dbo.app_user
    ADD email varchar(255) COLLATE Modern_Spanish_CI_AS NULL;

ALTER TABLE dbo.app_user
    ADD CONSTRAINT UQ_app_user_email UNIQUE (email);

CREATE TABLE dbo.app_password_reset_token (
    id         bigint IDENTITY(1,1) NOT NULL,
    user_id    bigint NOT NULL,
    token_hash varchar(200) COLLATE Modern_Spanish_CI_AS NOT NULL,
    expires_at datetime NOT NULL,
    used       bit NOT NULL DEFAULT 0,
    created_at datetime NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_app_password_reset_token PRIMARY KEY (id),
    CONSTRAINT UQ_app_password_reset_token UNIQUE (user_id, token_hash),
    CONSTRAINT FK_app_password_reset_token_user FOREIGN KEY (user_id) REFERENCES dbo.app_user(id)
);

COMMIT TRAN;
