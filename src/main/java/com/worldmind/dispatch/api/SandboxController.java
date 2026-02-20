package com.worldmind.dispatch.api;

import com.worldmind.core.model.SandboxInfo;
import com.worldmind.sandbox.SandboxManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for Sandbox container information.
 */
@RestController
@RequestMapping("/api/v1/sandboxes")
public class SandboxController {

    private static final Logger log = LoggerFactory.getLogger(SandboxController.class);

    private final SandboxManager sandboxManager;

    public SandboxController(SandboxManager sandboxManager) {
        this.sandboxManager = sandboxManager;
    }

    /**
     * GET /api/v1/sandboxes — List active sandboxes.
     * Returns an empty list since SandboxManager does not currently track active containers.
     * This endpoint will be enriched when container lifecycle tracking is added.
     */
    @GetMapping
    public List<SandboxInfo> listSandboxes() {
        log.debug("Listing active sandboxes");
        return List.of();
    }
}
