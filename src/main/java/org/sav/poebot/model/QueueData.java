package org.sav.poebot.model;

import java.util.List;

public record QueueData(int queue, List<SubQueue> subqueues) {}
