package cl.eos.dipalza.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class FacturacionDbConfig {

    @Bean(name = "facturacionProperties")
    @ConfigurationProperties("facturacion.datasource")
    public DataSourceProperties facturacionProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "facturacionDataSource")
    public DataSource facturacionDataSource(
            @Qualifier("facturacionProperties") DataSourceProperties properties) { // ← inyectar, no llamar directo
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean(name = "facturacionJdbcTemplate")
    public JdbcTemplate facturacionJdbcTemplate(@Qualifier("facturacionDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "facturacionTransactionManager")
    public PlatformTransactionManager facturacionTransactionManager(
            @Qualifier("facturacionDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource); // Específico para JDBC
    }
}
