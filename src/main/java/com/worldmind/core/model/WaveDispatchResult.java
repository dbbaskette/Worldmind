package com.worldmind.core.model;

import java.io.Serializable;
import java.util.List;

/**
 * Per-task result from parallel wave dispatch.
 * Written by ParallelDispatchNode, read by EvaluateWaveNode.
 */
public record WaveDispatchResult(
    String taskId,
    TaskStatus status,
    List<FileRecord> filesAffected,
    String output,
    long elapsedMs
) implements Serializable {}
