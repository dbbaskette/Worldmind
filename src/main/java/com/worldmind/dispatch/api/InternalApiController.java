package com.worldmind.dispatch.api;

import com.worldmind.starblaster.InstructionStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API endpoints used by CF task containers.
 *
 * <p>CF task commands are limited to 4096 characters, so instruction text
 * is stored on the orchestrator and fetched via HTTP by the centurion tasks.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalApiController {

    private final InstructionStore instructionStore;

    public InternalApiController(InstructionStore instructionStore) {
        this.instructionStore = instructionStore;
    }

    @GetMapping(value = "/instructions/{key}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getInstruction(@PathVariable String key) {
        String instruction = instructionStore.get(key);
        if (instruction == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(instruction);
    }
}
