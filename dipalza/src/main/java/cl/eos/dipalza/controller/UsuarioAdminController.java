package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ActualizarUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioResultDTO;
import cl.eos.dipalza.model.UsuarioDTO;
import cl.eos.dipalza.service.UsuarioAdminService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/usuarios")
@Profile({ "dev-sec", "prod-sec" })
public class UsuarioAdminController {

    private final UsuarioAdminService service;

    public UsuarioAdminController(UsuarioAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<UsuarioDTO> listar() {
        return service.listar();
    }

    @GetMapping("/{id}")
    public UsuarioDTO obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping
    public CrearUsuarioResultDTO crear(@RequestBody CrearUsuarioDTO req) {
        return service.crear(req);
    }

    @PutMapping("/{id}")
    public UsuarioDTO actualizar(@PathVariable Long id, @RequestBody ActualizarUsuarioDTO req) {
        return service.actualizar(id, req);
    }

    @PatchMapping("/{id}/habilitar")
    public UsuarioDTO habilitar(@PathVariable Long id) {
        return service.habilitar(id);
    }

    @PatchMapping("/{id}/deshabilitar")
    public UsuarioDTO deshabilitar(@PathVariable Long id) {
        return service.deshabilitar(id);
    }

    @PatchMapping("/{id}/bloquear")
    public UsuarioDTO bloquear(@PathVariable Long id) {
        return service.bloquear(id);
    }

    @PatchMapping("/{id}/desbloquear")
    public UsuarioDTO desbloquear(@PathVariable Long id) {
        return service.desbloquear(id);
    }
}
