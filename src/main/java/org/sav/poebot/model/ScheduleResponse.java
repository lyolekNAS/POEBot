package org.sav.poebot.model;

import java.util.List;

public record ScheduleResponse(String date, List<InfoBlock> info, List<QueueData> schedule) {}
