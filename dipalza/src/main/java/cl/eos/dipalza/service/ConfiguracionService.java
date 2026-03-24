package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Configuracion;
import cl.eos.dipalza.repository.ConfiguracionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConfiguracionService {

    private final ConfiguracionRepository repo;

    private Map<String, String> cache = new ConcurrentHashMap<>();

    public ConfiguracionService(ConfiguracionRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void cargarCache() {
        try {
            repo.findAll().forEach(c -> cache.put(c.getPropiedad(), c.getValor()));
        } catch (Exception e) {
            // Tabla no existe aún o BD inaccesible — continúa sin cache
            System.err.println("[ConfiguracionService] No se pudo cargar cache: " + e.getMessage());
        }
    }

    public void recargarCache() {
        cache.clear();
        cargarCache();
    }

    public String getString(String clave) {
        return cache.getOrDefault(clave, "");
    }

    public Integer getInt(String clave) {
        String val = cache.get(clave);
        if (val == null) return 0;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    public Double getDouble(String clave) {
        String val = cache.get(clave);
        if (val == null) return 0D;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 0D;
        }
    }

    public Boolean getBoolean(String clave) {
        String val = cache.get(clave);
        return Boolean.parseBoolean(val);
    }
    
    @Transactional
    public void actualizarConfig(String clave, String nuevoValor) {
        Configuracion config = repo.findById(clave)
            .orElseThrow(() -> new RuntimeException("Config no existe"));
        
        config.setValor(nuevoValor);
        repo.save(config);
        cache.put(clave, nuevoValor);
    }
}