package cl.eos.dipalza.utils;

public final class Constants {
    private Constants() {}

    public static final  String TIPO_DOCUMENTO_FACTURA = "06";
    public static final String LOCAL_000 = "000";
    public static final float PARIDAD = 1.f;
    public static final String ESTADO_NUMERADO_DISPONIBLE ="D";
    public static final String ESTADO_NUMERADO_RESERVADO ="R";
    public static final String ESTADO_NUMERADO_VENDIDO ="V";
    public static final String  INSERT_DETALLE_DOCUMENTO  =
            """
            insert into detalledocumento ( precioventa, totallinea, paridad, preciocosto, cantidad, id, linea, tipoid, local, articulo, descripcion, variacion ) 
            values  ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? )
           """;

}
