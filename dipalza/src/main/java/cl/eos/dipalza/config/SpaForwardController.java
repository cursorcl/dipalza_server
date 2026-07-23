package cl.eos.dipalza.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {

    @GetMapping({ "/{path:(?!ws-posiciones$)[^\\.]*}", "/**/{path:(?!ws-posiciones$)[^\\.]*}" })
    public String forward() {
        return "forward:/index.html";
    }
}
