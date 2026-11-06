package no.scienta.cfp;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/programme")
public class ProgrammeController {

    private final ProgrammeService service;

    public ProgrammeController(ProgrammeService service) {
        this.service = service;
    }

    @PutMapping({"/{room}/{slot}", "/{room}/{slot}/"})
    public ProgrammeEntry put(@PathVariable String room, @PathVariable String slot,
                              @RequestBody(required = false) String body) {
        return service.put(room, slot, body);
    }

    @GetMapping({"/{room}/{slot}", "/{room}/{slot}/"})
    public ProgrammeEntry get(@PathVariable String room, @PathVariable String slot) {
        return service.get(room, slot);
    }

    @DeleteMapping({"/{room}/{slot}", "/{room}/{slot}/"})
    public ProgrammeEntry delete(@PathVariable String room, @PathVariable String slot) {
        return service.delete(room, slot);
    }
}
