package no.scienta.cfp;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProgrammeController {

    private final ProgrammeService service;

    public ProgrammeController(ProgrammeService service) {
        this.service = service;
    }

    /**
     * Programme export as JSON (specs/programme-export/spec.md R1). No produces=: any Accept gets JSON (D12).
     * "/programme/" reaches this through the trailing-slash filter in WebConfig (D15).
     */
    @GetMapping("/programme")
    public ResponseEntity<List<ProgrammeRow>> export() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.export());
    }

    /**
     * Programme export as CSV (R1, R7). Its own literal path, not suffix matching on /programme (D1). The preset
     * Content-Type skips content negotiation, so Accept: application/json still gets CSV (D11).
     */
    @GetMapping("/programme.csv")
    public ResponseEntity<String> exportCsv() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=programme.csv")
                .body(ProgrammeService.toCsv(service.export()));
    }

    @PutMapping({"/programme/{room}/{slot}", "/programme/{room}/{slot}/"})
    public ProgrammeEntry put(@PathVariable String room, @PathVariable String slot,
                              @RequestBody(required = false) String body) {
        return service.put(room, slot, body);
    }

    @GetMapping({"/programme/{room}/{slot}", "/programme/{room}/{slot}/"})
    public ProgrammeEntry get(@PathVariable String room, @PathVariable String slot) {
        return service.get(room, slot);
    }

    @DeleteMapping({"/programme/{room}/{slot}", "/programme/{room}/{slot}/"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String room, @PathVariable String slot) {
        service.delete(room, slot);
    }
}
