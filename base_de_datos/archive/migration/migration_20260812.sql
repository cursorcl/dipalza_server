-- Agrega el flag de cambio de clave obligatorio y elimina la
-- infraestructura del codigo de 6 digitos (reemplazada por clave
-- temporal enviada por correo -- ver AuthController.forgotPassword).

SET QUOTED_IDENTIFIER ON;
GO

BEGIN TRAN;

ALTER TABLE dbo.app_user
    ADD must_change_password bit NOT NULL DEFAULT 0;
GO

DROP TABLE dbo.app_password_reset_token;

COMMIT TRAN;
