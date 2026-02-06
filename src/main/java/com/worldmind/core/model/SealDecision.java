package com.worldmind.core.model;

import java.io.Serializable;

/**
 * Result of the quality gate evaluation for a directive.
 * <p>
 * The Seal of Approval is granted when tests pass and the code review
 * score meets the minimum threshold. When denied, the {@link #action}
 * field indicates the {@link FailureStrategy} to apply.
 */
public record SealDecision(
    boolean sealGranted,
    FailureStrategy action,
    String reason
) implements Serializable {}
