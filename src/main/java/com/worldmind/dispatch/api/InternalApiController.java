package com.worldmind.dispatch.api;

import com.worldmind.sandbox.InstructionStore;
import com.worldmind.sandbox.OutputStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API endpoints used by CF task containers.
 *
 * <p>CF task commands are limited to 4096 characters, so instruction text
 * is stored on the orchestrator and fetched via HTTP by the agent tasks.
 *
 * <p>CF tasks also POST their Goose output back here, since the CF API v3
 * does not expose task stdout/stderr.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalApiController {

    private static final Logger log = LoggerFactory.getLogger(InternalApiController.class);

    private final InstructionStore instructionStore;
    private final OutputStore outputStore;

    public InternalApiController(InstructionStore instructionStore, OutputStore outputStore) {
        this.instructionStore = instructionStore;
        this.outputStore = outputStore;
    }

    @GetMapping(value = "/instructions/{key}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getInstruction(@PathVariable String key) {
        String instruction = instructionStore.get(key);
        if (instruction == null) {
            log.warn("Instruction not found for key: {} (store has {} entries)",
                    key, instructionStore.size());
            return ResponseEntity.notFound().build();
        }
        log.info("Serving instruction for key: {} ({} chars)", key, instruction.length());
        return ResponseEntity.ok(instruction);
    }

    @PutMapping(value = "/output/{key}", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> putOutput(@PathVariable String key, @RequestBody String output) {
        outputStore.put(key, output);
        log.info("Received output for key: {} ({} chars)", key, output.length());
        return ResponseEntity.ok().build();
    }
}
