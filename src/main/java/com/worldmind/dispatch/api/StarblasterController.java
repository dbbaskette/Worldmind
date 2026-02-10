package com.worldmind.dispatch.api;

import com.worldmind.core.model.StarblasterInfo;
import com.worldmind.starblaster.StarblasterManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for Starblaster container information.
 */
@RestController
@RequestMapping("/api/v1/starblasters")
public class StarblasterController {

    private static final Logger log = LoggerFactory.getLogger(StarblasterController.class);

    private final StarblasterManager starblasterManager;

    public StarblasterController(StarblasterManager starblasterManager) {
        this.starblasterManager = starblasterManager;
    }

    /**
     * GET /api/v1/starblasters — List active starblasters.
     * Returns an empty list since StarblasterManager does not currently track active containers.
     * This endpoint will be enriched when container lifecycle tracking is added.
     */
    @GetMapping
    public List<StarblasterInfo> listStarblasters() {
        log.debug("Listing active starblasters");
        return List.of();
    }
}
